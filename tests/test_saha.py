"""Saha kullanımı: ürün türü (yaş / kuru-kavlak), çuval darası, ağırlık kaynağı (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_saha.py
"""
from decimal import Decimal

import pytest

from tests.conftest import fiyat_gir

API = "/api/v1"
YAS = {"catlak_orani": 85, "randiman": 47, "bos_orani": 3, "curuk_orani": 1, "nem_orani": 35}   # yaş fıstıkta nem yüksektir


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Saha alanları yalnızca Java sürümünde var (--java ile çalıştırın)")


def kuru_tartim(client, uretici_id, brut=4800, dara=3800, **ek):
    """conftest.tartim'den farkı: ek alanlar kalite analizine değil isteğin kendisine eklenir."""
    return client.post(f"{API}/tartimlar", json={
        "uretici_id": uretici_id, "plaka": "56 ABC 123", "brut_kg": brut, "dara_kg": dara,
        "kalite": {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "curuk_orani": 1, "nem_orani": 6}} | ek)


def yas_tartim(client, uretici_id, brut=2600, dara=1500, **ek):
    return client.post(f"{API}/tartimlar", json={
        "uretici_id": uretici_id, "plaka": "56 YS 001", "brut_kg": brut, "dara_kg": dara,
        "urun_turu": "FRESH", "kalite": YAS} | ek)


def test_cuval_darasi_netten_dusulur(client, uretici):
    fiyat_gir(client, A=300)
    r = kuru_tartim(client, uretici["id"], cuval_sayisi=40, cuval_darasi_kg=1.25)
    assert r.status_code == 201, r.text
    t = r.json()
    assert Decimal(t["net_kg"]) == Decimal("950")                # 4800 − 3800 − 40 × 1,25
    assert Decimal(t["dara_kg"]) == Decimal("3850")              # toplam dara
    assert Decimal(t["arac_darasi_kg"]) == Decimal("3800")
    assert t["cuval_sayisi"] == 40 and Decimal(t["cuval_darasi_kg"]) == Decimal("1.25")
    assert Decimal(t["hakedis"]["brut_tutar"]) == Decimal("285000")   # 950 × 300


def test_onizleme_cuval_darasini_hesaba_katar(client, uretici):
    fiyat_gir(client, A=300)
    r = client.post(f"{API}/tartimlar/onizleme", json={
        "brut_kg": 1000, "dara_kg": 500, "cuval_sayisi": 20, "cuval_darasi_kg": 1,
        "kalite": {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "curuk_orani": 1, "nem_orani": 6}})
    assert r.status_code == 200, r.text
    assert Decimal(r.json()["net_kg"]) == Decimal("480")


def test_cuval_darasi_neti_sifirlarsa_422(client, uretici):
    fiyat_gir(client, A=300)
    r = kuru_tartim(client, uretici["id"], brut=1000, dara=900, cuval_sayisi=100, cuval_darasi_kg=1)
    assert r.status_code == 422 and "Dara" in r.json()["detaylar"][0]["mesaj"]
    assert kuru_tartim(client, uretici["id"], cuval_sayisi=-1).status_code == 422


def test_yas_fistik_kendi_siniflari_ve_fiyatiyla_alinir(client, uretici):
    fiyat_gir(client, A=300, YA=120)
    r = yas_tartim(client, uretici["id"])
    assert r.status_code == 201, r.text
    t = r.json()
    # %35 nem kuru-kavlakta Iskarta olurdu; yaş fıstıkta nem sınırı yok
    assert t["urun_turu"] == "FRESH" and t["urun_turu_adi"] == "Yaş fıstık"
    assert t["kalite"]["sinif_kodu"] == "YA"
    assert Decimal(t["fiyat"]["birim_fiyat"]) == Decimal("120")
    assert t["stok"][0]["silo_kodu"] == "S-YA"                  # yaş fıstık kendi silosuna


def test_yas_fistik_fiyati_yoksa_kuru_fiyatiyla_alinmaz(client, uretici):
    fiyat_gir(client, A=300)
    r = yas_tartim(client, uretici["id"])
    assert r.status_code == 400 and "YA sınıfı" in r.json()["hata"]


def test_manuel_sinif_urun_turuyle_uyusmali(client, uretici):
    fiyat_gir(client, A=300, YA=120)
    r = yas_tartim(client, uretici["id"], manuel_kalite_kodu="A", manuel_kalite_gerekcesi="deneme")
    assert r.status_code == 400 and "Yaş fıstık" in r.json()["hata"]


def test_agirlik_kaynagi_kaydedilir(client, uretici):
    fiyat_gir(client, A=300)
    t = kuru_tartim(client, uretici["id"], brut_kaynagi="SCALE").json()
    assert t["brut_kaynagi"] == "SCALE" and t["dara_kaynagi"] == "MANUAL"
    assert client.get(f"{API}/tartimlar/{t['id']}").json()["brut_kaynagi"] == "SCALE"


def test_guncel_fiyatlar_urun_turune_gore_gruplu(client):
    kalemler = client.get(f"{API}/fiyatlar/guncel").json()["kalemler"]
    turler = [k["urun_turu"] for k in kalemler]
    assert turler == sorted(turler) and {"DRY", "FRESH"} <= set(turler)   # önce kuru, sonra yaş
    assert {k["kalite_kodu"] for k in kalemler} >= {"A", "B", "ISK", "YA", "YB", "YISK"}
