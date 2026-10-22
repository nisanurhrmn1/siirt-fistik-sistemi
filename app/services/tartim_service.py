"""Kantar → kalite → fiyat kilidi → hakediş → stok: tek transaction'da teslimat kaydı."""
from decimal import Decimal

from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.core.errors import BusinessRuleError, NotFoundError
from app.db.models import Lot, Producer, QualityGrade, Silo, StockMovement, WeighTicket
from app.domain import rules
from app.schemas.tartim import FiyatKilidi, KaliteSonucu, StokKaydi, TartimGirisi, TartimYaniti
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
