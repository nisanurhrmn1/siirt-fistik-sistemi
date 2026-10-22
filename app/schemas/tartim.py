from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field, field_validator, model_validator

from app.db.enums import TicketStatus
from app.domain import rules
from app.schemas.common import DaraKilogram, IstekModeli, Kilogram, Oran
from app.schemas.finans import HakedisYaniti


class KaliteAnalizi(IstekModeli):
    numune_gram: Decimal = Field(Decimal("500"), gt=0, le=10000, decimal_places=2)
    catlak_orani: Oran = Field(description="Çatlak (ağzı açık) oranı %", examples=[90])
    randiman: Oran = Field(description="Randıman (iç oranı) %", examples=[58])
    bos_orani: Oran = Field(description="Boş oranı %", examples=[2])
    curuk_orani: Oran = Field(Decimal("0"), description="Çürük oranı %", examples=[1])
    nem_orani: Oran = Field(description="Nem oranı %", examples=[6])

    @model_validator(mode="after")
    def _defect_sum(self):
        if self.bos_orani + self.curuk_orani > 100:
            raise ValueError("Boş ve çürük oranlarının toplamı %100'ü aşamaz.")
        return self


class TartimGirisi(IstekModeli):
    uretici_id: int = Field(gt=0)
    plaka: str = Field(examples=["56 ABC 123"])
    sofor_adi: str | None = Field(None, max_length=120)
    brut_kg: Kilogram = Field(description="Dolu araç ağırlığı", examples=[4800])
    dara_kg: DaraKilogram = Field(description="Boş araç ağırlığı", examples=[3800])
    kalite: KaliteAnalizi
    silo_id: int | None = Field(None, description="Boşsa kaliteye uygun ilk silo otomatik seçilir")
    manuel_kalite_kodu: str | None = Field(None, max_length=10, description="Otomatik sınıflandırmayı geçersiz kılar")
    manuel_kalite_gerekcesi: str | None = Field(None, max_length=500)

    @field_validator("plaka")
    @classmethod
    def _plaka(cls, v: str) -> str:
        try:
            return rules.normalize_plate(v)
        except rules.BusinessRuleError as e:
            raise ValueError(f"{e} (örnek: 56 ABC 123)") from None

    @model_validator(mode="after")
    def _agirliklar(self):
        if self.dara_kg >= self.brut_kg:
            raise ValueError("Dara ağırlığı brüt ağırlıktan küçük olmalıdır (net kg pozitif olmalı).")
        if self.manuel_kalite_kodu and not self.manuel_kalite_gerekcesi:
            raise ValueError("Manuel kalite ataması için manuel_kalite_gerekcesi zorunludur.")
        return self


class KaliteSonucu(BaseModel):
    sinif_kodu: str
    sinif_adi: str
    catlak_orani: Decimal
    randiman: Decimal
    bos_orani: Decimal
    curuk_orani: Decimal
    nem_orani: Decimal
    manuel_atama: bool


class FiyatKilidi(BaseModel):
    fiyat_id: int
    birim_fiyat: Decimal
    fiyat_gecerlilik_baslangici: datetime
    fiyat_onay_zamani: datetime
    kilitlenme_zamani: datetime


class StokKaydi(BaseModel):
    silo_id: int
    silo_kodu: str
    miktar_kg: Decimal


class TartimYaniti(BaseModel):
    id: int
    fis_no: str
    lot_no: str | None
    durum: TicketStatus
    uretici_id: int
    uretici_adi: str
    plaka: str
    sofor_adi: str | None
    brut_kg: Decimal
    dara_kg: Decimal | None
    net_kg: Decimal | None
    tartim_zamani: datetime
    kalite: KaliteSonucu | None
    fiyat: FiyatKilidi | None = Field(description="Tartım anında kilitlenen (snapshot) fiyat")
    hakedis: HakedisYaniti | None
    stok: list[StokKaydi]
    uretici_guncel_bakiye: Decimal
