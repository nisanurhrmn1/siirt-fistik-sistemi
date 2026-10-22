from collections import defaultdict
from datetime import date
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.core.errors import NotFoundError
from app.core.timeutil import day_bounds_utc
from app.db.enums import TicketStatus
from app.db.models import (
    Advance, Lot, Payment, Producer, ProducerLedgerEntry, QualityGrade, QualityInspection, Settlement, Silo,
    StockMovement, WeighTicket,
)
from app.domain import rules
from app.schemas.rapor import (
    GunlukOzet, KaliteKirilimi, KaliteStok, KaliteStokPayi, SiloStok, StokDurumu, UreticiBakiye,
)

ZERO = Decimal("0")
CENT = Decimal("0.01")


def _q(x: Decimal) -> Decimal:
    return x.quantize(CENT, rounding=ROUND_HALF_UP)


def _weighted(numerator: Decimal, weight: Decimal) -> Decimal | None:
    return _q(numerator / weight) if weight else None


# ─── Stok ───

def stock_status(db: Session, gruplama: str, silo_id: int | None, kalite_kodu: str | None) -> StokDurumu:
    grades = {g.id: g for g in db.scalars(select(QualityGrade))}
    silos = {s.id: s for s in db.scalars(select(Silo).order_by(Silo.code))}
    if silo_id is not None and silo_id not in silos:
        raise NotFoundError(f"Silo bulunamadı (id={silo_id}).")
    grade_filter = None
    if kalite_kodu:
        grade_filter = next((g.id for g in grades.values() if g.code == kalite_kodu.upper()), None)
        if grade_filter is None:
            raise NotFoundError(f"'{kalite_kodu}' kodlu kalite sınıfı bulunamadı.")

    # (silo, lot) bazında net stok
    rows = db.execute(
        select(StockMovement.silo_id, StockMovement.lot_id, Lot.quality_grade_id, func.sum(StockMovement.quantity_kg))
        .join(Lot, Lot.id == StockMovement.lot_id)
        .group_by(StockMovement.silo_id, StockMovement.lot_id, Lot.quality_grade_id)
    ).all()
    positions = [
        (sid, lid, gid, _q(Decimal(str(qty))))
        for sid, lid, gid, qty in rows
        if (silo_id is None or sid == silo_id) and (grade_filter is None or gid == grade_filter)
    ]
    positions = [p for p in positions if p[3] != 0]
    toplam = sum((p[3] for p in positions), ZERO)
    now = rules.utcnow()

    if gruplama == "kalite":
        by_grade: dict[int, list] = defaultdict(list)
        for p in positions:
            by_grade[p[2]].append(p)
        kaliteler = [
            KaliteStok(
                kalite_kodu=grades[gid].code, kalite_adi=grades[gid].name,
                stok_kg=sum((p[3] for p in ps), ZERO), lot_sayisi=len({p[1] for p in ps}),
                silolar=sorted({silos[p[0]].code for p in ps}),
            )
            for gid, ps in sorted(by_grade.items(), key=lambda kv: grades[kv[0]].priority)
        ]
        return StokDurumu(gruplama="kalite", sorgu_zamani=now, toplam_stok_kg=toplam, kaliteler=kaliteler)

    by_silo: dict[int, list] = defaultdict(list)
    for p in positions:
        by_silo[p[0]].append(p)
    selected = [silos[silo_id]] if silo_id is not None else [s for s in silos.values() if s.is_active or by_silo[s.id]]
    result = []
    for s in selected:
        ps = by_silo.get(s.id, [])
        stok = sum((p[3] for p in ps), ZERO)
        per_grade: dict[int, list] = defaultdict(list)
        for p in ps:
            per_grade[p[2]].append(p)
        result.append(SiloStok(
            silo_id=s.id, silo_kodu=s.code, silo_adi=s.name,
            adanmis_kalite=grades[s.dedicated_grade_id].code if s.dedicated_grade_id else None,
            kapasite_kg=s.capacity_kg, stok_kg=stok, bos_kapasite_kg=s.capacity_kg - stok,
            doluluk_orani=_q(stok * 100 / s.capacity_kg),
            kaliteler=[KaliteStokPayi(kalite_kodu=grades[gid].code, stok_kg=sum((p[3] for p in gps), ZERO),
                                      lot_sayisi=len(gps)) for gid, gps in per_grade.items()],
        ))
    return StokDurumu(gruplama="silo", sorgu_zamani=now, toplam_stok_kg=toplam, silolar=result)


