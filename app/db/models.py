"""
Siirt Fıstığı Alım ve Üretici Ödeme Sistemi — SQLAlchemy 2.0 ORM modelleri.

Hedef veritabanı PostgreSQL'dir. Yalnızca PostgreSQL'de çalışan kısıtlar `pg_only(...)` ile
işaretlidir; SQLite'ta (birim testleri) atlanır, PostgreSQL'de CREATE TABLE'a eklenir.

Modüller:
  1. Kullanıcı & Üretici        users, producers, producer_bank_accounts
  2. Kalite & Fiyat             quality_grades, daily_prices
  3. Kantar & Kalite analizi    weigh_tickets, quality_inspections, document_sequences
  4. Hakediş, Avans, Ödeme      deduction_types, settlements, settlement_deductions,
                                advances, advance_offsets, payments, payment_allocations
  5. Cari hesap (defter)        producer_ledger_entries
  6. Depo / Stok                silos, lots, stock_movements
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Computed,
    Enum as SAEnum,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ExcludeConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship, validates

from .base import Base, TimestampMixin
from .enums import (
    AdvanceStatus,
    DeductionCalcMethod,
    LedgerEntryType,
    PaymentMethod,
    PriceStatus,
    ProducerType,
    SettlementStatus,
    StockMovementType,
    TicketStatus,
    UserRole,
)

# Para ve ölçü birimleri: float ASLA kullanılmaz.
Money = Numeric(14, 2)      # TL
Kg = Numeric(12, 2)         # kilogram
UnitPrice = Numeric(12, 2)  # TL/kg
Ratio = Numeric(5, 2)       # yüzde, 0.00–100.00
Rate = Numeric(12, 4)       # kesinti oranı (% veya TL/kg veya TL)
BigId = BigInteger().with_variant(Integer, "sqlite")  # SQLite'ta autoincrement için INTEGER


def enum_type(enum_cls, name: str) -> SAEnum:
    return SAEnum(
        enum_cls,
        name=name,
        native_enum=False,
        create_constraint=True,
        length=24,
        values_callable=lambda e: [m.value for m in e],
    )


def pg_only(constraint):
    return constraint.ddl_if(dialect="postgresql")


# ═══════════════════════════════════════════════════════════════════════════
# 1. KULLANICI & ÜRETİCİ
# ═══════════════════════════════════════════════════════════════════════════

class User(TimestampMixin, Base):
    """Sistem kullanıcıları — her kritik işlem (tartım, fiyat onayı, ödeme) kime ait izlenir."""
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(50), unique=True)
    full_name: Mapped[str] = mapped_column(String(120))
    role: Mapped[UserRole] = mapped_column(enum_type(UserRole, "user_role"))
    is_active: Mapped[bool] = mapped_column(default=True)


class Producer(TimestampMixin, Base):
    """Üretici (çiftçi veya firma)."""
    __tablename__ = "producers"

    id: Mapped[int] = mapped_column(primary_key=True)
    producer_type: Mapped[ProducerType] = mapped_column(enum_type(ProducerType, "producer_type"))
    national_id: Mapped[str | None] = mapped_column(String(11), unique=True)  # TC Kimlik No
    tax_number: Mapped[str | None] = mapped_column(String(10), unique=True)   # Vergi Kimlik No
    tax_office: Mapped[str | None] = mapped_column(String(80))
    full_name: Mapped[str] = mapped_column(String(200))                       # Ad Soyad / Ünvan
    phone: Mapped[str] = mapped_column(String(20))
    email: Mapped[str | None] = mapped_column(String(120))
    address: Mapped[str | None] = mapped_column(Text)
    district: Mapped[str | None] = mapped_column(String(60))
    city: Mapped[str] = mapped_column(String(60), default="Siirt")
    is_active: Mapped[bool] = mapped_column(default=True)
    notes: Mapped[str | None] = mapped_column(Text)

    bank_accounts: Mapped[list[ProducerBankAccount]] = relationship(back_populates="producer")
    weigh_tickets: Mapped[list[WeighTicket]] = relationship(back_populates="producer")

    __table_args__ = (
        CheckConstraint(
            "(producer_type = 'INDIVIDUAL' AND national_id IS NOT NULL) OR "
            "(producer_type = 'COMPANY' AND tax_number IS NOT NULL)",
            name="identity_required",
        ),
        CheckConstraint("national_id IS NULL OR length(national_id) = 11", name="national_id_len"),
        CheckConstraint("tax_number IS NULL OR length(tax_number) = 10", name="tax_number_len"),
    )


class ProducerBankAccount(TimestampMixin, Base):
    """Üreticinin ödeme alacağı hesaplar. Üretici başına en fazla bir varsayılan hesap."""
    __tablename__ = "producer_bank_accounts"

    id: Mapped[int] = mapped_column(primary_key=True)
    producer_id: Mapped[int] = mapped_column(ForeignKey("producers.id", ondelete="RESTRICT"), index=True)
    bank_name: Mapped[str] = mapped_column(String(80))
    iban: Mapped[str] = mapped_column(String(26))             # TR + 24 hane, boşluksuz
    account_holder: Mapped[str] = mapped_column(String(200))
    is_default: Mapped[bool] = mapped_column(default=False)
    is_active: Mapped[bool] = mapped_column(default=True)

    producer: Mapped[Producer] = relationship(back_populates="bank_accounts")

    __table_args__ = (
        UniqueConstraint("producer_id", "iban", name="uq_producer_bank_accounts_producer_iban"),
        CheckConstraint("length(iban) = 26 AND substr(iban, 1, 2) = 'TR'", name="iban_format"),
        Index(
            "uq_producer_bank_accounts_one_default",
            "producer_id",
            unique=True,
            postgresql_where=text("is_default = true"),
            sqlite_where=text("is_default = 1"),
        ),
    )


# ═══════════════════════════════════════════════════════════════════════════
# 2. KALİTE SINIFI & GÜNLÜK FİYAT
# ═══════════════════════════════════════════════════════════════════════════

class QualityGrade(TimestampMixin, Base):
    """Kalite sınıfı ve otomatik sınıflandırma eşikleri (NULL = o kriter uygulanmaz).

    Sınıflar `priority` sırasıyla (en iyi → en kötü) denenir; tüm eşikleri sağlayan ilk sınıf atanır.
    En son sınıf eşiksiz olmalıdır (her şeyi yakalayan "ıskarta" sınıfı).
    """
    __tablename__ = "quality_grades"

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(10), unique=True)   # A, B, C, ISK
    name: Mapped[str] = mapped_column(String(60))
    description: Mapped[str | None] = mapped_column(Text)
    priority: Mapped[int] = mapped_column(unique=True)
    min_split_ratio: Mapped[Decimal | None] = mapped_column(Ratio)     # Çatlak (ağzı açık) — yüksek iyi
    min_yield_ratio: Mapped[Decimal | None] = mapped_column(Ratio)     # Randıman (iç oranı)
    max_defect_ratio: Mapped[Decimal | None] = mapped_column(Ratio)    # Boş + çürük
    max_moisture_ratio: Mapped[Decimal | None] = mapped_column(Ratio)  # Nem
    is_active: Mapped[bool] = mapped_column(default=True)


class DailyPrice(TimestampMixin, Base):
    """Kalite sınıfı bazında, zaman aralığıyla geçerli taban fiyat.

    Kurallar:
      * Onaylı (APPROVED) bir fiyatın tutarı/sınıfı/başlangıcı değiştirilemez. Gün içi fiyat
        değişikliği = eski kaydın valid_to'su kapatılır + yeni kayıt açılır.
      * Aynı sınıf için onaylı fiyat aralıkları çakışamaz (PostgreSQL EXCLUDE kısıtı).
      * Hatalı fiyat silinmez, REVOKED yapılır; revoked_at'ten önce donmuş snapshot'lar korunur.
    """
    __tablename__ = "daily_prices"

    id: Mapped[int] = mapped_column(primary_key=True)
    quality_grade_id: Mapped[int] = mapped_column(ForeignKey("quality_grades.id"))
    price_per_kg: Mapped[Decimal] = mapped_column(UnitPrice)
    currency: Mapped[str] = mapped_column(String(3), default="TRY")
    valid_from: Mapped[datetime]
    valid_to: Mapped[datetime | None]  # NULL = yeni fiyat girilene kadar açık uçlu; aralık [from, to)
    status: Mapped[PriceStatus] = mapped_column(enum_type(PriceStatus, "price_status"), default=PriceStatus.DRAFT)
    created_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    approved_by_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    approved_at: Mapped[datetime | None]
    revoked_at: Mapped[datetime | None]
    note: Mapped[str | None] = mapped_column(Text)

    quality_grade: Mapped[QualityGrade] = relationship()

    __table_args__ = (
        CheckConstraint("price_per_kg > 0", name="price_positive"),
        CheckConstraint("valid_to IS NULL OR valid_to > valid_from", name="valid_range"),
        CheckConstraint(
            "status = 'DRAFT' OR (approved_by_id IS NOT NULL AND approved_at IS NOT NULL)",
            name="approval_fields",
        ),
        CheckConstraint("(status = 'REVOKED') = (revoked_at IS NOT NULL)", name="revoked_fields"),
        Index("ix_daily_prices_lookup", "quality_grade_id", "status", "valid_from"),
    )

    @validates("price_per_kg", "quality_grade_id", "valid_from", "currency")
    def _freeze_after_approval(self, key, value):
        current = getattr(self, key, None)
        if self.status in (PriceStatus.APPROVED, PriceStatus.REVOKED) and current is not None and current != value:
            raise ValueError(f"Onaylanmış fiyatın '{key}' alanı değiştirilemez; yeni fiyat kaydı açın.")
        return value


# Aynı sınıf için onaylı fiyat aralıkları çakışamaz. (btree_gist eklentisi gerekir.)
DailyPrice.__table__.append_constraint(
    pg_only(
        ExcludeConstraint(
            (DailyPrice.quality_grade_id, "="),
            (
                func.tstzrange(
                    DailyPrice.valid_from,
                    func.coalesce(DailyPrice.valid_to, text("'infinity'::timestamptz")),
                    text("'[)'"),
                ),
                "&&",
            ),
            name="ex_daily_prices_no_overlap",
            using="gist",
            where=text("status = 'APPROVED'"),
        )
    )
)


# ═══════════════════════════════════════════════════════════════════════════
# 3. KANTAR & KALİTE ANALİZİ
# ═══════════════════════════════════════════════════════════════════════════

class DocumentSequence(Base):
    """Yıl bazlı belge numarası sayacı (KF-2026-000001, LOT-2026-000001, HK-..., AV-..., OD-...).
    Satır kilidi (SELECT ... FOR UPDATE) ile artırılır; boşluksuz, benzersiz numara üretir."""
    __tablename__ = "document_sequences"

    doc_type: Mapped[str] = mapped_column(String(10), primary_key=True)
    year: Mapped[int] = mapped_column(primary_key=True)
    last_value: Mapped[int] = mapped_column(default=0)


class WeighTicket(TimestampMixin, Base):
    """Kantar fişi = tek bir teslimat. Üretici aynı gün sınırsız teslimat yapabilir; her biri ayrı fiş.

    FİYAT SNAPSHOT: daily_price_id + unit_price, kalite sınıfı belirlendiğinde
    `gross_weighed_at` (tartım anı) itibarıyla geçerli onaylı fiyattan kopyalanır ve bir daha
    değişmez (ORM validator + PostgreSQL trigger). Sonradan fiyat değişse bile hakediş bu değerle hesaplanır.
    """
    __tablename__ = "weigh_tickets"

    id: Mapped[int] = mapped_column(primary_key=True)
    ticket_no: Mapped[str] = mapped_column(String(20), unique=True)
    producer_id: Mapped[int] = mapped_column(ForeignKey("producers.id", ondelete="RESTRICT"))
    vehicle_plate: Mapped[str] = mapped_column(String(12))  # Normalize: büyük harf, boşluksuz (56ABC123)
    driver_name: Mapped[str | None] = mapped_column(String(120))

    gross_kg: Mapped[Decimal] = mapped_column(Kg)         # Dolu (giriş) tartım
    tare_kg: Mapped[Decimal | None] = mapped_column(Kg)   # Boş (çıkış) tartım = dara
    net_kg: Mapped[Decimal | None] = mapped_column(Kg, Computed("gross_kg - tare_kg", persisted=True))
    gross_weighed_at: Mapped[datetime]                    # Fiyat referans anı
    tare_weighed_at: Mapped[datetime | None]
    weighed_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    status: Mapped[TicketStatus] = mapped_column(
        enum_type(TicketStatus, "ticket_status"), default=TicketStatus.GROSS_WEIGHED
    )

    # --- Fiyat snapshot (dondurulmuş) ---
    daily_price_id: Mapped[int | None] = mapped_column(ForeignKey("daily_prices.id"))
    unit_price: Mapped[Decimal | None] = mapped_column(UnitPrice)
    priced_at: Mapped[datetime | None]

    cancel_reason: Mapped[str | None] = mapped_column(Text)

    producer: Mapped[Producer] = relationship(back_populates="weigh_tickets")
    inspection: Mapped[QualityInspection | None] = relationship(back_populates="ticket")
    daily_price: Mapped[DailyPrice | None] = relationship()
    lot: Mapped[Lot | None] = relationship(back_populates="ticket")
    settlement: Mapped[Settlement | None] = relationship(back_populates="ticket")

    __table_args__ = (
        CheckConstraint("gross_kg > 0", name="gross_positive"),
        CheckConstraint("tare_kg IS NULL OR (tare_kg >= 0 AND tare_kg < gross_kg)", name="tare_range"),
        CheckConstraint(
            "tare_weighed_at IS NULL OR tare_weighed_at >= gross_weighed_at", name="tare_after_gross"
        ),
        CheckConstraint(
            "status IN ('GROSS_WEIGHED', 'CANCELLED') OR tare_kg IS NOT NULL", name="tare_required"
        ),
        CheckConstraint(
            "(daily_price_id IS NULL AND unit_price IS NULL AND priced_at IS NULL) OR "
            "(daily_price_id IS NOT NULL AND unit_price IS NOT NULL AND priced_at IS NOT NULL)",
            name="price_snapshot_complete",
        ),
        CheckConstraint(
            "status NOT IN ('GRADED', 'SETTLED') OR daily_price_id IS NOT NULL", name="priced_when_graded"
        ),
        CheckConstraint("status <> 'CANCELLED' OR cancel_reason IS NOT NULL", name="cancel_reason"),
        Index("ix_weigh_tickets_producer_time", "producer_id", "gross_weighed_at"),
        Index("ix_weigh_tickets_time", "gross_weighed_at"),
    )

    @validates("daily_price_id", "unit_price")
    def _freeze_price_snapshot(self, key, value):
        current = getattr(self, key, None)
        if current is not None and current != value:
            raise ValueError("Teslimatın fiyat snapshot'ı dondurulmuştur, değiştirilemez.")
        return value


class QualityInspection(TimestampMixin, Base):
    """Teslimattan alınan numunenin laboratuvar analizi (teslimat başına bir analiz)."""
    __tablename__ = "quality_inspections"

    id: Mapped[int] = mapped_column(primary_key=True)
    ticket_id: Mapped[int] = mapped_column(ForeignKey("weigh_tickets.id", ondelete="RESTRICT"), unique=True)
    sample_weight_g: Mapped[Decimal] = mapped_column(Numeric(8, 2))
    split_ratio: Mapped[Decimal] = mapped_column(Ratio)     # Çatlak oranı %
    yield_ratio: Mapped[Decimal] = mapped_column(Ratio)     # Randıman %
    empty_ratio: Mapped[Decimal] = mapped_column(Ratio)     # Boş oranı %
    rotten_ratio: Mapped[Decimal] = mapped_column(Ratio)    # Çürük oranı %
    moisture_ratio: Mapped[Decimal] = mapped_column(Ratio)  # Nem %
    quality_grade_id: Mapped[int] = mapped_column(ForeignKey("quality_grades.id"))
    is_manual_override: Mapped[bool] = mapped_column(default=False)
    override_reason: Mapped[str | None] = mapped_column(Text)
    inspected_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    inspected_at: Mapped[datetime]

    ticket: Mapped[WeighTicket] = relationship(back_populates="inspection")
    quality_grade: Mapped[QualityGrade] = relationship()

    __table_args__ = (
        CheckConstraint("sample_weight_g > 0", name="sample_positive"),
        CheckConstraint(
            "split_ratio BETWEEN 0 AND 100 AND yield_ratio BETWEEN 0 AND 100 AND "
            "empty_ratio BETWEEN 0 AND 100 AND rotten_ratio BETWEEN 0 AND 100 AND "
            "moisture_ratio BETWEEN 0 AND 100",
            name="ratios_range",
        ),
        CheckConstraint("empty_ratio + rotten_ratio <= 100", name="defect_sum"),
        CheckConstraint("NOT is_manual_override OR override_reason IS NOT NULL", name="override_reason"),
    )


# ═══════════════════════════════════════════════════════════════════════════
# 4. HAKEDİŞ, KESİNTİ, AVANS, ÖDEME
# ═══════════════════════════════════════════════════════════════════════════

class DeductionType(TimestampMixin, Base):
    """Kesinti tanımı (stopaj, borsa tescil, komisyon, hamaliye...). Oran değişirse kayıt
    güncellenebilir; geçmiş hakedişler oranı settlement_deductions'ta snapshot olarak saklar."""
    __tablename__ = "deduction_types"

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(20), unique=True)
    name: Mapped[str] = mapped_column(String(100))
    calc_method: Mapped[DeductionCalcMethod] = mapped_column(enum_type(DeductionCalcMethod, "calc_method"))
    rate: Mapped[Decimal] = mapped_column(Rate)
    sort_order: Mapped[int] = mapped_column(default=0)
    is_active: Mapped[bool] = mapped_column(default=True)

    __table_args__ = (
        CheckConstraint("rate >= 0", name="rate_non_negative"),
        CheckConstraint("calc_method <> 'PERCENT' OR rate <= 100", name="percent_max"),
    )


