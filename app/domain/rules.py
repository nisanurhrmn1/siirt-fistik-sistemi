"""
Temel iş kuralları — route/UI'dan bağımsız servis fonksiyonları.

Her fonksiyon bir `Session` alır, değişiklikleri `flush` eder ama `commit` etmez; transaction sınırı
çağıranındır (ileride FastAPI dependency'si). Para hesapları Decimal + ROUND_HALF_UP ile yapılır.

Eşzamanlılık: hakediş ve ödeme, üretici satırını `SELECT ... FOR UPDATE` ile kilitler; aynı üreticiye
paralel iki işlem avansı iki kez mahsup edemez veya bakiyeyi aşan ödeme yapamaz (PostgreSQL'de).
"""
from __future__ import annotations

import re
import uuid
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.db.enums import (
    AdvanceStatus,
    DeductionCalcMethod,
    LedgerEntryType,
    PaymentMethod,
    PriceStatus,
    SettlementStatus,
    StockMovementType,
    TicketStatus,
)
from app.db.models import (
    Advance,
    AdvanceOffset,
    DailyPrice,
    DeductionType,
    DocumentSequence,
    Lot,
    Payment,
    PaymentAllocation,
    Producer,
    ProducerBankAccount,
    ProducerLedgerEntry,
    QualityGrade,
    QualityInspection,
    Settlement,
    SettlementDeduction,
    Silo,
    StockMovement,
    WeighTicket,
)

ZERO = Decimal("0")
CENT = Decimal("0.01")


class BusinessRuleError(Exception):
    """İş kuralı ihlali (API katmanında 409/422'ye çevrilecek)."""


def money(value) -> Decimal:
    return Decimal(value).quantize(CENT, rounding=ROUND_HALF_UP)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


# ───────────────────────────── Doğrulayıcılar ─────────────────────────────

def validate_tc_kimlik(no: str) -> bool:
    if len(no) != 11 or not no.isdigit() or no[0] == "0":
        return False
    d = [int(c) for c in no]
    if (sum(d[0:9:2]) * 7 - sum(d[1:8:2])) % 10 != d[9]:
        return False
    return sum(d[:10]) % 10 == d[10]


def validate_tax_number(no: str) -> bool:
    return len(no) == 10 and no.isdigit()


def normalize_iban(iban: str) -> str:
    return iban.replace(" ", "").upper()


def validate_iban(iban: str) -> bool:
    iban = normalize_iban(iban)
    if not re.fullmatch(r"TR\d{24}", iban):
        return False
    rearranged = iban[4:] + iban[:4]
    return int("".join(str(int(ch, 36)) for ch in rearranged)) % 97 == 1


_PLATE_RE = re.compile(r"(0[1-9]|[1-7]\d|8[01])[A-Z]{1,3}\d{2,5}")


def normalize_plate(plate: str) -> str:
    p = re.sub(r"[\s-]", "", plate).upper()
    if not _PLATE_RE.fullmatch(p):
        raise BusinessRuleError(f"Geçersiz plaka: {plate}")
    return p


def normalize_phone(phone: str) -> str:
    """'+90 532 123 45 67', '0532-123-4567', '5321234567' → '05321234567' (sabit hat: 0484...)."""
    d = re.sub(r"[\s()\-.]", "", phone)
    if d.startswith("+90"):
        d = "0" + d[3:]
    elif d.startswith("90") and len(d) == 12:
        d = "0" + d[2:]
    elif len(d) == 10:
        d = "0" + d
    if not re.fullmatch(r"0[2-5]\d{9}", d):
        raise BusinessRuleError(f"Geçersiz telefon numarası: {phone} (örnek: 0532 123 45 67)")
    return d


# ───────────────────────────── Belge numarası ─────────────────────────────

def next_document_no(session: Session, doc_type: str, at: datetime) -> str:
    """KF-2026-000001 biçiminde yıl bazlı, kilitli sayaçtan benzersiz numara."""
    seq = session.get(DocumentSequence, (doc_type, at.year), with_for_update=True)
    if seq is None:
        seq = DocumentSequence(doc_type=doc_type, year=at.year, last_value=0)
        session.add(seq)
    seq.last_value += 1
    session.flush()
    return f"{doc_type}-{at.year}-{seq.last_value:06d}"


