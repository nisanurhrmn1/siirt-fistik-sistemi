"""Satış ve sevkiyat (depodan çıkış): stok düşümü, lot izlenebilirliği, yetkiler, gün sonu (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_sevkiyat.py
"""
from decimal import Decimal

import httpx
import pytest
from sqlalchemy import exc, text

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Sevkiyat yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def giris_yap(c, kullanici):
    r = c.post(f"{API}/oturum/demo-giris", json={"kullanici_adi": kullanici})
    c.headers["Authorization"] = f"Bearer {r.json()['token']}"


def silo_id(client, kod):
    return next(s["silo_id"] for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == kod)


def silo_stok(client, kod):
    return next(Decimal(s["stok_kg"]) for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == kod)


def sevk(client, silo, brut=9700, dara=8200, fiyat=420, tahsilat="BANK_TRANSFER", musteri="Antep Fıstıkçılık A.Ş.", **ek):
    return client.post(f"{API}/sevkiyatlar", json={
        "musteri_adi": musteri, "plaka": "27 AF 270", "sofor_adi": "Hasan Demir", "silo_id": silo,
        "brut_kg": brut, "dara_kg": dara, "birim_fiyat": fiyat, "tahsilat": tahsilat} | ek)


@pytest.fixture
def stok(client, uretici):
    """S-A1 silosunda iki lot: 1000 kg + 1000 kg A sınıfı."""
    fiyat_gir(client, A=300)
    lotlar = [tartim(client, uretici["id"]).json() for _ in range(2)]
    assert all(t["stok"][0]["silo_kodu"] == "S-A1" for t in lotlar)
    return {"silo": silo_id(client, "S-A1"), "lotlar": lotlar}


def test_sevkiyat_stogu_lot_bazinda_ilk_giren_ilk_cikar_dusurur(client, stok):
    r = sevk(client, stok["silo"])                              # 9700 − 8200 = 1500 kg
    assert r.status_code == 201, r.text
    s = r.json()
    assert s["sevkiyat_no"].startswith("SV-") and Decimal(s["net_kg"]) == Decimal("1500")
    assert Decimal(s["toplam_tutar"]) == Decimal("630000")      # 1500 × 420
    assert s["kalite_kodu"] == "A" and s["urun_turu"] == "DRY" and s["silo_kodu"] == "S-A1"
    # İlk giren ilk çıkar: ilk lotun tamamı (1000), ikinciden 500
    assert [(l["lot_no"], Decimal(l["kg"])) for l in s["lotlar"]] == [
        (stok["lotlar"][0]["lot_no"], Decimal("1000")), (stok["lotlar"][1]["lot_no"], Decimal("500"))]
    assert Decimal(s["silo_kalan_kg"]) == Decimal("500") == silo_stok(client, "S-A1")
    assert client.get(f"{API}/sevkiyatlar/{s['id']}").json()["sevkiyat_no"] == s["sevkiyat_no"]


def test_stoktan_fazla_cikis_reddedilir_stok_degismez(client, stok):
    r = sevk(client, stok["silo"], brut=10300, dara=8200)       # 2100 kg > 2000 kg stok
    assert r.status_code == 400 and "S-A1 silosunda 2.000 kg stok var" in r.json()["hata"]
    assert silo_stok(client, "S-A1") == Decimal("2000")
    assert client.get(f"{API}/sevkiyatlar").json() == []


def test_bos_silodan_cikis_yapilamaz(client, stok):
    r = sevk(client, silo_id(client, "S-B1"))
    assert r.status_code == 400 and "boş" in r.json()["hata"]


def test_sevkiyat_dogrulamalari_422(client, stok):
    assert sevk(client, stok["silo"], brut=8200, dara=8200).status_code == 422
    assert sevk(client, stok["silo"], fiyat=0).status_code == 422
    assert sevk(client, stok["silo"], tahsilat=None).status_code == 422
    assert sevk(client, stok["silo"], plaka="X").status_code == 422
    assert sevk(client, stok["silo"], musteri_vergi_no="12").status_code == 422


def test_yetki_yalnizca_yonetici_ve_depo_sorumlusu(client, anonim, stok):
    for kullanici in ("kantar", "muhasebe", "fiyat"):
        giris_yap(anonim, kullanici)
        assert sevk(anonim, stok["silo"]).status_code == 403, kullanici
    giris_yap(anonim, "muhasebe")
    assert anonim.get(f"{API}/sevkiyatlar").status_code == 200      # okuma herkese açık
    giris_yap(anonim, "depo")
    assert sevk(anonim, stok["silo"], brut=8700).status_code == 201


def test_musteri_karti_tekrarlanmaz_acik_hesap_toplanir(client, stok):
    assert sevk(client, stok["silo"], brut=8700, tahsilat="ON_ACCOUNT").status_code == 201           # 500 kg
    assert sevk(client, stok["silo"], brut=8700, musteri="  antep FISTIKÇILIK a.ş. ", tahsilat="CASH").status_code == 201
    musteriler = client.get(f"{API}/musteriler").json()
    assert len(musteriler) == 1
    m = musteriler[0]
    assert m["sevkiyat_sayisi"] == 2 and Decimal(m["toplam_kg"]) == Decimal("1000")
    assert Decimal(m["acik_hesap"]) == Decimal("210000")             # yalnızca vadeli olan: 500 × 420


def test_veritabani_eksi_stoga_izin_vermez(client, db_engine, stok):
    s = sevk(client, stok["silo"], brut=8700).json()
    lot = client.get(f"{API}/tartimlar/{stok['lotlar'][0]['id']}").json()
    with pytest.raises(exc.DBAPIError, match="eksiye"):
        with db_engine.begin() as c:
            lot_id = c.execute(text("SELECT id FROM lots WHERE lot_no = :n"), {"n": lot["lot_no"]}).scalar()
            c.execute(text("""INSERT INTO stock_movements (lot_id, silo_id, movement_type, quantity_kg, moved_at, created_by_id, shipment_id)
                              SELECT :l, :s, 'DISPATCH', -5000, now(), created_by_id, id FROM shipments WHERE id = :sv"""),
                      {"l": lot_id, "s": stok["silo"], "sv": s["id"]})


def test_gun_sonu_sevkiyat_ve_tahsilati_gosterir(client, stok):
    sevk(client, stok["silo"], tahsilat="CASH")                                     # 1500 kg × 420 = 630.000 nakit
    r = client.get(f"{API}/raporlar/gun-sonu").json()
    assert len(r["sevkiyatlar"]) == 1 and Decimal(r["kasa"]["nakit_tahsilat"]) == Decimal("630000")
    assert Decimal(r["kasa"]["kasa_net_nakit"]) == Decimal("630000")                # nakit ödeme / avans yok
    silo = next(s for s in r["silo_girisleri"] if s["silo_kodu"] == "S-A1")
    assert Decimal(silo["giren_kg"]) == Decimal("2000") and Decimal(silo["cikan_kg"]) == Decimal("1500")
    csv = client.get(f"{API}/raporlar/gun-sonu.csv").content.decode("utf-8-sig")
    assert "SEVKİYATLAR (SATIŞ)" in csv and "Antep Fıstıkçılık A.Ş." in csv and "Nakit alındı" in csv
