from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field

from app.db.enums import AdvanceStatus, DeductionCalcMethod, PaymentMethod, SettlementStatus
from app.schemas.common import IstekModeli, Para


# ─── Hakediş ───
class KesintiYaniti(BaseModel):
    kod: str
    ad: str
    hesaplama: DeductionCalcMethod
    oran: Decimal = Field(description="PERCENT: %, PER_KG: TL/kg, FIXED: TL")
    matrah: Decimal
    tutar: Decimal


class AvansMahsubuYaniti(BaseModel):
    avans_id: int
    avans_no: str
    tutar: Decimal


class HakedisYaniti(BaseModel):
    id: int
    hakedis_no: str
    tartim_id: int
    fis_no: str
    uretici_id: int
    net_kg: Decimal
    birim_fiyat: Decimal
    brut_tutar: Decimal = Field(description="net_kg × birim_fiyat")
    kesintiler: list[KesintiYaniti]
    kesinti_toplami: Decimal
    avans_mahsuplari: list[AvansMahsubuYaniti]
    avans_mahsubu_toplami: Decimal
    net_odenecek: Decimal = Field(description="brüt − kesintiler − avans mahsubu")
    odenen: Decimal
    kalan: Decimal
    durum: SettlementStatus
    olusturma_zamani: datetime


# ─── Avans ───
class AvansGirisi(IstekModeli):
    uretici_id: int = Field(gt=0)
    tutar: Para
    odeme_yontemi: PaymentMethod = PaymentMethod.BANK_TRANSFER
    banka_hesap_id: int | None = Field(None, description="Boşsa üreticinin varsayılan hesabı (havalede)")
    aciklama: str | None = Field(None, max_length=500)


class AvansYaniti(BaseModel):
    id: int
    avans_no: str
    uretici_id: int
    tutar: Decimal
    mahsup_edilen: Decimal
    kalan: Decimal
    durum: AdvanceStatus
    odeme_yontemi: PaymentMethod
    verilis_zamani: datetime
    aciklama: str | None
    uretici_guncel_bakiye: Decimal = Field(description="(+) üreticiye borçluyuz, (−) üretici bize borçlu")


# ─── Ödeme ───
class OdemeGirisi(IstekModeli):
    tutar: Para | None = Field(None, description="Boş bırakılırsa hakedişin kalan tutarının tamamı ödenir")
    odeme_yontemi: PaymentMethod = PaymentMethod.BANK_TRANSFER
    banka_hesap_id: int | None = Field(None, description="Boşsa üreticinin varsayılan hesabı (havalede)")
    referans_no: str | None = Field(None, max_length=60, description="Dekont / EFT referansı")
    aciklama: str | None = Field(None, max_length=500)


class OdemeYaniti(BaseModel):
    id: int
    odeme_no: str
    tutar: Decimal
    odeme_yontemi: PaymentMethod
    banka_hesap_id: int | None
    iban: str | None
    odeme_zamani: datetime
    referans_no: str | None


class OdemeSonucu(BaseModel):
    mesaj: str
    bu_islemde_mahsup_edilen_avans: Decimal = Field(
        description="Hakediş oluştuktan sonra verilmiş avanslardan bu işlemde düşülen tutar")
    odeme: OdemeYaniti | None = Field(description="Kalan tutarın tamamı avansa mahsup edildiyse null")
    hakedis: HakedisYaniti
    uretici_guncel_bakiye: Decimal


# ─── Ekstre ───
class EkstreOzeti(BaseModel):
    bakiye: Decimal
    bakiye_durumu: str = Field(description="ALACAKLI: üreticiye borçluyuz · BORCLU: üretici avans borçlu · KAPALI")
    toplam_teslimat_kg: Decimal
    toplam_teslimat_tutari: Decimal
    toplam_kesinti: Decimal
    toplam_verilen_avans: Decimal
    toplam_odeme: Decimal
    acik_avans_bakiyesi: Decimal
    odenmemis_hakedis_toplami: Decimal
    odenebilir_tutar: Decimal


class EkstreTeslimat(BaseModel):
    tartim_id: int
    fis_no: str
    lot_no: str | None
    tarih: datetime
    net_kg: Decimal
    kalite_kodu: str | None
    randiman: Decimal | None
    birim_fiyat: Decimal | None
    hakedis_id: int | None
    brut_tutar: Decimal | None
    kesinti: Decimal | None
    avans_mahsubu: Decimal | None
    net_odenecek: Decimal | None
    odenen: Decimal | None
    kalan: Decimal | None
    hakedis_durumu: SettlementStatus | None


class EkstreAvans(BaseModel):
    id: int
    avans_no: str
    tarih: datetime
    tutar: Decimal
    mahsup_edilen: Decimal
    kalan: Decimal
    durum: AdvanceStatus


class EkstreHareket(BaseModel):
    id: int
    tarih: datetime
    tur: str
    aciklama: str | None
    alacak: Decimal = Field(description="Üreticinin lehine (+)")
    borc: Decimal = Field(description="Üreticinin aleyhine (−)")
    bakiye: Decimal = Field(description="Yürüyen bakiye")


class UreticiKisa(BaseModel):
    id: int
    ad_soyad: str
    tc_kimlik_no: str | None
    vergi_no: str | None
    telefon: str


class UreticiEkstre(BaseModel):
    uretici: UreticiKisa
    donem_baslangic: datetime | None
    donem_bitis: datetime | None
    ozet: EkstreOzeti = Field(description="Tüm zamanlar")
    devreden_bakiye: Decimal = Field(description="Dönem başından önceki bakiye")
    teslimatlar: list[EkstreTeslimat]
    acik_avanslar: list[EkstreAvans]
    hareketler: list[EkstreHareket]
