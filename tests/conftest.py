import os
import warnings

os.environ.setdefault("DATABASE_URL", "sqlite://")  # app.db.session import edilmeden önce

import httpx
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
    parser.addoption("--java", metavar="URL",
                     help="Aynı testleri çalışan Java (Spring Boot) sunucusuna karşı çalıştır, ör. http://127.0.0.1:8020. "
                          "Sunucu TEST_DATABASE_URL veritabanına bağlı olmalı.")
    parser.addoption("--sure-esigi", metavar="MS",
                     help="KS-1 tepki süresi eşiği (ms). Yalnızca sunucu veritabanıyla aynı bölgedeyken verin, ör. 500.")


# Java modunda her testten önce boşaltılan tablolar (başlangıç verisi tabloları korunur: users, quality_grades,
# deduction_types, silos). TRUNCATE satır trigger'larını tetiklemez.
ISLEM_TABLOLARI = ("sms_messages, customer_payments, shipments, customers, payment_allocations, payments, advance_offsets, settlement_deductions, producer_ledger_entries, "
                   "stock_movements, lots, settlements, advances, quality_inspections, weigh_tickets, daily_prices, "
                   "producer_bank_accounts, producers, document_sequences")


def pytest_configure(config):
    if config.getoption("--java"):
        config.option.pg = True   # Java yalnızca PostgreSQL ile çalışır; PostgreSQL'e özgü testler de koşar


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
    if request.config.getoption("--java"):
        # Şema Java (Flyway) tarafından kurulur; yalnızca işlem verisini temizle
        engine = create_db_engine(os.environ["TEST_DATABASE_URL"])
        with engine.begin() as conn:
            conn.exec_driver_sql(f"TRUNCATE {ISLEM_TABLOLARI} RESTART IDENTITY CASCADE")
            # Testlerin eklediği kullanıcılar ve hesap talepleri (demo hesapları kalır)
            conn.exec_driver_sql("DELETE FROM users WHERE username NOT IN ('admin', 'kantar', 'muhasebe', 'fiyat', 'depo')")
            # Çatlak primi kuralları testler arasında kapalı başlar (demo verisi açmış olabilir)
            conn.exec_driver_sql("UPDATE split_premium_rules SET is_active = false, empty_active = false")
            # Resmî kesintiler varsayılan hâline döner (stopaj, borsa, komisyon açık; Bağ-Kur kapalı)
            conn.exec_driver_sql("UPDATE deduction_types SET is_active = (code <> 'BAGKUR'), "
                                 "rate = CASE code WHEN 'STOPAJ' THEN 2 WHEN 'BORSA' THEN 0.2 WHEN 'KOMISYON' THEN 1 ELSE rate END")
        yield engine
        engine.dispose()
        return
    engine = _test_engine(request.config)
    init_db(engine)
    yield engine
    engine.dispose()


@pytest.fixture
def client(request, db_engine):
    """Her test için boş veritabanı + başlangıç verisi (kullanıcı, sınıflar, kesintiler, silolar)."""
    java_url = request.config.getoption("--java")
    if java_url:
        with httpx.Client(base_url=java_url, follow_redirects=True, timeout=30) as c:
            # Java sürümünde API giriş ister: testler yönetici (tüm yetkiler) olarak çalışır
            r = c.post("/api/v1/oturum/demo-giris", json={"kullanici_adi": "admin"})
            assert r.status_code == 200, r.text
            c.headers["Authorization"] = f"Bearer {r.json()['token']}"
            yield c
        return
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
