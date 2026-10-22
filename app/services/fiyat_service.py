from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.errors import NotFoundError
from app.core.timeutil import as_aware
from app.db.models import DailyPrice, QualityGrade
from app.domain import rules
from app.schemas.fiyat import FiyatGirisi, FiyatYaniti, GuncelFiyatKalemi, GuncelFiyatlar


def _fiyat_yaniti(dp: DailyPrice) -> FiyatYaniti:
    return FiyatYaniti(
        id=dp.id, kalite_kodu=dp.quality_grade.code, kalite_adi=dp.quality_grade.name,
        birim_fiyat=dp.price_per_kg, para_birimi=dp.currency, gecerlilik_baslangici=dp.valid_from,
        gecerlilik_bitisi=dp.valid_to, durum=dp.status, onay_zamani=dp.approved_at,
    )


def enter_prices(db: Session, data: FiyatGirisi, user_id: int) -> list[FiyatYaniti]:
    """Fiyatları girer ve onaylar. Aynı sınıfın açık fiyatı yeni fiyatın başladığı an kapanır;
    daha önce yapılmış tartımların kilitli fiyatı değişmez."""
    now = rules.utcnow()
    valid_from = as_aware(data.gecerlilik_baslangici) if data.gecerlilik_baslangici else now
    grades = {g.code: g for g in db.scalars(select(QualityGrade).where(QualityGrade.is_active.is_(True)))}

    created = []
    for kalem in data.fiyatlar:
        grade = grades.get(kalem.kalite_kodu)
        if grade is None:
            raise NotFoundError(
                f"'{kalem.kalite_kodu}' kodlu aktif kalite sınıfı yok. Tanımlı sınıflar: {', '.join(sorted(grades))}.")
        dp = DailyPrice(quality_grade_id=grade.id, price_per_kg=kalem.birim_fiyat, valid_from=valid_from,
                        created_by_id=user_id, note=data.aciklama)
        db.add(dp)
        db.flush()
        rules.approve_price(db, dp, user_id, at=now)
        created.append(dp)
    db.commit()
    return [_fiyat_yaniti(dp) for dp in created]


def current_prices(db: Session) -> GuncelFiyatlar:
    now = rules.utcnow()
    kalemler = []
    for g in db.scalars(select(QualityGrade).where(QualityGrade.is_active.is_(True)).order_by(QualityGrade.priority)):
        dp = rules.find_effective_price(db, g.id, now)
        kalemler.append(GuncelFiyatKalemi(kalite_kodu=g.code, kalite_adi=g.name,
                                          fiyat=_fiyat_yaniti(dp) if dp else None))
    return GuncelFiyatlar(sorgu_zamani=now, kalemler=kalemler)
