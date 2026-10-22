"""Ortam değişkenlerinden okunan ayarlar.

Proje kökünde bir `.env` dosyası varsa önce o okunur (ör. PostgreSQL bağlantısı için; bkz. `.env.example`).
Ortamda zaten tanımlı olan değişkenler `.env` ile ezilmez.

DATABASE_URL     varsayılan: sqlite:///./siirt_fistik.db
                 PostgreSQL: postgresql+psycopg://kullanici:sifre@localhost:5432/siirt_fistik
SEED_DEMO_DATA   1 ise ilk açılışta örnek kullanıcı, kalite sınıfı, kesinti ve silo kaydı oluşturur (varsayılan 1)
SQL_ECHO         1 ise üretilen SQL'i loglar
"""
import os
from dataclasses import dataclass, field
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


def load_env_file(path: Path = ENV_FILE) -> None:
    """Basit KEY=VALUE okuyucu (yorum satırları ve tırnaklar desteklenir)."""
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


load_env_file()


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default_factory=lambda: os.getenv("DATABASE_URL", "sqlite:///./siirt_fistik.db"))
    seed_demo_data: bool = field(default_factory=lambda: os.getenv("SEED_DEMO_DATA", "1") == "1")
    sql_echo: bool = field(default_factory=lambda: os.getenv("SQL_ECHO", "0") == "1")


settings = Settings()
