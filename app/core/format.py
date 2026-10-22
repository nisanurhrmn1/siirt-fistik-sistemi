from decimal import ROUND_HALF_UP, Decimal


def tl(value: Decimal) -> str:
    """Decimal('55432.5') → '55.432,50 TL' (kullanıcıya dönen mesajlar için)."""
    s = f"{Decimal(value).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP):,.2f}"
    return s.replace(",", "_").replace(".", ",").replace("_", ".") + " TL"