def _lock_producer(session: Session, producer_id: int) -> Producer:
    producer = session.execute(
        select(Producer).where(Producer.id == producer_id).with_for_update()
    ).scalar_one_or_none()
    if producer is None:
        raise BusinessRuleError("Üretici bulunamadı.")
    return producer


lock_producer = _lock_producer


def _ledger(session: Session, producer_id: int, entry_type: LedgerEntryType, amount: Decimal,
            at: datetime, user_id: int, description: str, **refs) -> ProducerLedgerEntry:
    entry = ProducerLedgerEntry(
        producer_id=producer_id, entry_type=entry_type, amount=money(amount), entry_at=at,
        created_by_id=user_id, description=description, **refs,
    )
    session.add(entry)
    return entry


# ───────────────────────────── Fiyat ─────────────────────────────

def approve_price(session: Session, price: DailyPrice, approver_id: int, at: datetime | None = None) -> DailyPrice:
    """Taslak fiyatı onaylar. Aynı sınıfın açık uçlu önceki fiyatı, yeni fiyatın başlangıcında kapatılır.
    Geçmişe dönük (onay anından önce başlayan) fiyat onaylanamaz → geçmiş tartımların fiyatı değişmez."""
    at = at or utcnow()
    if price.status != PriceStatus.DRAFT:
        raise BusinessRuleError("Yalnızca taslak fiyat onaylanabilir.")
    if price.valid_from < at:
        raise BusinessRuleError("Fiyat geçerlilik başlangıcı onay anından önce olamaz.")
    later = session.execute(
        select(DailyPrice.id).where(
            DailyPrice.quality_grade_id == price.quality_grade_id,
            DailyPrice.status == PriceStatus.APPROVED,
            DailyPrice.valid_from >= price.valid_from,
        )
    ).first()
    if later is not None:
        raise BusinessRuleError("Bu kalite sınıfı için aynı veya daha ileri tarihli onaylı bir fiyat zaten var.")

    previous = session.execute(
        select(DailyPrice).where(
            DailyPrice.quality_grade_id == price.quality_grade_id,
            DailyPrice.status == PriceStatus.APPROVED,
            DailyPrice.valid_from < price.valid_from,
            or_(DailyPrice.valid_to.is_(None), DailyPrice.valid_to > price.valid_from),
        ).with_for_update()
    ).scalars().all()
    for prev in previous:
        prev.valid_to = price.valid_from  # [from, to) — yeni fiyatın başladığı an eski fiyat biter
    session.flush()  # önce eski fiyatı kapat; yoksa iki onaylı fiyat bir an çakışır (EXCLUDE kısıtı)

    price.status = PriceStatus.APPROVED
    price.approved_by_id = approver_id
    price.approved_at = at
    session.flush()
    return price


def revoke_price(session: Session, price: DailyPrice, at: datetime | None = None) -> None:
    """Hatalı fiyatı iptal eder. Bu andan önce dondurulmuş snapshot'lar etkilenmez."""
    if price.status != PriceStatus.APPROVED:
        raise BusinessRuleError("Yalnızca onaylı fiyat iptal edilebilir.")
    price.status = PriceStatus.REVOKED
    price.revoked_at = at or utcnow()
    session.flush()


def find_effective_price(session: Session, grade_id: int, at: datetime) -> DailyPrice | None:
    """`at` anında GEÇERLİ ve O ANDA ONAYLI olan fiyat.

    * valid_from <= at < valid_to
    * approved_at <= at          → sonradan onaylanan fiyat geçmiş tartıma uygulanmaz
    * revoked_at IS NULL OR > at → sonradan iptal edilen fiyat, iptalden önceki tartımlar için geçerli kalır
    """
    return session.execute(
        select(DailyPrice)
        .where(
            DailyPrice.quality_grade_id == grade_id,
            DailyPrice.status.in_([PriceStatus.APPROVED, PriceStatus.REVOKED]),
            DailyPrice.valid_from <= at,
            or_(DailyPrice.valid_to.is_(None), DailyPrice.valid_to > at),
            DailyPrice.approved_at <= at,
            or_(DailyPrice.revoked_at.is_(None), DailyPrice.revoked_at > at),
        )
        .order_by(DailyPrice.valid_from.desc(), DailyPrice.id.desc())
        .limit(1)
    ).scalar_one_or_none()


# ───────────────────────────── Kantar ─────────────────────────────

