from fastapi import APIRouter, status

from app.api.deps import CurrentUser, DbSession
from app.schemas.common import HATA_400, HATA_404, HATA_422
from app.schemas.fiyat import FiyatGirisi, FiyatYaniti, GuncelFiyatlar
from app.services import fiyat_service

router = APIRouter(prefix="/fiyatlar", tags=["Fiyat Yönetimi"])


@router.post(
    "", response_model=list[FiyatYaniti], status_code=status.HTTP_201_CREATED,
    summary="Kalite sınıflarına göre taban fiyat girişi",
    description="Girilen fiyatlar onaylanmış olarak kaydedilir. Aynı sınıfın önceki fiyatı, yeni fiyatın "
                "başladığı anda kapanır. Geçmişe dönük fiyat girilemez; böylece önceki tartımların "
                "kilitli fiyatı asla değişmez.",
    responses={**HATA_400, **HATA_404, **HATA_422},
)
def fiyat_gir(data: FiyatGirisi, db: DbSession, user: CurrentUser):
    return fiyat_service.enter_prices(db, data, user.id)


@router.get("/guncel", response_model=GuncelFiyatlar, summary="Şu an geçerli fiyatlar (her kalite sınıfı için)")
def guncel_fiyatlar(db: DbSession):
    return fiyat_service.current_prices(db)
