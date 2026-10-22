from datetime import datetime, timezone

from sqlalchemy import DateTime, MetaData, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator

# Alembic autogenerate'in kararlı kısıt adları üretebilmesi için isimlendirme kuralı.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class UTCDateTime(TypeDecorator):
    """timestamptz; yazarken UTC'ye çevirir, okurken her zaman timezone-aware (UTC) döner.
    SQLite saat dilimi saklamadığı için okunan naive değerler UTC kabul edilir."""
    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("Saat dilimi olmayan (naive) datetime yazılamaz; UTC kullanın.")
        return value.astimezone(timezone.utc)

    def process_result_value(self, value, dialect):
        if value is not None and value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)
    # Tüm zaman damgaları timezone-aware (PostgreSQL: timestamptz). Uygulama UTC yazar.
    type_annotation_map = {datetime: UTCDateTime()}


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(server_default=func.now(), onupdate=func.now())