def create_weigh_ticket(session: Session, *, producer_id: int, vehicle_plate: str, gross_kg: Decimal,
                        weighed_by_id: int, weighed_at: datetime | None = None,
                        driver_name: str | None = None) -> WeighTicket:
    """Dolu (giriş) tartım. Aynı üretici aynı gün birden çok kez gelebilir; her seferinde yeni fiş."""
    weighed_at = weighed_at or utcnow()
    producer = session.get(Producer, producer_id)
    if producer is None or not producer.is_active:
        raise BusinessRuleError("Üretici bulunamadı veya pasif.")
    if Decimal(gross_kg) <= 0:
        raise BusinessRuleError("Brüt ağırlık pozitif olmalı.")
    ticket = WeighTicket(
        ticket_no=next_document_no(session, "KF", weighed_at),
        producer_id=producer_id,
        vehicle_plate=normalize_plate(vehicle_plate),
        driver_name=driver_name,
        gross_kg=Decimal(gross_kg),
        gross_weighed_at=weighed_at,
        weighed_by_id=weighed_by_id,
        status=TicketStatus.GROSS_WEIGHED,
    )
    session.add(ticket)
    session.flush()
    return ticket


def record_tare(session: Session, ticket: WeighTicket, tare_kg: Decimal, at: datetime | None = None) -> WeighTicket:
    """Boş (dara) tartım → net kg = brüt − dara (DB'de hesaplanan sütun)."""
    if ticket.status != TicketStatus.GROSS_WEIGHED:
        raise BusinessRuleError("Dara yalnızca brüt tartımı yapılmış fişe girilebilir.")
    tare_kg = Decimal(tare_kg)
    if not (ZERO <= tare_kg < ticket.gross_kg):
        raise BusinessRuleError("Dara 0 ile brüt ağırlık arasında olmalı.")
    ticket.tare_kg = tare_kg
    ticket.tare_weighed_at = at or utcnow()
    ticket.status = TicketStatus.NET_WEIGHED
    session.flush()
    return ticket


def cancel_ticket(session: Session, ticket: WeighTicket, reason: str) -> None:
    if ticket.status == TicketStatus.SETTLED:
        raise BusinessRuleError("Hakedişi oluşmuş fiş iptal edilemez; ters kayıt yapın.")
    ticket.status = TicketStatus.CANCELLED
    ticket.cancel_reason = reason
    session.flush()


def ticket_net_kg(ticket: WeighTicket) -> Decimal:
    return ticket.gross_kg - ticket.tare_kg


# ───────────────────────────── Kalite & fiyat snapshot ─────────────────────────────

def determine_grade(session: Session, *, split: Decimal, yield_: Decimal, empty: Decimal,
                    rotten: Decimal, moisture: Decimal) -> QualityGrade:
    grades = session.execute(
        select(QualityGrade).where(QualityGrade.is_active.is_(True)).order_by(QualityGrade.priority)
    ).scalars()
    defect = empty + rotten
    for g in grades:
        if g.min_split_ratio is not None and split < g.min_split_ratio:
            continue
        if g.min_yield_ratio is not None and yield_ < g.min_yield_ratio:
            continue
        if g.max_defect_ratio is not None and defect > g.max_defect_ratio:
            continue
        if g.max_moisture_ratio is not None and moisture > g.max_moisture_ratio:
            continue
        return g
    raise BusinessRuleError("Hiçbir kalite sınıfına uymuyor; eşiksiz bir son sınıf tanımlayın.")


