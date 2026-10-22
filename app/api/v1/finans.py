from fastapi import APIRouter, Response, status

from app.api.deps import CurrentUser, DbSession
from app.schemas.common import HATA_400, HATA_404, HATA_422
from app.schemas.finans import AvansGirisi, AvansYaniti, HakedisYaniti, OdemeGirisi, OdemeSonucu
from app.services import finans_service

router = APIRouter(tags=["Finans: Avans, Hakediş, Ödeme"])


@router.post(
    "/avanslar", response_model=AvansYaniti, status_code=status.HTTP_201_CREATED,
    summary="Üreticiye avans ver",
    description="Avans cari hesaba borç olarak yazılır; üretici bakiyesi eksiye düşebilir. "
                "Sonraki hakedişlerden en eski avans önce olacak şekilde otomatik mahsup edilir.",
    responses={**HATA_400, **HATA_404, **HATA_422},
)
def avans_ver(data: AvansGirisi, db: DbSession, user: CurrentUser):
    return finans_service.give_advance(db, data, user.id)


@router.get("/hakedisler/{hakedis_id}", response_model=HakedisYaniti, summary="Hakediş detayı",
            responses=HATA_404)
def hakedis_getir(hakedis_id: int, db: DbSession):
    return finans_service.build_hakedis_yaniti(db, finans_service.get_settlement(db, hakedis_id))


@router.post(
    "/hakedisler/{hakedis_id}/odeme-yap", response_model=OdemeSonucu, status_code=status.HTTP_201_CREATED,
    summary="Hakediş ödemesi (kısmi veya tam)",
    description=(
        "Yasal kesintiler hakediş oluşurken düşülmüştür. Bu işlem önce, hakediş oluştuktan **sonra** verilmiş "
        "açık avansları kalan tutardan mahsup eder, ardından kalan net tutarı (veya `tutar` kadarını) öder.\n\n"
        "* **201** — ödeme kaydı oluştu\n"
        "* **200** — kalan tutarın tamamı avansa mahsup edildi, ödeme gerekmedi\n"
        "* **400** — hakediş zaten kapalı, tutar kalanı veya üretici bakiyesini aşıyor"
    ),
    responses={200: {"model": OdemeSonucu, "description": "Ödeme gerekmedi (tamamı mahsup)"},
               **HATA_400, **HATA_404, **HATA_422},
)
def odeme_yap(hakedis_id: int, data: OdemeGirisi, response: Response, db: DbSession, user: CurrentUser):
    sonuc, odeme_olustu = finans_service.pay_settlement(db, hakedis_id, data, user.id)
    if not odeme_olustu:
        response.status_code = status.HTTP_200_OK
    return sonuc
