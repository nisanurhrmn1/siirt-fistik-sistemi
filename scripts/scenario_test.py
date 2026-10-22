"""Kritik iş kurallarını uçtan uca doğrulayan senaryo (SQLite in-memory).
Çalıştır: python -m scripts.scenario_test"""
import warnings
from datetime import datetime, timedelta, timezone
from decimal import Decimal as D

from sqlalchemy import create_engine, event, select
from sqlalchemy.exc import SAWarning
from sqlalchemy.orm import Session

from app.db import Base
from app.db.enums import (
    DeductionCalcMethod, PaymentMethod, PriceStatus, ProducerType, SettlementStatus, TicketStatus, UserRole,
)
from app.db.models import (
    Advance, DailyPrice, DeductionType, Producer, ProducerBankAccount, QualityGrade, Silo, User,
)
from app.domain import rules as r

warnings.filterwarnings("ignore", category=SAWarning, message=".*Decimal objects natively.*")

T0 = datetime(2026, 10, 22, tzinfo=timezone.utc)  # gün başı


def h(hour, minute=0):
    return T0 + timedelta(hours=hour, minutes=minute)


def make_iban(bank_and_account: str) -> str:
    """TR + kontrol + 22 hane; mod-97 kontrol basamaklarını hesaplar."""
    num = int("".join(str(int(c, 36)) for c in bank_and_account + "TR00"))
    return f"TR{98 - num % 97:02d}{bank_and_account}"


def expect_error(fn, *args, **kw):
    try:
        fn(*args, **kw)
    except (r.BusinessRuleError, ValueError) as e:
        print(f"   ✓ beklenen hata: {e}")
        return
    raise AssertionError("Hata bekleniyordu")