# ─── Üretici bakiyeleri ───

def producer_balances(db: Session) -> list[UreticiBakiye]:
    def grouped(stmt) -> dict[int, Decimal]:
        return {pid: Decimal(str(v or 0)) for pid, v in db.execute(stmt).all()}

    balance = grouped(select(ProducerLedgerEntry.producer_id, func.sum(ProducerLedgerEntry.amount))
                      .group_by(ProducerLedgerEntry.producer_id))
    open_adv = grouped(select(Advance.producer_id, func.sum(Advance.amount - Advance.offset_amount))
                       .group_by(Advance.producer_id))
    unpaid = grouped(select(Settlement.producer_id, func.sum(Settlement.net_payable - Settlement.paid_amount))
                     .group_by(Settlement.producer_id))
    kgs = grouped(select(Settlement.producer_id, func.sum(Settlement.net_kg)).group_by(Settlement.producer_id))

    result = []
    for p in db.scalars(select(Producer).order_by(Producer.full_name, Producer.id)):
        b, u = _q(balance.get(p.id, ZERO)), _q(unpaid.get(p.id, ZERO))
        result.append(UreticiBakiye(
            uretici_id=p.id, ad_soyad=p.full_name, bakiye=b, acik_avans=_q(open_adv.get(p.id, ZERO)),
            odenmemis_hakedis=u, odenebilir=max(min(u, b), ZERO), toplam_teslimat_kg=_q(kgs.get(p.id, ZERO)),
        ))
    return result


# ─── Günlük özet ───

def daily_summary(db: Session, tarih: date) -> GunlukOzet:
    start, end = day_bounds_utc(tarih)
    tickets = db.scalars(
        select(WeighTicket)
        .where(WeighTicket.gross_weighed_at >= start, WeighTicket.gross_weighed_at < end,
               WeighTicket.status.in_([TicketStatus.GRADED, TicketStatus.SETTLED]))
        .options(selectinload(WeighTicket.inspection).selectinload(QualityInspection.quality_grade),
                 selectinload(WeighTicket.settlement))
    ).all()
    payments = db.scalars(select(Payment).where(Payment.paid_at >= start, Payment.paid_at < end)).all()
    advances = db.scalars(select(Advance).where(Advance.given_at >= start, Advance.given_at < end)).all()

    net_kg = sum((t.net_kg for t in tickets), ZERO)
    settlements = [t.settlement for t in tickets if t.settlement]

    groups: dict[str, list[WeighTicket]] = defaultdict(list)
    for t in tickets:
        groups[t.inspection.quality_grade.code].append(t)
    kirilim = []
    for code, ts in sorted(groups.items(), key=lambda kv: kv[1][0].inspection.quality_grade.priority):
        kg = sum((t.net_kg for t in ts), ZERO)
        kirilim.append(KaliteKirilimi(
            kalite_kodu=code, teslimat_sayisi=len(ts), net_kg=kg,
            brut_tutar=sum((t.settlement.gross_amount for t in ts if t.settlement), ZERO),
            ortalama_birim_fiyat=_weighted(sum((t.net_kg * t.unit_price for t in ts), ZERO), kg),
            ortalama_randiman=_weighted(sum((t.net_kg * t.inspection.yield_ratio for t in ts), ZERO), kg),
        ))

    return GunlukOzet(
        tarih=tarih,
        teslimat_sayisi=len(tickets),
        uretici_sayisi=len({t.producer_id for t in tickets}),
        toplam_net_kg=net_kg,
        ortalama_randiman=_weighted(sum((t.net_kg * t.inspection.yield_ratio for t in tickets), ZERO), net_kg),
        ortalama_birim_fiyat=_weighted(sum((t.net_kg * t.unit_price for t in tickets), ZERO), net_kg),
        brut_alim_tutari=sum((s.gross_amount for s in settlements), ZERO),
        toplam_kesinti=sum((s.deductions_total for s in settlements), ZERO),
        toplam_avans_mahsubu=sum((s.advance_offset_total for s in settlements), ZERO),
        net_hakedis_toplami=sum((s.net_payable for s in settlements), ZERO),
        odenen_toplam=sum((p.amount for p in payments), ZERO),
        odeme_sayisi=len(payments),
        verilen_avans_toplami=sum((a.amount for a in advances), ZERO),
        kalite_kirilimi=kirilim,
    )
