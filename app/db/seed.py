"""Geliştirme için başlangıç verisi. Yalnızca ilgili tablo boşsa ekler; tekrar çalıştırmak güvenlidir.
Fiyatlar burada girilmez — POST /api/v1/fiyatlar ile girilir."""
from decimal import Decimal as D

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.enums import DeductionCalcMethod, UserRole
from app.db.models import DeductionType, QualityGrade, Silo, User


def seed_reference_data(db: Session) -> None:
    if db.scalar(select(User.id).limit(1)) is None:
        db.add(User(username="admin", full_name="Sistem Yöneticisi", role=UserRole.ADMIN))

    if db.scalar(select(QualityGrade.id).limit(1)) is None:
        db.add_all([
            QualityGrade(code="A", name="1. Sınıf", priority=1, min_split_ratio=D("85"),
                         min_yield_ratio=D("55"), max_defect_ratio=D("5"), max_moisture_ratio=D("8")),
            QualityGrade(code="B", name="2. Sınıf", priority=2, min_split_ratio=D("70"),
                         min_yield_ratio=D("48"), max_defect_ratio=D("10"), max_moisture_ratio=D("10")),
            QualityGrade(code="ISK", name="Iskarta", priority=9),
        ])
        db.flush()

    if db.scalar(select(DeductionType.id).limit(1)) is None:
        # ÖRNEK oranlar — güncel mevzuat ve borsa tarifesine göre güncelleyin.
        db.add_all([
            DeductionType(code="STOPAJ", name="Gelir vergisi stopajı", calc_method=DeductionCalcMethod.PERCENT,
                          rate=D("2"), sort_order=1),
            DeductionType(code="BORSA", name="Borsa tescil ücreti", calc_method=DeductionCalcMethod.PERCENT,
                          rate=D("0.2"), sort_order=2),
            DeductionType(code="KOMISYON", name="Komisyon", calc_method=DeductionCalcMethod.PERCENT,
                          rate=D("1"), sort_order=3),
        ])

    if db.scalar(select(Silo.id).limit(1)) is None:
        grades = {g.code: g.id for g in db.scalars(select(QualityGrade))}
        db.add_all([
            Silo(code="S-A1", name="A Sınıfı Silo 1", capacity_kg=D("200000"), dedicated_grade_id=grades.get("A")),
            Silo(code="S-B1", name="B Sınıfı Silo 1", capacity_kg=D("200000"), dedicated_grade_id=grades.get("B")),
            Silo(code="S-ISK", name="Iskarta Deposu", capacity_kg=D("50000"), dedicated_grade_id=grades.get("ISK")),
        ])
    db.commit()
