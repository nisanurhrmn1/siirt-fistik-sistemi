from datetime import date
from typing import Annotated

from fastapi import APIRouter, Query, status

from app.api.deps import CurrentUser, DbSession
from app.core.timeutil import today_tr
from app.schemas.common import HATA_400, HATA_404, HATA_422
from app.schemas.tartim import OnizlemeGirisi, TartimGirisi, TartimOnizleme, TartimOzeti, TartimYaniti
from app.services import tartim_service

router = APIRouter(prefix="/tartimlar", tags=["Kantar ve Alım"])


@router.post(
    "", response_model=TartimYaniti, status_code=status.HTTP_201_CREATED,
    summary="Tartım + kalite analizi ile fıstık alımı",
    description=(
        "Tek işlemde (hepsi ya da hiçbiri):\n\n"
        "1. Kantar fişi ve net kg (brüt − dara)\n"
        "2. Kalite analizi ve sınıf ataması (veya gerekçeli manuel sınıf)\n"
        "3. **Fiyat kilidi:** tartım anında geçerli ve onaylı günlük fiyat fişe kopyalanır; "
        "sonradan fiyat değişse de bu teslimat etkilenmez\n"
        "4. Hakediş: brüt − yasal kesintiler − açık avans mahsubu\n"
        "5. Lot açılır ve kaliteye uygun siloya stok girişi yapılır\n\n"
        "Aynı üretici aynı gün birden çok teslimat yapabilir; her biri ayrı fiş/lot numarası alır."
    ),
    responses={**HATA_400, **HATA_404, **HATA_422},
)
def tartim_kaydet(data: TartimGirisi, db: DbSession, user: CurrentUser):
    ticket = tartim_service.register_delivery(db, data, user.id)
    return tartim_service.build_tartim_yaniti(db, ticket)


@router.post(
    "/onizleme", response_model=TartimOnizleme,
    summary="Kaydetmeden tahmini hakediş",
    description="Kantar ekranındaki canlı hesaplama için: kalite sınıfı, şu anki fiyat, kesintiler, "
                "açık avans mahsubu ve tahmini net ödeme. Veritabanına hiçbir şey yazılmaz.",
    responses={**HATA_404, **HATA_422},
)
def tartim_onizleme(data: OnizlemeGirisi, db: DbSession):
    return tartim_service.preview_delivery(db, data)


@router.get("", response_model=list[TartimOzeti], summary="Günün tartımları (en yeni önce)", responses=HATA_422)
def tartim_listele(
    db: DbSession,
    tarih: Annotated[date | None, Query(description="YYYY-AA-GG; boşsa bugün (Türkiye saati)")] = None,
    uretici_id: Annotated[int | None, Query(gt=0)] = None,
):
    return tartim_service.list_deliveries(db, tarih or today_tr(), uretici_id)


@router.get("/{tartim_id}", response_model=TartimYaniti, summary="Tartım (teslimat) detayı", responses=HATA_404)
def tartim_getir(tartim_id: int, db: DbSession):
    return tartim_service.build_tartim_yaniti(db, tartim_service.get_ticket(db, tartim_id))
