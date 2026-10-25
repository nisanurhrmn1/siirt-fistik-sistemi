"""Halka açık üretici bakiye ve emanet sorgusu (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_sorgu.py
"""
import random
from decimal import Decimal

import httpx
import pytest

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Üretici sorgusu yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    """Girişsiz istemci: sorgu halka açıktır."""
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def gecerli_tc():
    """Algoritmaya uygun rastgele T.C. (deneme sınırı testleri birbirini etkilemesin)."""
    d = [random.randint(1, 9)] + [random.randint(0, 9) for _ in range(8)]
    d.append(((d[0] + d[2] + d[4] + d[6] + d[8]) * 7 - (d[1] + d[3] + d[5] + d[7])) % 10)
    d.append(sum(d) % 10)
    return "".join(map(str, d))


def sorgu(c, tc, son4):
    return c.post(f"{API}/sorgu/uretici", json={"tc_kimlik_no": tc, "telefon_son4": son4})


def test_dogru_bilgiyle_yalnizca_kendi_ozetini_gorur(client, anonim, uretici):
    fiyat_gir(client, A=300)
    tartim(client, uretici["id"])                                                      # 1.000 kg satış
    client.post(f"{API}/tartimlar", json={"uretici_id": uretici["id"], "plaka": "56 ABC 123", "brut_kg": 4400, "dara_kg": 3200,
                                          "emanet": True, "kalite": {"catlak_orani": 90, "randiman": 58, "bos_orani": 2,
                                                                     "curuk_orani": 1, "nem_orani": 6}})   # 1.200 kg emanet
    client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 10000, "odeme_yontemi": "CASH"})
    # Başka bir üretici: verisi asla görünmemeli
    baska = client.post(f"{API}/ureticiler", json={"tc_kimlik_no": "10000000078", "ad_soyad": "Fatma Demir",
                                                   "telefon": "0533 444 55 66", "ilce": "Merkez"}).json()
    tartim(client, baska["id"], brut=9800, dara=7200)

    r = sorgu(anonim, "10000000146", "4567")                                          # +90 532 123 45 67
    assert r.status_code == 200, r.text
    s = r.json()
    assert s["ad_maskeli"] == "Ahmet Y."
    assert (Decimal(s["toplam_teslim_kg"]), Decimal(s["emanet_kg"]), Decimal(s["satilan_kg"])) == (2200, 1200, 1000)
    assert Decimal(s["toplam_avans"]) == 10000 and Decimal(s["acik_avans"]) == 10000
    assert len(s["son_teslimatlar"]) == 2 and {t["teslim_sekli"] for t in s["son_teslimatlar"]} == {"Satış", "Emanet"}
    metin = r.text
    for gizli in ("10000000146", "5321234567", "TR33", "Yılmaz", "Fatma", "10000000078", "2600"):
        assert gizli not in metin, gizli


def test_eslesmeyen_bilgiler_ayni_mesaji_verir(anonim, uretici):
    yanlis_tel = sorgu(anonim, "10000000146", "0000")
    olmayan_tc = sorgu(anonim, gecerli_tc(), "4567")
    assert yanlis_tel.status_code == olmayan_tc.status_code == 400
    assert yanlis_tel.json()["hata"] == olmayan_tc.json()["hata"] == "T.C. Kimlik No veya telefon bilgisi eşleşmedi."


def test_dogrulamalar(anonim):
    assert sorgu(anonim, "12345678901", "1234").status_code == 422      # algoritmaya uymayan T.C.
    r = sorgu(anonim, "10000000146", "12a")
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "telefon_son4"


def test_hatali_denemeler_sinirlanir(client, anonim):
    tc = gecerli_tc()
    r = client.post(f"{API}/ureticiler", json={"tc_kimlik_no": tc, "ad_soyad": "Deneme Üretici", "telefon": "0544 777 88 99",
                                               "ilce": "Merkez"})
    assert r.status_code == 201, r.text
    for _ in range(5):
        assert sorgu(anonim, tc, "0000").status_code == 400
    kilitli = sorgu(anonim, tc, "8899")                                                 # doğru bilgi de bekletilir
    assert kilitli.status_code == 429 and "dakika sonra" in kilitli.json()["hata"]
    assert sorgu(anonim, "10000000146", "0000").status_code == 400                     # başka T.C. etkilenmez


def test_sorgu_giris_gerektirmez_ama_diger_api_gerektirir(anonim, uretici):
    assert anonim.get(f"{API}/ureticiler").status_code == 401
    assert sorgu(anonim, "10000000146", "4567").status_code == 200
