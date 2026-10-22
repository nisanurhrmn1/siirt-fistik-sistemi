import os
import warnings

os.environ.setdefault("DATABASE_URL", "sqlite://")  # app.db.session import edilmeden önce

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.exc import SAWarning

from app.api.deps import get_db
from app.db.seed import seed_reference_data
from app.db.session import create_db_engine, init_db, make_session_factory
from app.main import app

warnings.filterwarnings("ignore", category=SAWarning, message=".*Decimal objects natively.*")

IBAN = "TR330006100519786457841326"


def pytest_addoption(parser):
    parser.addoption("--pg", action="store_true",
                     help="Testleri .env'deki TEST_DATABASE_URL (PostgreSQL) üzerinde çalıştır")


def _test_engine(config):
    """Varsayılan: bellek içi SQLite. --pg: PostgreSQL test veritabanı, her testte şema sıfırlanır."""
    if not config.getoption("--pg"):
        return create_db_engine("sqlite://")
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.exit("--pg için .env'de TEST_DATABASE_URL tanımlı olmalı (scripts.postgres_kurulum çalıştırın).")
    engine = create_db_engine(url)
    with engine.begin() as conn:
        conn.exec_driver_sql("DROP SCHEMA IF EXISTS public CASCADE")
        conn.exec_driver_sql("CREATE SCHEMA public")
    return engine


@pytest.fixture
def db_engine(request):
    engine = _test_engine(request.config)
    init_db(engine)
    yield engine
    engine.dispose()


@pytest.fixture
def client(db_engine):
    """Her test için boş veritabanı + başlangıç verisi (kullanıcı, sınıflar, kesintiler, silolar)."""
    engine = db_engine
    Session = make_session_factory(engine)
    with Session() as db:
        seed_reference_data(db)

    def _get_db():
        db = Session()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = _get_db
    yield TestClient(app)  # lifespan çalışmaz; init/seed yukarıda yapıldı
    app.dependency_overrides.clear()


@pytest.fixture
def uretici(client):
    r = client.post("/api/v1/ureticiler", json={
        "tc_kimlik_no": "10000000146", "ad_soyad": "Ahmet Yılmaz", "telefon": "+90 532 123 45 67",
        "ilce": "Kurtalan", "banka_hesabi": {"banka_adi": "Ziraat", "iban": "TR33 0006 1005 1978 6457 8413 26"},
    })
    assert r.status_code == 201, r.text
    return r.json()


def fiyat_gir(client, **fiyatlar):
    r = client.post("/api/v1/fiyatlar", json={
        "fiyatlar": [{"kalite_kodu": k, "birim_fiyat": v} for k, v in fiyatlar.items()]})
    assert r.status_code == 201, r.text
    return r.json()


def tartim(client, uretici_id, brut=4800, dara=3800, **kalite):
    analiz = {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "curuk_orani": 1, "nem_orani": 6} | kalite
    return client.post("/api/v1/tartimlar", json={
        "uretici_id": uretici_id, "plaka": "56 ABC 123", "brut_kg": brut, "dara_kg": dara, "kalite": analiz})
