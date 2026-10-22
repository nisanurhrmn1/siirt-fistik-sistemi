from decimal import Decimal
from typing import Annotated, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

# ─── Tekrar kullanılan doğrulanmış tipler (ihlalde 422 döner) ───
Kilogram = Annotated[Decimal, Field(gt=0, le=100000, max_digits=12, decimal_places=2,
                                    description="Kilogram, 0'dan büyük, en fazla 2 ondalık")]
DaraKilogram = Annotated[Decimal, Field(ge=0, le=100000, max_digits=12, decimal_places=2,
                                        description="Kilogram, negatif olamaz")]
Oran = Annotated[Decimal, Field(ge=0, le=100, max_digits=5, decimal_places=2, description="Yüzde, 0–100")]
Para = Annotated[Decimal, Field(gt=0, max_digits=14, decimal_places=2, description="TL, 0'dan büyük")]
BirimFiyat = Annotated[Decimal, Field(gt=0, max_digits=12, decimal_places=2, description="TL/kg")]


class IstekModeli(BaseModel):
    """İstek gövdeleri: tanımsız alan reddedilir (yazım hatası sessizce yutulmasın), metinler kırpılır."""
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class YanitModeli(BaseModel):
    model_config = ConfigDict(from_attributes=True, populate_by_name=True)


class HataYaniti(BaseModel):
    hata: str = Field(examples=["Üretici bulunamadı (id=42)."])


class DogrulamaHatasiDetay(BaseModel):
    alan: str = Field(examples=["kalite.nem_orani"])
    mesaj: str = Field(examples=["En fazla 100 olabilir."])


class DogrulamaHatasi(BaseModel):
    hata: str = Field(examples=["Gönderilen veriler geçersiz."])
    detaylar: list[DogrulamaHatasiDetay]


T = TypeVar("T")


class Sayfa(BaseModel, Generic[T]):
    toplam: int
    sayfa: int
    sayfa_boyutu: int
    kayitlar: list[T]


# Swagger'da her endpoint'in olası hata yanıtları
HATA_400 = {400: {"model": HataYaniti, "description": "İş kuralı ihlali"}}
HATA_404 = {404: {"model": HataYaniti, "description": "Kayıt bulunamadı"}}
HATA_409 = {409: {"model": HataYaniti, "description": "Tekrarlı kayıt"}}
HATA_422 = {422: {"model": DogrulamaHatasi, "description": "Doğrulama hatası"}}
