"""Alan değer kümeleri. Veritabanında VARCHAR + CHECK olarak saklanır (native enum değil),
böylece yeni değer eklemek ALTER TYPE gerektirmez ve SQLite ile de çalışır."""
import enum


class ProducerType(str, enum.Enum):
    INDIVIDUAL = "INDIVIDUAL"  # Gerçek kişi (TC Kimlik No zorunlu)
    COMPANY = "COMPANY"        # Tüzel kişi (Vergi No zorunlu)


class UserRole(str, enum.Enum):
    ADMIN = "ADMIN"
    SCALE_OPERATOR = "SCALE_OPERATOR"  # Kantar
    QUALITY = "QUALITY"                # Laboratuvar / kalite
    ACCOUNTING = "ACCOUNTING"          # Muhasebe / ödeme
    WAREHOUSE = "WAREHOUSE"            # Depo
    PRICE_MANAGER = "PRICE_MANAGER"    # Fiyat belirleme / onay


class PriceStatus(str, enum.Enum):
    DRAFT = "DRAFT"
    APPROVED = "APPROVED"
    REVOKED = "REVOKED"  # İptal; geçmiş snapshot'lar etkilenmez


class TicketStatus(str, enum.Enum):
    GROSS_WEIGHED = "GROSS_WEIGHED"  # Dolu (giriş) tartım yapıldı
    NET_WEIGHED = "NET_WEIGHED"      # Boş (dara) tartım yapıldı, net kg belli
    GRADED = "GRADED"                # Kalite sınıfı + fiyat donduruldu, lot açıldı
    SETTLED = "SETTLED"              # Hakediş oluşturuldu
    CANCELLED = "CANCELLED"


class DeductionCalcMethod(str, enum.Enum):
    PERCENT = "PERCENT"  # Brüt tutarın %'si (stopaj, borsa tescil, komisyon)
    PER_KG = "PER_KG"    # Net kg başına TL (ör. hamaliye)
    FIXED = "FIXED"      # Teslimat başına sabit TL


class SettlementStatus(str, enum.Enum):
    UNPAID = "UNPAID"
    PARTIALLY_PAID = "PARTIALLY_PAID"
    PAID = "PAID"
    OFFSET_ONLY = "OFFSET_ONLY"  # Tutarın tamamı avansa mahsup edildi, ödenecek bir şey yok


class AdvanceStatus(str, enum.Enum):
    OPEN = "OPEN"
    PARTIALLY_OFFSET = "PARTIALLY_OFFSET"
    CLOSED = "CLOSED"


class PaymentMethod(str, enum.Enum):
    BANK_TRANSFER = "BANK_TRANSFER"
    CASH = "CASH"
    CHEQUE = "CHEQUE"


class LedgerEntryType(str, enum.Enum):
    DELIVERY = "DELIVERY"      # + Teslimat brüt tutarı (üreticinin alacağı)
    DEDUCTION = "DEDUCTION"    # - Stopaj / borsa / komisyon kesintisi
    ADVANCE = "ADVANCE"        # - Üreticiye verilen avans
    PAYMENT = "PAYMENT"        # - Üreticiye yapılan ödeme
    ADJUSTMENT = "ADJUSTMENT"  # ± Manuel düzeltme
    REVERSAL = "REVERSAL"      # ± Hatalı kaydın ters kaydı (kayıtlar silinmez)


class StockMovementType(str, enum.Enum):
    RECEIPT = "RECEIPT"            # + Teslimattan siloya ilk giriş
    DISPATCH = "DISPATCH"          # - Satış / sevk / işleme çıkışı
    TRANSFER_IN = "TRANSFER_IN"    # + Silolar arası transfer (hedef)
    TRANSFER_OUT = "TRANSFER_OUT"  # - Silolar arası transfer (kaynak)
    ADJUSTMENT = "ADJUSTMENT"      # ± Sayım / fire farkı