def record_quality_inspection(session: Session, ticket: WeighTicket, *, sample_weight_g: Decimal,
                              split: Decimal, yield_: Decimal, empty: Decimal, rotten: Decimal,
                              moisture: Decimal, inspected_by_id: int, at: datetime | None = None,
                              override_grade_id: int | None = None,
                              override_reason: str | None = None) -> QualityInspection:
    """Kalite analizini kaydeder, sınıfı belirler, TARTIM ANINDAKİ fiyatı dondurur ve lotu açar."""
    at = at or utcnow()
    if ticket.status != TicketStatus.NET_WEIGHED:
        raise BusinessRuleError("Kalite analizi için önce dara tartımı tamamlanmalı.")

    if override_grade_id is not None:
        if not override_reason:
            raise BusinessRuleError("Manuel sınıf ataması gerekçe gerektirir.")
        grade = session.get(QualityGrade, override_grade_id)
    else:
        grade = determine_grade(session, split=split, yield_=yield_, empty=empty, rotten=rotten, moisture=moisture)

    # Referans an: tartım anı (gross_weighed_at). Analiz saatler sonra yapılsa da fiyat tartım anına aittir.
    price = find_effective_price(session, grade.id, ticket.gross_weighed_at)
    if price is None:
        raise BusinessRuleError(
            f"{grade.code} sınıfı için tartım anında ({ticket.gross_weighed_at:%d.%m.%Y %H:%M}) onaylı fiyat yok."
        )

    inspection = QualityInspection(
        ticket_id=ticket.id, sample_weight_g=sample_weight_g, split_ratio=split, yield_ratio=yield_,
        empty_ratio=empty, rotten_ratio=rotten, moisture_ratio=moisture, quality_grade_id=grade.id,
        is_manual_override=override_grade_id is not None, override_reason=override_reason,
        inspected_by_id=inspected_by_id, inspected_at=at,
    )
    session.add(inspection)

    ticket.daily_price_id = price.id      # ← SNAPSHOT
    ticket.unit_price = price.price_per_kg
    ticket.priced_at = at
    ticket.status = TicketStatus.GRADED

    session.add(Lot(
        lot_no=next_document_no(session, "LOT", ticket.gross_weighed_at),
        ticket_id=ticket.id, quality_grade_id=grade.id, initial_kg=ticket_net_kg(ticket),
    ))
    session.flush()
    return inspection


# ───────────────────────────── Avans ─────────────────────────────

def give_advance(session: Session, *, producer_id: int, amount: Decimal, method: PaymentMethod,
                 created_by_id: int, given_at: datetime | None = None,
                 bank_account_id: int | None = None, description: str | None = None) -> Advance:
    given_at = given_at or utcnow()
    _lock_producer(session, producer_id)
    amount = money(amount)
    if amount <= 0:
        raise BusinessRuleError("Avans tutarı pozitif olmalı.")
    if method == PaymentMethod.BANK_TRANSFER:
        bank_account_id = _resolve_bank_account(session, producer_id, bank_account_id)
    adv = Advance(
        advance_no=next_document_no(session, "AV", given_at), producer_id=producer_id, amount=amount,
        offset_amount=ZERO, status=AdvanceStatus.OPEN, given_at=given_at, method=method,
        bank_account_id=bank_account_id, description=description, created_by_id=created_by_id,
    )
    session.add(adv)
    session.flush()
    # Avans verildiği anda üretici borçlanır → bakiye eksiye düşebilir.
    _ledger(session, producer_id, LedgerEntryType.ADVANCE, -amount, given_at, created_by_id,
            f"Avans {adv.advance_no}", advance_id=adv.id)
    session.flush()
    return adv


def _refresh_advance_status(adv: Advance) -> None:
    if adv.offset_amount == 0:
        adv.status = AdvanceStatus.OPEN
    elif adv.offset_amount < adv.amount:
        adv.status = AdvanceStatus.PARTIALLY_OFFSET
    else:
        adv.status = AdvanceStatus.CLOSED


# ───────────────────────────── Hakediş ─────────────────────────────

def _deduction_amount(dt: DeductionType, gross: Decimal, net_kg: Decimal) -> tuple[Decimal, Decimal]:
    """(base_amount, amount)"""
    if dt.calc_method == DeductionCalcMethod.PERCENT:
        return gross, money(gross * dt.rate / 100)
    if dt.calc_method == DeductionCalcMethod.PER_KG:
        return net_kg, money(net_kg * dt.rate)
    return gross, money(dt.rate)


deduction_amount = _deduction_amount


def _plan_advance_offsets(session: Session, producer_id: int, limit: Decimal) -> list[tuple[Advance, Decimal]]:
    """Açık avanslardan en eskiden başlayarak en fazla `limit` kadar mahsup planı."""
    open_advances = session.execute(
        select(Advance)
        .where(Advance.producer_id == producer_id, Advance.offset_amount < Advance.amount)
        .order_by(Advance.given_at, Advance.id)
        .with_for_update()
    ).scalars().all()
    plan: list[tuple[Advance, Decimal]] = []
    remaining = limit
    for adv in open_advances:
        if remaining <= 0:
            break
        take = min(adv.amount - adv.offset_amount, remaining)
        plan.append((adv, take))
        remaining -= take
    return plan


