from datetime import date, datetime, time, timedelta, timezone

# Türkiye 2016'dan beri kalıcı olarak UTC+3 (yaz saati uygulaması yok).
TR_TZ = timezone(timedelta(hours=3), "Europe/Istanbul")


def today_tr() -> date:
    return datetime.now(TR_TZ).date()


def day_bounds_utc(d: date) -> tuple[datetime, datetime]:
    """Türkiye saatine göre `d` gününün [başlangıç, bitiş) aralığı, UTC olarak."""
    start = datetime.combine(d, time.min, tzinfo=TR_TZ)
    return start.astimezone(timezone.utc), (start + timedelta(days=1)).astimezone(timezone.utc)


def as_aware(dt: datetime) -> datetime:
    """Saat dilimi verilmemiş girişleri Türkiye saati kabul eder, UTC'ye çevirir."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=TR_TZ)
    return dt.astimezone(timezone.utc)
