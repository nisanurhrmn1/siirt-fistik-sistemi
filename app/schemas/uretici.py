from datetime import datetime

from pydantic import Field, field_validator, model_validator

from app.db.enums import ProducerType
from app.domain import rules
from app.schemas.common import IstekModeli, YanitModeli


class BankaHesabiGirisi(IstekModeli):
    banka_adi: str = Field(min_length=2, max_length=80, examples=["Ziraat Bankası"])
    iban: str = Field(examples=["TR33 0006 1005 1978 6457 8413 26"],
                      description="Boşluklu veya boşluksuz; TR + 24 hane, kontrol basamağı doğrulanır")
    hesap_sahibi: str | None = Field(None, max_length=200, description="Boşsa üreticinin adı kullanılır")

    @field_validator("iban")
    @classmethod
    def _iban(cls, v: str) -> str:
        v = rules.normalize_iban(v)
        if not rules.validate_iban(v):
            raise ValueError("IBAN geçersiz: TR ile başlamalı, 26 karakter olmalı ve kontrol basamağı tutmalı.")
        return v


class UreticiGirisi(IstekModeli):
    tip: ProducerType = Field(ProducerType.INDIVIDUAL, description="INDIVIDUAL = gerçek kişi, COMPANY = tüzel kişi")
    tc_kimlik_no: str | None = Field(None, examples=["10000000146"], description="Gerçek kişide zorunlu")
    vergi_no: str | None = Field(None, examples=["1234567890"], description="Tüzel kişide zorunlu, 10 hane")
    vergi_dairesi: str | None = Field(None, max_length=80)
    ad_soyad: str = Field(min_length=3, max_length=200, description="Ad soyad veya firma ünvanı",
                          examples=["Ahmet Yılmaz"])
    telefon: str = Field(examples=["0532 123 45 67"])
    eposta: str | None = Field(None, max_length=120, pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
    adres: str | None = Field(None, max_length=500)
    ilce: str | None = Field(None, max_length=60, examples=["Kurtalan"])
    il: str = Field("Siirt", max_length=60)
    banka_hesabi: BankaHesabiGirisi | None = None
    notlar: str | None = Field(None, max_length=1000)

    @field_validator("tc_kimlik_no")
    @classmethod
    def _tc(cls, v: str | None) -> str | None:
        if v and not rules.validate_tc_kimlik(v):
            raise ValueError("TC Kimlik No geçersiz: 11 haneli olmalı, 0 ile başlamamalı ve kontrol basamakları tutmalı.")
        return v or None

    @field_validator("vergi_no")
    @classmethod
    def _vkn(cls, v: str | None) -> str | None:
        if v and not rules.validate_tax_number(v):
            raise ValueError("Vergi No geçersiz: 10 haneli rakamdan oluşmalı.")
        return v or None

    @field_validator("telefon")
    @classmethod
    def _telefon(cls, v: str) -> str:
        try:
            return rules.normalize_phone(v)
        except rules.BusinessRuleError as e:
            raise ValueError(str(e)) from None

    @model_validator(mode="after")
    def _kimlik_zorunlu(self):
        if self.tip == ProducerType.INDIVIDUAL and not self.tc_kimlik_no:
            raise ValueError("Gerçek kişi üretici için tc_kimlik_no zorunludur.")
        if self.tip == ProducerType.COMPANY and not self.vergi_no:
            raise ValueError("Tüzel kişi üretici için vergi_no zorunludur.")
        return self


class BankaHesabiYaniti(YanitModeli):
    id: int
    banka_adi: str = Field(validation_alias="bank_name")
    iban: str
    hesap_sahibi: str = Field(validation_alias="account_holder")
    varsayilan: bool = Field(validation_alias="is_default")
    aktif: bool = Field(validation_alias="is_active")


class UreticiYaniti(YanitModeli):
    id: int
    tip: ProducerType = Field(validation_alias="producer_type")
    tc_kimlik_no: str | None = Field(validation_alias="national_id")
    vergi_no: str | None = Field(validation_alias="tax_number")
    vergi_dairesi: str | None = Field(validation_alias="tax_office")
    ad_soyad: str = Field(validation_alias="full_name")
    telefon: str = Field(validation_alias="phone")
    eposta: str | None = Field(validation_alias="email")
    adres: str | None = Field(validation_alias="address")
    ilce: str | None = Field(validation_alias="district")
    il: str = Field(validation_alias="city")
    aktif: bool = Field(validation_alias="is_active")
    kayit_zamani: datetime = Field(validation_alias="created_at")
    banka_hesaplari: list[BankaHesabiYaniti] = Field(validation_alias="bank_accounts")
