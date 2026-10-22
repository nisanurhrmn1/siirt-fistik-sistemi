from datetime import date, datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field


# ─── Depo ───
class KaliteStokPayi(BaseModel):
    kalite_kodu: str
    stok_kg: Decimal
    lot_sayisi: int


class SiloStok(BaseModel):
    silo_id: int
    silo_kodu: str
    silo_adi: str
    adanmis_kalite: str | None
    kapasite_kg: Decimal
    stok_kg: Decimal
    bos_kapasite_kg: Decimal
    doluluk_orani: Decimal = Field(description="%")
    kaliteler: list[KaliteStokPayi]


class KaliteStok(BaseModel):
    kalite_kodu: str
    kalite_adi: str
    stok_kg: Decimal
    lot_sayisi: int
    silolar: list[str]


class StokDurumu(BaseModel):
    gruplama: Literal["silo", "kalite"]
    sorgu_zamani: datetime
    toplam_stok_kg: Decimal
    silolar: list[SiloStok] | None = None
    kaliteler: list[KaliteStok] | None = None


# ─── Günlük özet ───
class KaliteKirilimi(BaseModel):
    kalite_kodu: str
    teslimat_sayisi: int
    net_kg: Decimal
    brut_tutar: Decimal
    ortalama_birim_fiyat: Decimal
    ortalama_randiman: Decimal


class GunlukOzet(BaseModel):
    tarih: date
    teslimat_sayisi: int
    uretici_sayisi: int
    toplam_net_kg: Decimal
    ortalama_randiman: Decimal | None = Field(description="Net kg ağırlıklı ortalama %")
    ortalama_birim_fiyat: Decimal | None = Field(description="Net kg ağırlıklı TL/kg")
    brut_alim_tutari: Decimal
    toplam_kesinti: Decimal
    toplam_avans_mahsubu: Decimal
    net_hakedis_toplami: Decimal
    odenen_toplam: Decimal = Field(description="O gün yapılan ödemeler (hangi teslimata ait olursa olsun)")
    odeme_sayisi: int
    verilen_avans_toplami: Decimal
    kalite_kirilimi: list[KaliteKirilimi]
