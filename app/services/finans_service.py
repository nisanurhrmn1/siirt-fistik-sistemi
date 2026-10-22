from datetime import date
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.core.errors import BusinessRuleError, NotFoundError
from app.core.timeutil import day_bounds_utc
from app.db.enums import LedgerEntryType, TicketStatus
from app.db.models import (
    Advance, AdvanceOffset, DeductionType, Payment, Producer, ProducerBankAccount, ProducerLedgerEntry,
    QualityInspection, Settlement, SettlementDeduction, WeighTicket,
)
from app.domain import rules
from app.schemas.finans import (
    AvansGirisi, AvansMahsubuYaniti, AvansYaniti, EkstreAvans, EkstreHareket, EkstreOzeti, EkstreTeslimat,
    HakedisYaniti, KesintiYaniti, OdemeGirisi, OdemeSonucu, OdemeYaniti, UreticiEkstre, UreticiKisa,
)
from app.services.uretici_service import get_producer

ZERO = Decimal("0")


# ─── Hakediş ───

def get_settlement(db: Session, settlement_id: int) -> Settlement:
    s = db.get(Settlement, settlement_id)
    if s is None:
        raise NotFoundError(f"Hakediş bulunamadı (id={settlement_id}).")
    return s


def build_hakedis_yaniti(db: Session, s: Settlement) -> HakedisYaniti:
    deductions = db.execute(
        select(SettlementDeduction, DeductionType)
        .join(DeductionType, DeductionType.id == SettlementDeduction.deduction_type_id)
        .where(SettlementDeduction.settlement_id == s.id)
        .order_by(DeductionType.sort_order)
    ).all()
    offsets = db.execute(
        select(AdvanceOffset, Advance.advance_no)
        .join(Advance, Advance.id == AdvanceOffset.advance_id)
        .where(AdvanceOffset.settlement_id == s.id)
        .order_by(AdvanceOffset.id)
    ).all()
    return HakedisYaniti(
        id=s.id, hakedis_no=s.settlement_no, tartim_id=s.ticket_id, fis_no=s.ticket.ticket_no,
        uretici_id=s.producer_id, net_kg=s.net_kg, birim_fiyat=s.unit_price, brut_tutar=s.gross_amount,
        kesintiler=[
            KesintiYaniti(kod=dt.code, ad=dt.name, hesaplama=d.calc_method, oran=d.rate,
                          matrah=d.base_amount, tutar=d.amount)
            for d, dt in deductions
        ],
        kesinti_toplami=s.deductions_total,
        avans_mahsuplari=[AvansMahsubuYaniti(avans_id=o.advance_id, avans_no=no, tutar=o.amount) for o, no in offsets],
        avans_mahsubu_toplami=s.advance_offset_total,
        net_odenecek=s.net_payable, odenen=s.paid_amount, kalan=s.net_payable - s.paid_amount,
        durum=s.status, olusturma_zamani=s.created_at,
    )


# ─── Avans ───

def give_advance(db: Session, data: AvansGirisi, user_id: int) -> AvansYaniti:
    get_producer(db, data.uretici_id)  # 404
    adv = rules.give_advance(
        db, producer_id=data.uretici_id, amount=data.tutar, method=data.odeme_yontemi, created_by_id=user_id,
        bank_account_id=data.banka_hesap_id, description=data.aciklama,
    )
    db.commit()
    return AvansYaniti(
        id=adv.id, avans_no=adv.advance_no, uretici_id=adv.producer_id, tutar=adv.amount,
        mahsup_edilen=adv.offset_amount, kalan=adv.amount - adv.offset_amount, durum=adv.status,
        odeme_yontemi=adv.method, verilis_zamani=adv.given_at, aciklama=adv.description,
        uretici_guncel_bakiye=rules.producer_balance(db, adv.producer_id),
    )


# ─── Ödeme ───

def pay_settlement(db: Session, settlement_id: int, data: OdemeGirisi, user_id: int) -> tuple[OdemeSonucu, bool]:
    """Hakedişin kalanını öder. Önce hakediş oluştuktan sonra verilmiş açık avansları mahsup eder.
    Dönüş: (sonuç, ödeme_kaydı_oluştu_mu)."""
    s = get_settlement(db, settlement_id)
    rules.lock_producer(db, s.producer_id)
    if s.net_payable - s.paid_amount <= 0:
        raise BusinessRuleError(
            f"{s.settlement_no} için ödenecek tutar kalmadı (durum: {s.status.value}).")

    offset_now = rules.offset_advances_into_settlement(db, s)
    remaining = s.net_payable - s.paid_amount

    payment = None
    if remaining > 0:
        amount = data.tutar if data.tutar is not None else remaining
        if amount > remaining:
            raise BusinessRuleError(
                f"Ödeme tutarı ({amount} TL) hakedişin kalan tutarını ({remaining} TL) aşamaz.")
        payment = rules.record_payment(
            db, producer_id=s.producer_id, amount=amount, method=data.odeme_yontemi, created_by_id=user_id,
            bank_account_id=data.banka_hesap_id, reference_no=data.referans_no, description=data.aciklama,
            settlement_id=s.id,
        )
    db.commit()

    if payment is None:
        mesaj = "Hakedişin kalan tutarının tamamı açık avanslardan mahsup edildi; ödeme yapılmadı."
    elif s.net_payable == s.paid_amount:
        mesaj = f"{payment.amount} TL ödendi; hakediş tamamen kapandı."
    else:
        mesaj = f"{payment.amount} TL kısmi ödeme yapıldı; kalan {s.net_payable - s.paid_amount} TL."

    sonuc = OdemeSonucu(
        mesaj=mesaj,
        bu_islemde_mahsup_edilen_avans=offset_now,
        odeme=OdemeYaniti(
            id=payment.id, odeme_no=payment.payment_no, tutar=payment.amount, odeme_yontemi=payment.method,
            banka_hesap_id=payment.bank_account_id, iban=_payment_iban(db, payment), odeme_zamani=payment.paid_at,
            referans_no=payment.reference_no,
        ) if payment else None,
        hakedis=build_hakedis_yaniti(db, s),
        uretici_guncel_bakiye=rules.producer_balance(db, s.producer_id),
    )
    return sonuc, payment is not None