def _apply_advance_offsets(session: Session, settlement: Settlement, plan: list[tuple[Advance, Decimal]]) -> None:
    for adv, take in plan:
        existing = session.execute(
            select(AdvanceOffset).where(AdvanceOffset.advance_id == adv.id,
                                        AdvanceOffset.settlement_id == settlement.id)
        ).scalar_one_or_none()
        if existing is None:
            session.add(AdvanceOffset(advance_id=adv.id, settlement_id=settlement.id, amount=take))
        else:
            existing.amount += take
        adv.offset_amount += take
        _refresh_advance_status(adv)


def offset_advances_into_settlement(session: Session, settlement: Settlement) -> Decimal:
    """Hakediş oluştuktan SONRA verilmiş avansları, hakedişin ödenmemiş kısmından mahsup eder.
    Brüt ve kesintiler değişmez; yalnızca advance_offset_total artar, net_payable aynı miktarda azalır."""
    unpaid = settlement.net_payable - settlement.paid_amount
    if unpaid <= 0:
        return ZERO
    plan = _plan_advance_offsets(session, settlement.producer_id, unpaid)
    total = sum((take for _, take in plan), ZERO)
    if total > 0:
        _apply_advance_offsets(session, settlement, plan)
        settlement.advance_offset_total += total
        settlement.net_payable -= total
        _refresh_settlement_status(settlement)
        session.flush()
    return total


def create_settlement(session: Session, ticket: WeighTicket, created_by_id: int,
                      at: datetime | None = None) -> Settlement:
    """Net hakediş = (Net kg × Dondurulmuş fiyat) − Kesintiler − Avans mahsubu.

    Avans mahsubu FIFO (en eski avans önce) ve kesinti sonrası tutarla sınırlıdır. Kapanmayan avans
    açık kalır; üretici bakiyesi eksidedir ve sonraki hakedişlerde otomatik mahsup edilir.
    """
    at = at or utcnow()
    if ticket.status != TicketStatus.GRADED:
        raise BusinessRuleError("Hakediş için teslimatın kalite/fiyat aşaması tamamlanmalı.")
    _lock_producer(session, ticket.producer_id)

    net_kg = ticket_net_kg(ticket)
    gross = money(net_kg * ticket.unit_price)

    # 1) Kesintiler (oran snapshot'ı ile)
    deduction_rows: list[SettlementDeduction] = []
    for dt in session.execute(
        select(DeductionType).where(DeductionType.is_active.is_(True)).order_by(DeductionType.sort_order)
    ).scalars():
        base, amount = _deduction_amount(dt, gross, net_kg)
        deduction_rows.append(SettlementDeduction(
            deduction_type_id=dt.id, calc_method=dt.calc_method, rate=dt.rate, base_amount=base, amount=amount,
        ))
    deductions_total = sum((d.amount for d in deduction_rows), ZERO)
    if deductions_total > gross:
        raise BusinessRuleError("Kesintiler toplamı brüt tutarı aşıyor; kesinti tanımlarını kontrol edin.")

    # 2) Açık avansların FIFO mahsubu
    available = gross - deductions_total
    offsets = _plan_advance_offsets(session, ticket.producer_id, available)
    offset_total = sum((take for _, take in offsets), ZERO)
    net_payable = available - offset_total

    settlement = Settlement(
        settlement_no=next_document_no(session, "HK", at), ticket_id=ticket.id,
        producer_id=ticket.producer_id, net_kg=net_kg, unit_price=ticket.unit_price,
        gross_amount=gross, deductions_total=deductions_total, advance_offset_total=offset_total,
        net_payable=net_payable, paid_amount=ZERO,
        status=SettlementStatus.OFFSET_ONLY if net_payable == 0 else SettlementStatus.UNPAID,
        created_by_id=created_by_id,
    )
    settlement.deductions = deduction_rows
    session.add(settlement)
    session.flush()

    _apply_advance_offsets(session, settlement, offsets)

    # 3) Cari hesap: + brüt, − her kesinti. (Avans, verildiği anda zaten borç yazıldı; tekrar yazılmaz.)
    _ledger(session, ticket.producer_id, LedgerEntryType.DELIVERY, gross, at, created_by_id,
            f"Teslimat {ticket.ticket_no}", ticket_id=ticket.id, settlement_id=settlement.id)
    for d in deduction_rows:
        if d.amount > 0:
            _ledger(session, ticket.producer_id, LedgerEntryType.DEDUCTION, -d.amount, at, created_by_id,
                    f"Kesinti {ticket.ticket_no}", settlement_id=settlement.id, settlement_deduction_id=d.id)

    ticket.status = TicketStatus.SETTLED
    session.flush()
    return settlement