class Settlement(TimestampMixin, Base):
    """Hakediş — bir teslimatın parasal sonucu (teslimat başına bir hakediş).

        gross_amount         = ROUND(net_kg × unit_price, 2)
        deductions_total     = Σ settlement_deductions.amount          (≤ gross_amount)
        advance_offset_total = Σ advance_offsets.amount                (FIFO avans mahsubu)
        net_payable          = gross_amount − deductions_total − advance_offset_total   (≥ 0)

    Avans, teslimatın kesinti sonrası tutarından büyükse: mahsup bu tutarla sınırlanır, net_payable = 0
    olur, kalan avans açık kalır ve üreticinin cari bakiyesi (ledger) eksiye düşer. Sonraki
    teslimatların hakedişinde kalan avans otomatik mahsup edilir.
    """
    __tablename__ = "settlements"

    id: Mapped[int] = mapped_column(primary_key=True)
    settlement_no: Mapped[str] = mapped_column(String(20), unique=True)
    ticket_id: Mapped[int] = mapped_column(ForeignKey("weigh_tickets.id", ondelete="RESTRICT"), unique=True)
    producer_id: Mapped[int] = mapped_column(ForeignKey("producers.id", ondelete="RESTRICT"), index=True)
    net_kg: Mapped[Decimal] = mapped_column(Kg)                # snapshot
    unit_price: Mapped[Decimal] = mapped_column(UnitPrice)     # snapshot (= weigh_tickets.unit_price)
    gross_amount: Mapped[Decimal] = mapped_column(Money)
    deductions_total: Mapped[Decimal] = mapped_column(Money)
    advance_offset_total: Mapped[Decimal] = mapped_column(Money)
    net_payable: Mapped[Decimal] = mapped_column(Money)
    paid_amount: Mapped[Decimal] = mapped_column(Money, default=Decimal("0"))
    status: Mapped[SettlementStatus] = mapped_column(enum_type(SettlementStatus, "settlement_status"))
    created_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))

    ticket: Mapped[WeighTicket] = relationship(back_populates="settlement")
    deductions: Mapped[list[SettlementDeduction]] = relationship(back_populates="settlement")
    advance_offsets: Mapped[list[AdvanceOffset]] = relationship(back_populates="settlement")

    __table_args__ = (
        CheckConstraint("net_kg > 0 AND unit_price > 0", name="qty_price_positive"),
        CheckConstraint("deductions_total >= 0 AND deductions_total <= gross_amount", name="deductions_range"),
        CheckConstraint("advance_offset_total >= 0", name="offset_non_negative"),
        CheckConstraint("net_payable >= 0", name="net_non_negative"),
        CheckConstraint("paid_amount >= 0 AND paid_amount <= net_payable", name="paid_range"),
        # Ondalık eşitlikleri yalnızca gerçek NUMERIC tipli PostgreSQL'de denetlenir.
        pg_only(CheckConstraint("gross_amount = ROUND(net_kg * unit_price, 2)", name="gross_formula")),
        pg_only(
            CheckConstraint(
                "net_payable = gross_amount - deductions_total - advance_offset_total", name="net_formula"
            )
        ),
    )


