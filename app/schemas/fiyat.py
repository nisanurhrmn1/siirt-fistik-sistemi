from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field, field_validator

from app.db.enums import PriceStatus
from app.schemas.common import BirimFiyat, IstekModeli


class FiyatKalemi(IstekModeli):
    kalite_kodu: str = Field(min_length=1, max_length=10, examples=["A"])
    birim_fiyat: BirimFiyat = Field(examples=[300])

    @field_validator("kalite_kodu")
    @classmethod
    def _upper(cls, v: str) -> str:
        return v.upper()


class FiyatGirisi(IstekModeli):
    fiyatlar: list[FiyatKalemi] = Field(min_length=1, description="Her kalite sınıfı için bir kalem")
    gecerlilik_baslangici: datetime | None = Field(
        None, description="Boşsa hemen geçerli olur. Saat dilimi verilmezse Türkiye saati kabul edilir. "
                          "Geçmiş bir zaman olamaz (geçmiş tartımların fiyatı değişmesin).")
    aciklama: str | None = Field(None, max_length=500)

    @field_validator("fiyatlar")
    @classmethod
    def _tekil(cls, v: list[FiyatKalemi]) -> list[FiyatKalemi]:
        codes = [k.kalite_kodu for k in v]
        if len(codes) != len(set(codes)):
            raise ValueError("Aynı kalite sınıfı listede birden fazla kez yer alamaz.")
        return v


class FiyatYaniti(BaseModel):
    id: int
    kalite_kodu: str
    kalite_adi: str
    birim_fiyat: Decimal
    para_birimi: str
    gecerlilik_baslangici: datetime
    gecerlilik_bitisi: datetime | None
    durum: PriceStatus
    onay_zamani: datetime | None


class GuncelFiyatKalemi(BaseModel):
    kalite_kodu: str
    kalite_adi: str
    fiyat: FiyatYaniti | None = Field(description="Bu sınıf için şu an geçerli onaylı fiyat yoksa null")


class GuncelFiyatlar(BaseModel):
    sorgu_zamani: datetime
    kalemler: list[GuncelFiyatKalemi]