# ───────────────────────────── Ödeme ─────────────────────────────

def producer_balance(session: Session, producer_id: int) -> Decimal:
    """Cari bakiye: (+) şirket üreticiye borçlu, (−) üretici şirkete borçlu (kapanmamış avans)."""
    total = session.execute(
        select(func.coalesce(func.sum(ProducerLedgerEntry.amount), 0))
        .where(ProducerLedgerEntry.producer_id == producer_id)
    ).scalar_one()
    return money(total)


def _resolve_bank_account(session: Session, producer_id: int, bank_account_id: int | None) -> int:
    stmt = select(ProducerBankAccount).where(
        ProducerBankAccount.producer_id == producer_id, ProducerBankAccount.is_active.is_(True)
    )
    stmt = stmt.where(ProducerBankAccount.id == bank_account_id) if bank_account_id else stmt.where(
        ProducerBankAccount.is_default.is_(True))
    acc = session.execute(stmt).scalar_one_or_none()
    if acc is None:
        raise BusinessRuleError("Üreticiye ait aktif banka hesabı bulunamadı.")
    return acc.id


def _refresh_settlement_status(s: Settlement) -> None:
    if s.net_payable == 0:
        s.status = SettlementStatus.OFFSET_ONLY
    elif s.paid_amount == 0:
        s.status = SettlementStatus.UNPAID
    elif s.paid_amount < s.net_payable:
        s.status = SettlementStatus.PARTIALLY_PAID
    else:
        s.status = SettlementStatus.PAID


def record_payment(session: Session, *, producer_id: int, amount: Decimal, method: PaymentMethod,
                   created_by_id: int, paid_at: datetime | None = None, bank_account_id: int | None = None,
                   reference_no: str | None = None, description: str | None = None,
                   settlement_id: int | None = None) -> Payment:
    """Ödemeyi en eski ödenmemiş hakedişten başlayarak dağıtır (parçalı/tam).
    `settlement_id` verilirse yalnızca o hakedişe uygulanır.
    Ödeme ne cari bakiyeyi ne de ödenmemiş hakediş toplamını aşabilir."""
    paid_at = paid_at or utcnow()
    _lock_producer(session, producer_id)
    amount = money(amount)
    if amount <= 0:
        raise BusinessRuleError("Ödeme tutarı pozitif olmalı.")

    stmt = select(Settlement).where(
        Settlement.producer_id == producer_id, Settlement.paid_amount < Settlement.net_payable
    )
    if settlement_id is not None:
        stmt = stmt.where(Settlement.id == settlement_id)
    unpaid = session.execute(
        stmt.order_by(Settlement.created_at, Settlement.id).with_for_update()
    ).scalars().all()
    unpaid_total = sum((s.net_payable - s.paid_amount for s in unpaid), ZERO)
    payable = min(unpaid_total, max(producer_balance(session, producer_id), ZERO))
    if amount > payable:
        raise BusinessRuleError(f"Ödenebilir tutar {payable} TL; {amount} TL ödenemez.")

    if method == PaymentMethod.BANK_TRANSFER:
        bank_account_id = _resolve_bank_account(session, producer_id, bank_account_id)

    payment = Payment(
        payment_no=next_document_no(session, "OD", paid_at), producer_id=producer_id,
        bank_account_id=bank_account_id, amount=amount, method=method, paid_at=paid_at,
        reference_no=reference_no, description=description, created_by_id=created_by_id,
    )
    session.add(payment)
    session.flush()

    left = amount
    for s in unpaid:
        if left <= 0:
            break
        take = min(s.net_payable - s.paid_amount, left)
        session.add(PaymentAllocation(payment_id=payment.id, settlement_id=s.id, amount=take))
        s.paid_amount += take
        _refresh_settlement_status(s)
        left -= take

    _ledger(session, producer_id, LedgerEntryType.PAYMENT, -amount, paid_at, created_by_id,
            f"Ödeme {payment.payment_no}", payment_id=payment.id)
    session.flush()
    return payment


