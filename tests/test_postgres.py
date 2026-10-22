"""Yalnızca PostgreSQL'de bulunan son savunma hattı: trigger'lar, EXCLUDE kısıtı ve rapor view'ları.

Uygulamayı atlayıp doğrudan SQL ile veri bozmaya çalışır; veritabanının reddettiğini doğrular.
Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --pg
"""
from decimal import Decimal as D

import pytest
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"


@pytest.fixture(autouse=True)
def _yalnizca_postgresql(request):
    if not request.config.getoption("--pg"):
        pytest.skip("PostgreSQL testleri için --pg ile çalıştırın")


@pytest.fixture
def teslimat(client, uretici):
    """Fiyat girilmiş, bir teslimatı yapılmış üretici: (tartım yanıtı, hakediş)."""
    fiyat_gir(client, A=300, B=260, ISK=150)
    t = tartim(client, uretici["id"]).json()
    return t, t["hakedis"]


def sql(engine, komut, **params):
    with engine.begin() as conn:
        return conn.execute(text(komut), params)


def test_cari_hesap_defteri_degistirilemez_ve_silinemez(db_engine, teslimat):
    with pytest.raises(DBAPIError, match="yalnızca eklemeye açıktır"):
        sql(db_engine, "UPDATE producer_ledger_entries SET amount = amount * 2")
    with pytest.raises(DBAPIError, match="yalnızca eklemeye açıktır"):
        sql(db_engine, "DELETE FROM producer_ledger_entries")


def test_onayli_fiyat_degistirilemez_silinemez(db_engine, teslimat):
    with pytest.raises(DBAPIError, match="değiştirilemez"):
        sql(db_engine, "UPDATE daily_prices SET price_per_kg = 999 WHERE status = 'APPROVED'")
    with pytest.raises(DBAPIError, match="silinemez"):
        sql(db_engine, "DELETE FROM daily_prices WHERE status = 'APPROVED'")


def test_ayni_sinif_icin_cakisan_onayli_fiyat_reddedilir(db_engine, teslimat):
    with pytest.raises(DBAPIError, match="ex_daily_prices_no_overlap"):
        sql(db_engine, """
            INSERT INTO daily_prices (quality_grade_id, price_per_kg, currency, valid_from, status,
                                      created_by_id, approved_by_id, approved_at)
            SELECT quality_grade_id, 999, 'TRY', valid_from + interval '1 minute', 'APPROVED', 1, 1, now()
              FROM daily_prices WHERE status = 'APPROVED' LIMIT 1""")


def test_teslimatin_kilitli_fiyati_ve_tartimi_degistirilemez(db_engine, teslimat):
    t, _ = teslimat
    with pytest.raises(DBAPIError, match="dondurulmuş"):
        sql(db_engine, "UPDATE weigh_tickets SET unit_price = 1 WHERE id = :id", id=t["id"])
    with pytest.raises(DBAPIError, match="değiştirilemez"):
        sql(db_engine, "UPDATE weigh_tickets SET gross_kg = gross_kg + 1000 WHERE id = :id", id=t["id"])


def test_hakedis_tutarlari_degistirilemez_ama_avans_mahsubu_yapilabilir(db_engine, teslimat):
    _, h = teslimat
    with pytest.raises(DBAPIError, match="Hakediş tutarları değiştirilemez"):
        sql(db_engine, "UPDATE settlements SET gross_amount = 1 WHERE id = :id", id=h["id"])
    with pytest.raises(DBAPIError, match="Hakediş tutarları değiştirilemez"):
        sql(db_engine, "UPDATE settlements SET net_payable = net_payable + 1000 WHERE id = :id", id=h["id"])
    # İzin verilen tek değişiklik: avans mahsubu artar, net ödenecek aynı miktarda azalır
    sql(db_engine, """UPDATE settlements SET advance_offset_total = advance_offset_total + 100,
                                             net_payable = net_payable - 100 WHERE id = :id""", id=h["id"])


def test_hakedis_formulu_veritabaninda_denetlenir(db_engine, teslimat):
    _, h = teslimat
    with pytest.raises(DBAPIError, match="ck_settlements_net_formula"):
        sql(db_engine, """INSERT INTO settlements (settlement_no, ticket_id, producer_id, net_kg, unit_price,
                              gross_amount, deductions_total, advance_offset_total, net_payable, paid_amount,
                              status, created_by_id)
                          SELECT 'HK-SAHTE', id, producer_id, 1000, 300, 300000, 0, 0, 999999, 0, 'UNPAID', 1
                            FROM weigh_tickets WHERE id = :id""", id=h["tartim_id"])


def test_farkli_kalite_yanlis_siloya_giremez(db_engine, teslimat):
    t, _ = teslimat  # A sınıfı lot
    with pytest.raises(DBAPIError, match="yalnızca kalite sınıfı"):
        sql(db_engine, """INSERT INTO stock_movements (lot_id, silo_id, movement_type, quantity_kg, moved_at,
                                                       created_by_id)
                          SELECT l.id, s.id, 'RECEIPT', 10, now(), 1 FROM lots l, silos s
                           WHERE l.lot_no = :lot AND s.code = 'S-B1'""", lot=t["lot_no"])


def test_rapor_viewlari_api_ile_ayni_sonucu_verir(client, db_engine, teslimat, uretici):
    client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 50000, "odeme_yontemi": "CASH"})
    ekstre = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()["ozet"]
    view = sql(db_engine, "SELECT balance, open_advance, total_deductions FROM v_producer_balance "
                          "WHERE producer_id = :id", id=uretici["id"]).one()
    assert view.balance == D(ekstre["bakiye"]) and view.open_advance == D(ekstre["acik_avans_bakiyesi"])
    assert view.total_deductions == D(ekstre["toplam_kesinti"])

    stok = sql(db_engine, "SELECT silo_code, stock_kg FROM v_silo_stock").all()
    assert [(s.silo_code, s.stock_kg) for s in stok] == [("S-A1", D("1000.00"))]