class SettlementDeduction(Base):
    """Hakedişe uygulanan her kesintinin oran ve tutar snapshot'ı."""
    __tablename__ = "settlement_deductions"

    id: Mapped[int] = mapped_column(primary_key=True)
    settlement_id: Mapped[int] = mapped_column(ForeignKey("settlements.id", ondelete="CASCADE"))
    deduction_type_id: Mapped[int] = mapped_column(ForeignKey("deduction_types.id"))
    calc_method: Mapped[DeductionCalcMethod] = mapped_column(enum_type(DeductionCalcMethod, "calc_method"))
    rate: Mapped[Decimal] = mapped_column(Rate)
    base_amount: Mapped[Decimal] = mapped_column(Money)  # Hesaplamanın dayandığı tutar veya kg
    amount: Mapped[Decimal] = mapped_column(Money)

    settlement: Mapped[Settlement] = relationship(back_populates="deductions")
    deduction_type: Mapped[DeductionType] = relationship()

    __table_args__ = (
        UniqueConstraint("settlement_id", "deduction_type_id", name="uq_settlement_deductions_type"),
        CheckConstraint("amount >= 0", name="amount_non_negative"),
    )


class Advance(TimestampMixin, Base):
    """Üreticiye teslimattan önce verilen avans. `offset_amount` mahsup edilen kısımdır."""
    __tablename__ = "advances"

    id: Mapped[int] = mapped_column(primary_key=True)
    advance_no: Mapped[str] = mapped_column(String(20), unique=True)
    producer_id: Mapped[int] = mapped_column(ForeignKey("producers.id", ondelete="RESTRICT"), index=True)
    amount: Mapped[Decimal] = mapped_column(Money)
    offset_amount: Mapped[Decimal] = mapped_column(Money, default=Decimal("0"))
    status: Mapped[AdvanceStatus] = mapped_column(enum_type(AdvanceStatus, "advance_status"), default=AdvanceStatus.OPEN)
    given_at: Mapped[datetime]
    method: Mapped[PaymentMethod] = mapped_column(enum_type(PaymentMethod, "payment_method"))
    bank_account_id: Mapped[int | None] = mapped_column(ForeignKey("producer_bank_accounts.id"))
    description: Mapped[str | None] = mapped_column(Text)
    created_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))

    offsets: Mapped[list[AdvanceOffset]] = relationship(back_populates="advance")

    __table_args__ = (
        CheckConstraint("amount > 0", name="amount_positive"),
        CheckConstraint("offset_amount >= 0 AND offset_amount <= amount", name="offset_range"),
        CheckConstraint("method <> 'BANK_TRANSFER' OR bank_account_id IS NOT NULL", name="bank_required"),
        Index("ix_advances_open_fifo", "producer_id", "given_at"),
    )


