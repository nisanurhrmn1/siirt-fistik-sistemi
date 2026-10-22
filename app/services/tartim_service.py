"""Kantar → kalite → fiyat kilidi → hakediş → stok: tek transaction'da teslimat kaydı."""
from datetime import date
from decimal import Decimal

from sqlalchemy import case, func, select
from sqlalchemy.orm import Session, selectinload

from app.core.errors import BusinessRuleError, NotFoundError
from app.core.format import tl
from app.core.timeutil import day_bounds_utc
from app.db.models import (
    Advance, DeductionType, Lot, Producer, QualityGrade, QualityInspection, Silo, StockMovement, WeighTicket,
)
from app.domain import rules
from app.schemas.tartim import (
    FiyatKilidi, KaliteSonucu, KesintiOnizleme, OnizlemeGirisi, StokKaydi, TartimGirisi, TartimOnizleme,
    TartimOzeti, TartimYaniti,
)
from app.services.finans_service import build_hakedis_yaniti


def get_ticket(db: Session, ticket_id: int) -> WeighTicket:
    t = db.get(WeighTicket, ticket_id)
    if t is None:
        raise NotFoundError(f"Tartım bulunamadı (id={ticket_id}).")
    return t


def _choose_silo(db: Session, lot: Lot) -> Silo:
    """Önce lotun sınıfına adanmış, sonra adanmamış silolar; kapasitesi ve kalite uyumu olan ilki."""
    candidates = db.scalars(
        select(Silo)
        .where(Silo.is_active.is_(True),
               (Silo.dedicated_grade_id == lot.quality_grade_id) | Silo.dedicated_grade_id.is_(None))
        .order_by(case((Silo.dedicated_grade_id == lot.quality_grade_id, 0), else_=1), Silo.code)
    ).all()
    for silo in candidates:
        try:
            rules.check_silo_accepts(db, silo, lot, lot.initial_kg)
            return silo
        except BusinessRuleError:
            continue
    raise BusinessRuleError(
        f"{lot.quality_grade.code} sınıfı {lot.initial_kg} kg için uygun (aynı kalite, yeterli kapasite) silo yok.")


def register_delivery(db: Session, data: TartimGirisi, user_id: int) -> WeighTicket:
    producer = db.get(Producer, data.uretici_id)
    if producer is None:
        raise NotFoundError(f"Üretici bulunamadı (id={data.uretici_id}).")
    if not producer.is_active:
        raise BusinessRuleError("Pasif üreticiden alım yapılamaz.")

    silo = None
    if data.silo_id is not None:
        silo = db.get(Silo, data.silo_id)
        if silo is None:
            raise NotFoundError(f"Silo bulunamadı (id={data.silo_id}).")

    override_id = None
    if data.manuel_kalite_kodu:
        grade = db.scalar(select(QualityGrade).where(QualityGrade.code == data.manuel_kalite_kodu.upper()))
        if grade is None:
            raise NotFoundError(f"'{data.manuel_kalite_kodu}' kodlu kalite sınıfı bulunamadı.")
        override_id = grade.id

    # Tek zaman damgası: fiyat bu ana göre kilitlenir.
    now = rules.utcnow()
    ticket = rules.create_weigh_ticket(
        db, producer_id=producer.id, vehicle_plate=data.plaka, gross_kg=data.brut_kg,
        weighed_by_id=user_id, weighed_at=now, driver_name=data.sofor_adi,
    )
    rules.record_tare(db, ticket, data.dara_kg, at=now)
    k = data.kalite
    rules.record_quality_inspection(
        db, ticket, sample_weight_g=k.numune_gram, split=k.catlak_orani, yield_=k.randiman,
        empty=k.bos_orani, rotten=k.curuk_orani, moisture=k.nem_orani, inspected_by_id=user_id, at=now,
        override_grade_id=override_id, override_reason=data.manuel_kalite_gerekcesi,
    )
    rules.create_settlement(db, ticket, user_id, at=now)

    lot = db.scalar(select(Lot).where(Lot.ticket_id == ticket.id))
    rules.receive_lot_into_silo(db, lot, silo or _choose_silo(db, lot), user_id, at=now)
    db.commit()
    db.refresh(ticket)
    return ticket


