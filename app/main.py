"""Uygulama giriş noktası.

Geliştirme sunucusu (proje kök klasöründen):
    .\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --reload
Panel: http://127.0.0.1:8000/   ·   Swagger: http://127.0.0.1:8000/docs   ·   ReDoc: http://127.0.0.1:8000/redoc
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.errors import register_exception_handlers
from app.api.v1 import api_router
from app.core.config import settings
from app.db import session as db_session
from app.db.seed import seed_reference_data
from app.web import mount_web

TAGS = [
    {"name": "Üreticiler", "description": "Üretici kaydı, listeleme ve cari hesap ekstresi"},
    {"name": "Kantar ve Alım", "description": "Tartım, kalite analizi, fiyat kilidi, hakediş ve stok girişi"},
    {"name": "Fiyat Yönetimi", "description": "Kalite sınıfı bazlı günlük taban fiyatlar"},
    {"name": "Finans: Avans, Hakediş, Ödeme", "description": "Avans, avans mahsubu ve parçalı/tam ödeme"},
    {"name": "Depo", "description": "Silo ve kalite bazlı stok"},
    {"name": "Raporlar", "description": "Günlük alım ve finans özetleri"},
    {"name": "Sistem", "description": "Sağlık kontrolü"},
]


@asynccontextmanager
async def lifespan(_: FastAPI):
    db_session.init_db(db_session.engine)
    if settings.seed_demo_data:
        with db_session.SessionLocal() as db:
            seed_reference_data(db)
    yield


app = FastAPI(
    title="Siirt Fıstığı Alım ve Üretici Ödeme Sistemi",
    version="1.1.0",
    description="Kantar, kalite, fiyat, hakediş, avans, ödeme ve depo yönetimi için REST API.\n\n"
                "Para ve ağırlık değerleri hassasiyet kaybı olmaması için JSON'da **metin** olarak döner "
                "(ör. `\"300000.00\"`); isteklerde sayı veya metin gönderilebilir.\n\n"
                "Kimlik doğrulama henüz yok: işlemi yapan kullanıcı `X-Kullanici-Id` başlığıyla verilir, "
                "boşsa ilk aktif yönetici kullanılır.",
    openapi_tags=TAGS,
    lifespan=lifespan,
    docs_url=None,   # /docs ve /redoc, CDN yerine yerel dosyalarla app/web.py'de sunulur
    redoc_url=None,
)
register_exception_handlers(app)
app.include_router(api_router, prefix="/api/v1")
mount_web(app)  # Yönetim paneli: http://127.0.0.1:8000/


@app.get("/saglik", tags=["Sistem"], summary="Sağlık kontrolü")
def saglik() -> dict[str, str]:
    return {"durum": "ok"}