class AdvanceOffset(Base):
    """Avans mahsubu: hangi avansın hangi hakedişten ne kadar düşüldüğü (FIFO)."""
    __tablename__ = "advance_offsets"

    id: Mapped[int] = mapped_column(primary_key=True)
    advance_id: Mapped[int] = mapped_column(ForeignKey("advances.id", ondelete="RESTRICT"))
    settlement_id: Mapped[int] = mapped_column(ForeignKey("settlements.id", ondelete="RESTRICT"))
    amount: Mapped[Decimal] = mapped_column(Money)
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())

    advance: Mapped[Advance] = relationship(back_populates="offsets")
    settlement: Mapped[Settlement] = relationship(back_populates="advance_offsets")

    __table_args__ = (
        UniqueConstraint("advance_id", "settlement_id", name="uq_advance_offsets_pair"),
        CheckConstraint("amount > 0", name="amount_positive"),
    )


class Payment(TimestampMixin, Base):
    """Üreticiye yapılan ödeme. Bir ödeme birden fazla hakedişi (parçalı) kapatabilir."""
    __tablename__ = "payments"

    id: Mapped[int] = mapped_column(primary_key=True)
    payment_no: Mapped[str] = mapped_column(String(20), unique=True)
    producer_id: Mapped[int] = mapped_column(ForeignKey("producers.id", ondelete="RESTRICT"), index=True)
    bank_account_id: Mapped[int | None] = mapped_column(ForeignKey("producer_bank_accounts.id"))
    amount: Mapped[Decimal] = mapped_column(Money)
    method: Mapped[PaymentMethod] = mapped_column(enum_type(PaymentMethod, "payment_method"))
    paid_at: Mapped[datetime]
    reference_no: Mapped[str | None] = mapped_column(String(60))  # Dekont / EFT referansı
    description: Mapped[str | None] = mapped_column(Text)
    created_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))

    allocations: Mapped[list[PaymentAllocation]] = relationship(back_populates="payment")

    __table_args__ = (
        CheckConstraint("amount > 0", name="amount_positive"),
        CheckConstraint("method <> 'BANK_TRANSFER' OR bank_account_id IS NOT NULL", name="bank_required"),
    )


