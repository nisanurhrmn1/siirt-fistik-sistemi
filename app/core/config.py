"""Ortam değişkenlerinden okunan ayarlar.

DATABASE_URL     varsayılan: sqlite:///./siirt_fistik.db
                 PostgreSQL: postgresql+psycopg://kullanici:sifre@localhost:5432/siirt_fistik
SEED_DEMO_DATA   1 ise ilk açılışta örnek kullanıcı, kalite sınıfı, kesinti ve silo kaydı oluşturur (varsayılan 1)
SQL_ECHO         1 ise üretilen SQL'i loglar
"""
import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default_factory=lambda: os.getenv("DATABASE_URL", "sqlite:///./siirt_fistik.db"))
    seed_demo_data: bool = field(default_factory=lambda: os.getenv("SEED_DEMO_DATA", "1") == "1")
    sql_echo: bool = field(default_factory=lambda: os.getenv("SQL_ECHO", "0") == "1")


settings = Settings()