def main():
    engine = create_engine("sqlite://")
    event.listen(engine, "connect", lambda c, _: c.execute("PRAGMA foreign_keys=ON"))
    Base.metadata.create_all(engine)

    with Session(engine) as s:
        # ── Ana veriler
        admin = User(username="admin", full_name="Admin", role=UserRole.ADMIN)
        s.add(admin)
        s.flush()
        u = admin.id
        A = QualityGrade(code="A", name="1. Sınıf", priority=1, min_split_ratio=D("85"),
                         min_yield_ratio=D("55"), max_defect_ratio=D("5"), max_moisture_ratio=D("8"))
        B = QualityGrade(code="B", name="2. Sınıf", priority=2, min_split_ratio=D("70"),
                         min_yield_ratio=D("48"), max_defect_ratio=D("10"), max_moisture_ratio=D("10"))
        C = QualityGrade(code="ISK", name="Iskarta", priority=9)
        s.add_all([A, B, C])
        # Oranlar ÖRNEKTİR — güncel mevzuat/borsa tarifesine göre ayarlanmalı.
        s.add_all([
            DeductionType(code="STOPAJ", name="Gelir vergisi stopajı", calc_method=DeductionCalcMethod.PERCENT,
                          rate=D("2"), sort_order=1),
            DeductionType(code="BORSA", name="Borsa tescil ücreti", calc_method=DeductionCalcMethod.PERCENT,
                          rate=D("0.2"), sort_order=2),
            DeductionType(code="KOMISYON", name="Komisyon", calc_method=DeductionCalcMethod.PERCENT,
                          rate=D("1"), sort_order=3),
        ])
        s.flush()

        assert r.validate_tc_kimlik("10000000146") and not r.validate_tc_kimlik("10000000147")
        iban = make_iban("0006200000000123456789")
        assert r.validate_iban(iban)
        p = Producer(producer_type=ProducerType.INDIVIDUAL, national_id="10000000146",
                     full_name="Ahmet Yılmaz", phone="05550000000", district="Kurtalan")
        s.add(p)
        s.flush()
        s.add(ProducerBankAccount(producer_id=p.id, bank_name="Ziraat", iban=iban,
                                  account_holder=p.full_name, is_default=True))

        # ── Fiyatlar: 06:00'dan itibaren A=300, B=260, ISK=150 (05:30'da onaylandı)
        prices = {}
        for g, price in [(A, "300"), (B, "260"), (C, "150")]:
            dp = DailyPrice(quality_grade_id=g.id, price_per_kg=D(price), valid_from=h(6), created_by_id=u)
            s.add(dp)
            s.flush()
            r.approve_price(s, dp, u, at=h(5, 30))
            prices[g.code] = dp

        print("1) Onaylı fiyat değiştirilemez")
        expect_error(setattr, prices["A"], "price_per_kg", D("999"))

        print("2) 500.000 TL avans → bakiye eksi")
        r.give_advance(s, producer_id=p.id, amount=D("500000"), method=PaymentMethod.BANK_TRANSFER,
                       created_by_id=u, given_at=h(7))
        assert r.producer_balance(s, p.id) == D("-500000.00")

        print("3) Aynı gün 3 teslimat (08:00, 11:30, 14:00)")
        t1 = r.create_weigh_ticket(s, producer_id=p.id, vehicle_plate="56 abc 123", gross_kg=D("4800"),
                                   weighed_by_id=u, weighed_at=h(8))
        t2 = r.create_weigh_ticket(s, producer_id=p.id, vehicle_plate="56ABC123", gross_kg=D("4600"),
                                   weighed_by_id=u, weighed_at=h(11, 30))
        r.record_tare(s, t1, D("3800"), at=h(8, 20))   # net 1000
        r.record_tare(s, t2, D("3800"), at=h(11, 50))  # net 800
        assert t1.ticket_no != t2.ticket_no
        print(f"   fişler: {t1.ticket_no}, {t2.ticket_no}")

        print("4) 12:00'de A fiyatı 320'ye çıkıyor (11:55'te onaylandı)")
        new_a = DailyPrice(quality_grade_id=A.id, price_per_kg=D("320"), valid_from=h(12), created_by_id=u)
        s.add(new_a)
        s.flush()
        r.approve_price(s, new_a, u, at=h(11, 55))
        assert prices["A"].valid_to is not None

        t3 = r.create_weigh_ticket(s, producer_id=p.id, vehicle_plate="56ABC123", gross_kg=D("4300"),
                                   weighed_by_id=u, weighed_at=h(14))
        r.record_tare(s, t3, D("3800"), at=h(14, 15))  # net 500

        print("5) Kalite analizleri 13:00 sonrası → t2 yine de 300 TL (tartım 11:30)")
        good = dict(sample_weight_g=D("500"), split=D("90"), yield_=D("58"), empty=D("2"),
                    rotten=D("1"), moisture=D("6"), inspected_by_id=u)
        r.record_quality_inspection(s, t1, at=h(13), **good)
        r.record_quality_inspection(s, t2, at=h(13, 5), **good)
        r.record_quality_inspection(s, t3, at=h(15), **good)
        assert (t1.unit_price, t2.unit_price, t3.unit_price) == (D("300"), D("300"), D("320")), \
            (t1.unit_price, t2.unit_price, t3.unit_price)
        print(f"   birim fiyatlar: {t1.unit_price} / {t2.unit_price} / {t3.unit_price}")
        expect_error(setattr, t2, "unit_price", D("320"))

        print("6) Hakediş t1: avans > teslimat → net 0, bakiye eksi")
        s1 = r.create_settlement(s, t1, u, at=h(16))
        print(f"   brüt {s1.gross_amount}, kesinti {s1.deductions_total}, mahsup {s1.advance_offset_total}, "
              f"net {s1.net_payable}, durum {s1.status.value}")
        assert s1.gross_amount == D("300000.00") and s1.deductions_total == D("9600.00")
        assert s1.net_payable == 0 and s1.status == SettlementStatus.OFFSET_ONLY
        assert r.producer_balance(s, p.id) == D("-209600.00")

        print("7) Hakediş t2: kalan avans mahsup edilir")
        s2 = r.create_settlement(s, t2, u, at=h(16, 5))
        assert s2.advance_offset_total == D("209600.00") and s2.net_payable == D("22720.00"), s2.net_payable
        adv = s.execute(select(Advance)).scalar_one()
        assert adv.status.value == "CLOSED"
        s3 = r.create_settlement(s, t3, u, at=h(16, 10))
        assert s3.gross_amount == D("160000.00") and s3.net_payable == D("154880.00")
        bal = r.producer_balance(s, p.id)
        assert bal == D("177600.00"), bal
        print(f"   bakiye: {bal}")

        print("8) Parçalı ödeme 100.000 → s2 tam, s3 kısmi")
        r.record_payment(s, producer_id=p.id, amount=D("100000"), method=PaymentMethod.BANK_TRANSFER,
                         created_by_id=u, paid_at=h(17))
        assert s2.status == SettlementStatus.PAID and s3.status == SettlementStatus.PARTIALLY_PAID
        assert r.producer_balance(s, p.id) == D("77600.00")
        expect_error(r.record_payment, s, producer_id=p.id, amount=D("80000"),
                     method=PaymentMethod.CASH, created_by_id=u, paid_at=h(17, 5))

        print("9) Silo kalite ayrıştırması")
        siloA = Silo(code="S-A1", name="A Silosu", capacity_kg=D("50000"), dedicated_grade_id=A.id)
        siloB = Silo(code="S-B1", name="B Silosu", capacity_kg=D("50000"), dedicated_grade_id=B.id)
        siloX = Silo(code="S-X1", name="Genel", capacity_kg=D("1200"))
        s.add_all([siloA, siloB, siloX])
        s.flush()
        r.receive_lot_into_silo(s, t1.lot, siloA, u, at=h(18))
        r.receive_lot_into_silo(s, t2.lot, siloA, u, at=h(18))
        expect_error(r.receive_lot_into_silo, s, t3.lot, siloB, u, at=h(18))
        r.receive_lot_into_silo(s, t3.lot, siloX, u, at=h(18))
        expect_error(r.transfer_lot, s, t1.lot, siloA, siloX, D("800"), u, at=h(19))  # kapasite
        r.transfer_lot(s, t1.lot, siloA, siloX, D("500"), u, at=h(19))
        assert r.silo_stock(s, siloA.id) == D("1300") and r.silo_stock(s, siloX.id) == D("1000")

        print("10) Geçersiz durum geçişi (dara/kalite olmadan hakediş)")
        t4 = r.create_weigh_ticket(s, producer_id=p.id, vehicle_plate="56ABC123", gross_kg=D("4000"),
                                   weighed_by_id=u, weighed_at=h(20))
        expect_error(r.create_settlement, s, t4, u)
        assert t4.status == TicketStatus.GROSS_WEIGHED
        assert prices["A"].status == PriceStatus.APPROVED

        s.commit()
    print("\nTÜM SENARYOLAR GEÇTİ ✓")


if __name__ == "__main__":
    main()