class PaymentAllocation(Base):
    """Ödemenin hakedişlere dağıtımı → hakedişin parçalı/tam ödendi durumu buradan gelir."""
    __tablename__ = "payment_allocations"

    id: Mapped[int] = mapped_column(primary_key=True)
    payment_id: Mapped[int] = mapped_column(ForeignKey("payments.id", ondelete="RESTRICT"))
    settlement_id: Mapped[int] = mapped_column(ForeignKey("settlements.id", ondelete="RESTRICT"), index=True)
    amount: Mapped[Decimal] = mapped_column(Money)

    payment: Mapped[Payment] = relationship(back_populates="allocations")
    settlement: Mapped[Settlement] = relationship()

    __table_args__ = (
        UniqueConstraint("payment_id", "settlement_id", name="uq_payment_allocations_pair"),
        CheckConstraint("amount > 0", name="amount_positive"),
    )


# ═══════════════════════════════════════════════════════════════════════════
# 5. CARİ HESAP DEFTERİ (tek doğruluk kaynağı)
# ═══════════════════════════════════════════════════════════════════════════

class ProducerLedgerEntry(Base):
    """Üretici cari hesap hareketi — yalnızca ekleme yapılır (UPDATE/DELETE yasak, düzeltme = REVERSAL).

    İşaret: (+) üreticinin alacağı, (−) üreticinin borcu / ona ödenen.
    Bakiye = Σ amount. Negatif bakiye = üretici, teslimatlarla henüz kapanmamış avans borcu taşıyor.
    """
    __tablename__ = "producer_ledger_entries"

    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    producer_id: Mapped[int] = mapped_column(ForeignKey("producers.id", ondelete="RESTRICT"))
    entry_type: Mapped[LedgerEntryType] = mapped_column(enum_type(LedgerEntryType, "ledger_entry_type"))
    amount: Mapped[Decimal] = mapped_column(Money)
    entry_at: Mapped[datetime]
    description: Mapped[str | None] = mapped_column(String(255))
    ticket_id: Mapped[int | None] = mapped_column(ForeignKey("weigh_tickets.id"))
    settlement_id: Mapped[int | None] = mapped_column(ForeignKey("settlements.id"))
    settlement_deduction_id: Mapped[int | None] = mapped_column(ForeignKey("settlement_deductions.id"))
    advance_id: Mapped[int | None] = mapped_column(ForeignKey("advances.id"))
    payment_id: Mapped[int | None] = mapped_column(ForeignKey("payments.id"))
    reversal_of_id: Mapped[int | None] = mapped_column(ForeignKey("producer_ledger_entries.id"), unique=True)
    created_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())

    __table_args__ = (
        CheckConstraint(
            "(entry_type = 'DELIVERY' AND amount > 0 AND settlement_id IS NOT NULL) OR "
            "(entry_type = 'DEDUCTION' AND amount < 0 AND settlement_deduction_id IS NOT NULL) OR "
            "(entry_type = 'ADVANCE' AND amount < 0 AND advance_id IS NOT NULL) OR "
            "(entry_type = 'PAYMENT' AND amount < 0 AND payment_id IS NOT NULL) OR "
            "(entry_type = 'ADJUSTMENT' AND amount <> 0) OR "
            "(entry_type = 'REVERSAL' AND amount <> 0 AND reversal_of_id IS NOT NULL)",
            name="type_sign_ref",
        ),
        Index("ix_ledger_producer_time", "producer_id", "entry_at"),
    )