def preview_delivery(db: Session, data: OnizlemeGirisi) -> TartimOnizleme:
    """Hiçbir şey kaydetmeden, kaydedilseydi oluşacak sınıf/fiyat/hakedişi aynı kurallarla hesaplar."""
    net_kg = data.brut_kg - data.dara_kg
    uyarilar: list[str] = []
    if data.manuel_kalite_kodu:
        grade = db.scalar(select(QualityGrade).where(QualityGrade.code == data.manuel_kalite_kodu.upper()))
        if grade is None:
            raise NotFoundError(f"'{data.manuel_kalite_kodu}' kodlu kalite sınıfı bulunamadı.")
    else:
        k = data.kalite
        grade = rules.determine_grade(db, split=k.catlak_orani, yield_=k.randiman, empty=k.bos_orani,
                                      rotten=k.curuk_orani, moisture=k.nem_orani)

    open_advance = Decimal("0")
    if data.uretici_id is not None:
        open_advance = Decimal(str(db.scalar(
            select(func.coalesce(func.sum(Advance.amount - Advance.offset_amount), 0))
            .where(Advance.producer_id == data.uretici_id)
        )))

    price = rules.find_effective_price(db, grade.id, rules.utcnow())
    if price is None:
        uyarilar.append(f"{grade.code} sınıfı için şu an onaylı fiyat yok; tartım kaydedilemez.")
        return TartimOnizleme(
            net_kg=net_kg, kalite_kodu=grade.code, kalite_adi=grade.name, fiyat_id=None, birim_fiyat=None,
            brut_tutar=None, kesintiler=[], kesinti_toplami=Decimal("0"), acik_avans=open_advance,
            avans_mahsubu=Decimal("0"), tahmini_net_odenecek=None, uyarilar=uyarilar,
        )

    gross = rules.money(net_kg * price.price_per_kg)
    kesintiler = []
    for dt in db.scalars(select(DeductionType).where(DeductionType.is_active.is_(True))
                         .order_by(DeductionType.sort_order)):
        _, amount = rules.deduction_amount(dt, gross, net_kg)
        kesintiler.append(KesintiOnizleme(kod=dt.code, ad=dt.name, tutar=amount))
    total_ded = sum((k.tutar for k in kesintiler), Decimal("0"))
    offset = min(open_advance, max(gross - total_ded, Decimal("0")))
    if offset > 0:
        uyarilar.append(f"Üreticinin {tl(open_advance)} açık avansı var; {tl(offset)} bu teslimattan mahsup edilecek.")
    return TartimOnizleme(
        net_kg=net_kg, kalite_kodu=grade.code, kalite_adi=grade.name, fiyat_id=price.id,
        birim_fiyat=price.price_per_kg, brut_tutar=gross, kesintiler=kesintiler, kesinti_toplami=total_ded,
        acik_avans=open_advance, avans_mahsubu=offset, tahmini_net_odenecek=gross - total_ded - offset,
        uyarilar=uyarilar,
    )


def list_deliveries(db: Session, tarih: date, uretici_id: int | None) -> list[TartimOzeti]:
    start, end = day_bounds_utc(tarih)
    stmt = (
        select(WeighTicket)
        .where(WeighTicket.gross_weighed_at >= start, WeighTicket.gross_weighed_at < end)
        .options(selectinload(WeighTicket.producer), selectinload(WeighTicket.lot),
                 selectinload(WeighTicket.settlement),
                 selectinload(WeighTicket.inspection).selectinload(QualityInspection.quality_grade))
        .order_by(WeighTicket.gross_weighed_at.desc(), WeighTicket.id.desc())
    )
    if uretici_id is not None:
        stmt = stmt.where(WeighTicket.producer_id == uretici_id)
    rows = []
    for t in db.scalars(stmt):
        s = t.settlement
        rows.append(TartimOzeti(
            id=t.id, fis_no=t.ticket_no, lot_no=t.lot.lot_no if t.lot else None, tartim_zamani=t.gross_weighed_at,
            uretici_id=t.producer_id, uretici_adi=t.producer.full_name, plaka=t.vehicle_plate, net_kg=t.net_kg,
            kalite_kodu=t.inspection.quality_grade.code if t.inspection else None, birim_fiyat=t.unit_price,
            hakedis_id=s.id if s else None, net_odenecek=s.net_payable if s else None,
            kalan=(s.net_payable - s.paid_amount) if s else None, hakedis_durumu=s.status.value if s else None,
        ))
    return rows


def build_tartim_yaniti(db: Session, t: WeighTicket) -> TartimYaniti:
    q = t.inspection
    p = t.daily_price
    stock_rows = []
    if t.lot is not None:
        stock_rows = db.execute(
            select(Silo.id, Silo.code, func.sum(StockMovement.quantity_kg))
            .join(StockMovement, StockMovement.silo_id == Silo.id)
            .where(StockMovement.lot_id == t.lot.id)
            .group_by(Silo.id, Silo.code)
            .order_by(Silo.code)
        ).all()
    return TartimYaniti(
        id=t.id, fis_no=t.ticket_no, lot_no=t.lot.lot_no if t.lot else None, durum=t.status,
        uretici_id=t.producer_id, uretici_adi=t.producer.full_name, plaka=t.vehicle_plate, sofor_adi=t.driver_name,
        brut_kg=t.gross_kg, dara_kg=t.tare_kg, net_kg=t.net_kg, tartim_zamani=t.gross_weighed_at,
        kalite=KaliteSonucu(
            sinif_kodu=q.quality_grade.code, sinif_adi=q.quality_grade.name, catlak_orani=q.split_ratio,
            randiman=q.yield_ratio, bos_orani=q.empty_ratio, curuk_orani=q.rotten_ratio,
            nem_orani=q.moisture_ratio, manuel_atama=q.is_manual_override,
        ) if q else None,
        fiyat=FiyatKilidi(
            fiyat_id=p.id, birim_fiyat=t.unit_price, fiyat_gecerlilik_baslangici=p.valid_from,
            fiyat_onay_zamani=p.approved_at, kilitlenme_zamani=t.priced_at,
        ) if p else None,
        hakedis=build_hakedis_yaniti(db, t.settlement) if t.settlement else None,
        stok=[StokKaydi(silo_id=sid, silo_kodu=code, miktar_kg=Decimal(str(qty)))
              for sid, code, qty in stock_rows if qty],
        uretici_guncel_bakiye=rules.producer_balance(db, t.producer_id),
    )
