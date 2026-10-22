from datetime import date
from typing import Annotated, Literal

from fastapi import APIRouter, Query

from app.api.deps import DbSession
from app.core.timeutil import today_tr
from app.schemas.common import HATA_404, HATA_422
from app.schemas.rapor import GunlukOzet, StokDurumu, UreticiBakiye
from app.services import rapor_service

depo_router = APIRouter(prefix="/depo", tags=["Depo"])
rapor_router = APIRouter(prefix="/raporlar", tags=["Raporlar"])


@depo_router.get(
    "/stok-durumu", response_model=StokDurumu, summary="Silo veya kalite sınıfına göre stok",
    description="`gruplama=silo`: her silonun stok, kapasite ve doluluk oranı (kalite kırılımıyla). "
                "`gruplama=kalite`: her kalite sınıfının toplam stoğu ve bulunduğu silolar.",
    responses={**HATA_404, **HATA_422},
)
def stok_durumu(
    db: DbSession,
    gruplama: Literal["silo", "kalite"] = "silo",
    silo_id: Annotated[int | None, Query(description="Yalnızca bu silo")] = None,
    kalite_kodu: Annotated[str | None, Query(max_length=10, description="Yalnızca bu kalite sınıfı")] = None,
):
    return rapor_service.stock_status(db, gruplama, silo_id, kalite_kodu)


@rapor_router.get("/uretici-bakiyeleri", response_model=list[UreticiBakiye],
                  summary="Tüm üreticilerin cari bakiye, açık avans ve ödenebilir tutarı")
def uretici_bakiyeleri(db: DbSession):
    return rapor_service.producer_balances(db)


@rapor_router.get(
    "/gunluk-ozet", response_model=GunlukOzet, summary="Günlük alım ve ödeme özeti",
    description="Gün içinde tartılan fıstık miktarı, kg ağırlıklı ortalama randıman ve fiyat, hakediş "
                "toplamları, o gün yapılan ödemeler ve verilen avanslar. Gün sınırı Türkiye saatidir.",
    responses=HATA_422,
)
def gunluk_ozet(
    db: DbSession,
    tarih: Annotated[date | None, Query(description="YYYY-AA-GG; boşsa bugün")] = None,
):
    return rapor_service.daily_summary(db, tarih or today_tr())