# ═══════════════════════════════════════════════════════════════════════════
# 6. DEPO / STOK
# ═══════════════════════════════════════════════════════════════════════════

class Silo(TimestampMixin, Base):
    """Silo / depo bölmesi. `dedicated_grade_id` doluysa yalnızca o kalite sınıfı girebilir."""
    __tablename__ = "silos"

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(20), unique=True)
    name: Mapped[str] = mapped_column(String(80))
    location: Mapped[str | None] = mapped_column(String(120))
    capacity_kg: Mapped[Decimal] = mapped_column(Kg)
    dedicated_grade_id: Mapped[int | None] = mapped_column(ForeignKey("quality_grades.id"))
    is_active: Mapped[bool] = mapped_column(default=True)

    __table_args__ = (CheckConstraint("capacity_kg > 0", name="capacity_positive"),)


class Lot(TimestampMixin, Base):
    """Parti / lot — her teslimat kalite sınıfı belirlenince tek bir lot olarak stoğa girer."""
    __tablename__ = "lots"

    id: Mapped[int] = mapped_column(primary_key=True)
    lot_no: Mapped[str] = mapped_column(String(20), unique=True)
    ticket_id: Mapped[int] = mapped_column(ForeignKey("weigh_tickets.id", ondelete="RESTRICT"), unique=True)
    quality_grade_id: Mapped[int] = mapped_column(ForeignKey("quality_grades.id"))
    initial_kg: Mapped[Decimal] = mapped_column(Kg)

    ticket: Mapped[WeighTicket] = relationship(back_populates="lot")
    quality_grade: Mapped[QualityGrade] = relationship()

    __table_args__ = (CheckConstraint("initial_kg > 0", name="initial_positive"),)