# ───────────────────────────── Depo / Stok ─────────────────────────────

def silo_stock(session: Session, silo_id: int) -> Decimal:
    return Decimal(session.execute(
        select(func.coalesce(func.sum(StockMovement.quantity_kg), 0)).where(StockMovement.silo_id == silo_id)
    ).scalar_one())


def lot_stock_in_silo(session: Session, lot_id: int, silo_id: int) -> Decimal:
    return Decimal(session.execute(
        select(func.coalesce(func.sum(StockMovement.quantity_kg), 0))
        .where(StockMovement.lot_id == lot_id, StockMovement.silo_id == silo_id)
    ).scalar_one())


def check_silo_accepts(session: Session, silo: Silo, lot: Lot, qty: Decimal) -> None:
    """Kaliteye göre ayrıştırma + kapasite kontrolü."""
    if not silo.is_active:
        raise BusinessRuleError(f"{silo.code} pasif.")
    if silo.dedicated_grade_id is not None and silo.dedicated_grade_id != lot.quality_grade_id:
        raise BusinessRuleError(f"{silo.code} yalnızca kendi kalite sınıfını kabul eder.")
    # Sınıfa adanmamış silo da karışık kalite içeremez: içinde stok varsa aynı sınıf olmalı.
    other_grades = session.execute(
        select(Lot.quality_grade_id)
        .join(StockMovement, StockMovement.lot_id == Lot.id)
        .where(StockMovement.silo_id == silo.id, Lot.quality_grade_id != lot.quality_grade_id)
        .group_by(Lot.quality_grade_id)
        .having(func.sum(StockMovement.quantity_kg) > 0)
    ).first()
    if other_grades is not None:
        raise BusinessRuleError(f"{silo.code} içinde farklı kalite sınıfı stok var; karıştırılamaz.")
    if silo_stock(session, silo.id) + qty > silo.capacity_kg:
        raise BusinessRuleError(f"{silo.code} kapasitesi aşılıyor.")


def receive_lot_into_silo(session: Session, lot: Lot, silo: Silo, created_by_id: int,
                          quantity_kg: Decimal | None = None, at: datetime | None = None) -> StockMovement:
    """Lotun (tamamını veya bir kısmını) siloya ilk girişi. Lot birden çok siloya bölünebilir."""
    placed = Decimal(session.execute(
        select(func.coalesce(func.sum(StockMovement.quantity_kg), 0))
        .where(StockMovement.lot_id == lot.id, StockMovement.movement_type == StockMovementType.RECEIPT)
    ).scalar_one())
    qty = Decimal(quantity_kg) if quantity_kg is not None else lot.initial_kg - placed
    if qty <= 0 or placed + qty > lot.initial_kg:
        raise BusinessRuleError("Lotun yerleştirilmemiş miktarı aşılıyor.")
    check_silo_accepts(session, silo, lot, qty)
    mv = StockMovement(lot_id=lot.id, silo_id=silo.id, movement_type=StockMovementType.RECEIPT,
                       quantity_kg=qty, moved_at=at or utcnow(), created_by_id=created_by_id)
    session.add(mv)
    session.flush()
    return mv


def transfer_lot(session: Session, lot: Lot, from_silo: Silo, to_silo: Silo, quantity_kg: Decimal,
                 created_by_id: int, at: datetime | None = None) -> str:
    qty = Decimal(quantity_kg)
    if qty <= 0 or lot_stock_in_silo(session, lot.id, from_silo.id) < qty:
        raise BusinessRuleError("Kaynak siloda bu lottan yeterli stok yok.")
    check_silo_accepts(session, to_silo, lot, qty)
    group, at = str(uuid.uuid4()), at or utcnow()
    session.add_all([
        StockMovement(lot_id=lot.id, silo_id=from_silo.id, movement_type=StockMovementType.TRANSFER_OUT,
                      quantity_kg=-qty, moved_at=at, transfer_group=group, created_by_id=created_by_id),
        StockMovement(lot_id=lot.id, silo_id=to_silo.id, movement_type=StockMovementType.TRANSFER_IN,
                      quantity_kg=qty, moved_at=at, transfer_group=group, created_by_id=created_by_id),
    ])
    session.flush()
    return group
