from datetime import date
from typing import Annotated

from fastapi import APIRouter, Query, status

from app.api.deps import DbSession
from app.schemas.common import HATA_400, HATA_404, HATA_409, HATA_422, Sayfa
from app.schemas.finans import UreticiEkstre
from app.schemas.uretici import UreticiGirisi, UreticiYaniti
from app.services import finans_service, uretici_service

router = APIRouter(prefix="/ureticiler", tags=["Üreticiler"])


@router.post(
    "", response_model=UreticiYaniti, status_code=status.HTTP_201_CREATED,
    summary="Yeni üretici kaydı",
    description="TC Kimlik No (algoritma kontrolü), Vergi No, telefon ve IBAN (mod-97) doğrulanır. "
                "Aynı TC/VKN ile ikinci kayıt 409 döner.",
    responses={**HATA_409, **HATA_422},
)
def uretici_olustur(data: UreticiGirisi, db: DbSession):
    return UreticiYaniti.model_validate(uretici_service.create_producer(db, data))


@router.get("", response_model=Sayfa[UreticiYaniti], summary="Üretici listesi (sayfalı, aranabilir)",
            responses=HATA_422)
def uretici_listele(
    db: DbSession,
    arama: Annotated[str | None, Query(max_length=100, description="Ad, TC, VKN veya telefonda arar")] = None,
    aktif: Annotated[bool | None, Query(description="Yalnızca aktif/pasif üreticiler")] = None,
    sayfa: Annotated[int, Query(ge=1)] = 1,
    sayfa_boyutu: Annotated[int, Query(ge=1, le=100)] = 20,
):
    toplam, items = uretici_service.list_producers(db, arama=arama, aktif=aktif, sayfa=sayfa,
                                                   sayfa_boyutu=sayfa_boyutu)
    return Sayfa[UreticiYaniti](toplam=toplam, sayfa=sayfa, sayfa_boyutu=sayfa_boyutu,
                                kayitlar=[UreticiYaniti.model_validate(p) for p in items])


@router.get("/{uretici_id}", response_model=UreticiYaniti, summary="Üretici detayı", responses=HATA_404)
def uretici_getir(uretici_id: int, db: DbSession):
    return UreticiYaniti.model_validate(uretici_service.get_producer(db, uretici_id))


@router.get(
    "/{uretici_id}/bakiye-ekstre", response_model=UreticiEkstre,
    summary="Üretici cari hesap ekstresi",
    description="Genel özet (bakiye, açık avans, ödenmemiş hakediş, ödenebilir tutar), dönemdeki teslimatlar, "
                "açık avanslar ve yürüyen bakiyeli cari hareketler. Tarihler Türkiye saatine göredir.",
    responses={**HATA_400, **HATA_404, **HATA_422},
)
def uretici_ekstre(
    uretici_id: int, db: DbSession,
    baslangic: Annotated[date | None, Query(description="Dönem başı (dahil), YYYY-AA-GG")] = None,
    bitis: Annotated[date | None, Query(description="Dönem sonu (dahil), YYYY-AA-GG")] = None,
):
    return finans_service.producer_statement(db, uretici_id, baslangic, bitis)