class StockMovement(Base):
    """Stok hareketi (işaretli miktar). Silo/lot stoğu = Σ quantity_kg."""
    __tablename__ = "stock_movements"

    id: Mapped[int] = mapped_column(BigId, primary_key=True)
    lot_id: Mapped[int] = mapped_column(ForeignKey("lots.id", ondelete="RESTRICT"))
    silo_id: Mapped[int] = mapped_column(ForeignKey("silos.id", ondelete="RESTRICT"))
    movement_type: Mapped[StockMovementType] = mapped_column(enum_type(StockMovementType, "stock_movement_type"))
    quantity_kg: Mapped[Decimal] = mapped_column(Kg)
    moved_at: Mapped[datetime]
    transfer_group: Mapped[str | None] = mapped_column(String(36))  # TRANSFER_OUT/IN çiftini bağlar
    note: Mapped[str | None] = mapped_column(Text)
    created_by_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())

    lot: Mapped[Lot] = relationship()
    silo: Mapped[Silo] = relationship()

    __table_args__ = (
        CheckConstraint(
            "(movement_type IN ('RECEIPT', 'TRANSFER_IN') AND quantity_kg > 0) OR "
            "(movement_type IN ('DISPATCH', 'TRANSFER_OUT') AND quantity_kg < 0) OR "
            "(movement_type = 'ADJUSTMENT' AND quantity_kg <> 0)",
            name="type_sign",
        ),
        CheckConstraint(
            "movement_type NOT IN ('TRANSFER_IN', 'TRANSFER_OUT') OR transfer_group IS NOT NULL",
            name="transfer_group_required",
        ),
        Index("ix_stock_movements_silo", "silo_id", "lot_id"),
        Index("ix_stock_movements_lot", "lot_id"),
    )