# ─── Ekstre ───

def producer_statement(db: Session, producer_id: int, baslangic: date | None, bitis: date | None) -> UreticiEkstre:
    producer: Producer = get_producer(db, producer_id)
    if baslangic and bitis and baslangic > bitis:
        raise BusinessRuleError("baslangic tarihi bitis tarihinden sonra olamaz.")
    start = day_bounds_utc(baslangic)[0] if baslangic else None
    end = day_bounds_utc(bitis)[1] if bitis else None

    def in_period(dt) -> bool:
        return (start is None or dt >= start) and (end is None or dt < end)

    ledger = db.scalars(
        select(ProducerLedgerEntry).where(ProducerLedgerEntry.producer_id == producer_id)
        .order_by(ProducerLedgerEntry.entry_at, ProducerLedgerEntry.id)
    ).all()
    advances = db.scalars(select(Advance).where(Advance.producer_id == producer_id)
                          .order_by(Advance.given_at, Advance.id)).all()
    settlements = db.scalars(select(Settlement).where(Settlement.producer_id == producer_id)).all()

    def total(t: LedgerEntryType) -> Decimal:
        return sum((e.amount for e in ledger if e.entry_type == t), ZERO)

    balance = sum((e.amount for e in ledger), ZERO)
    unpaid = sum((s.net_payable - s.paid_amount for s in settlements), ZERO)
    ozet = EkstreOzeti(
        bakiye=balance,
        bakiye_durumu="ALACAKLI" if balance > 0 else "BORCLU" if balance < 0 else "KAPALI",
        toplam_teslimat_kg=sum((s.net_kg for s in settlements), ZERO),
        toplam_teslimat_tutari=total(LedgerEntryType.DELIVERY),
        toplam_kesinti=-total(LedgerEntryType.DEDUCTION),
        toplam_verilen_avans=-total(LedgerEntryType.ADVANCE),
        toplam_odeme=-total(LedgerEntryType.PAYMENT),
        acik_avans_bakiyesi=sum((a.amount - a.offset_amount for a in advances), ZERO),
        odenmemis_hakedis_toplami=unpaid,
        odenebilir_tutar=max(min(unpaid, balance), ZERO),
    )

    devir = sum((e.amount for e in ledger if start is not None and e.entry_at < start), ZERO)
    running = devir
    hareketler = []
    for e in ledger:
        if not in_period(e.entry_at):
            continue
        running += e.amount
        hareketler.append(EkstreHareket(
            id=e.id, tarih=e.entry_at, tur=e.entry_type.value, aciklama=e.description,
            alacak=e.amount if e.amount > 0 else ZERO, borc=-e.amount if e.amount < 0 else ZERO, bakiye=running,
        ))

    tickets = db.scalars(
        select(WeighTicket)
        .where(WeighTicket.producer_id == producer_id, WeighTicket.status != TicketStatus.CANCELLED)
        .options(selectinload(WeighTicket.inspection).selectinload(QualityInspection.quality_grade),
                 selectinload(WeighTicket.settlement), selectinload(WeighTicket.lot))
        .order_by(WeighTicket.gross_weighed_at, WeighTicket.id)
    ).all()
    teslimatlar = []
    for t in tickets:
        if not in_period(t.gross_weighed_at):
            continue
        s, q = t.settlement, t.inspection
        teslimatlar.append(EkstreTeslimat(
            tartim_id=t.id, fis_no=t.ticket_no, lot_no=t.lot.lot_no if t.lot else None, tarih=t.gross_weighed_at,
            net_kg=t.net_kg if t.net_kg is not None else ZERO,
            kalite_kodu=q.quality_grade.code if q else None, randiman=q.yield_ratio if q else None,
            birim_fiyat=t.unit_price, hakedis_id=s.id if s else None,
            brut_tutar=s.gross_amount if s else None, kesinti=s.deductions_total if s else None,
            avans_mahsubu=s.advance_offset_total if s else None, net_odenecek=s.net_payable if s else None,
            odenen=s.paid_amount if s else None, kalan=(s.net_payable - s.paid_amount) if s else None,
            hakedis_durumu=s.status if s else None,
        ))

    return UreticiEkstre(
        uretici=UreticiKisa(id=producer.id, ad_soyad=producer.full_name, tc_kimlik_no=producer.national_id,
                            vergi_no=producer.tax_number, telefon=producer.phone),
        donem_baslangic=start, donem_bitis=end, ozet=ozet, devreden_bakiye=devir,
        teslimatlar=teslimatlar,
        acik_avanslar=[
            EkstreAvans(id=a.id, avans_no=a.advance_no, tarih=a.given_at, tutar=a.amount,
                        mahsup_edilen=a.offset_amount, kalan=a.amount - a.offset_amount, durum=a.status)
            for a in advances if a.offset_amount < a.amount
        ],
        hareketler=hareketler,
    )


def _payment_iban(db: Session, payment: Payment) -> str | None:
    return db.get(ProducerBankAccount, payment.bank_account_id).iban if payment.bank_account_id else None
