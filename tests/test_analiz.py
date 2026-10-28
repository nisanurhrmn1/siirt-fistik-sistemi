"""Raporlar sekmesinin grafikli analizi (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_analiz.py
"""
from datetime import date, timedelta
from decimal import Decimal

import httpx
import pytest

from tests.conftest import fiyat_gir, tartim
from tests.test_guvenlik import giris_yap

API = "/api/v1"


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Grafikli analiz yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def analiz(c, **p):
    return c.get(f"{API}/raporlar/analiz", params=p)


def test_bos_aralikta_her_gun_sifir_doner(client):
    bugun = date.today()
    r = analiz(client, baslangic=str(bugun - timedelta(days=6)), bitis=str(bugun))
    assert r.status_code == 200, r.text
    a = r.json()
    assert len(a["gunluk"]) == 7 and all(g["teslimat_sayisi"] == 0 and Decimal(g["kg"]) == 0 for g in a["gunluk"])
    assert a["ozet"]["teslimat_sayisi"] == 0 and a["ozet"]["ortalama_fiyat"] is None
    assert a["siniflar"] == [] and a["ureticiler"] == []


def test_alimlar_dagilim_ve_fiyat_gecmisi(client, uretici):
    fiyat_gir(client, A=300, B=260, ISK=150)
    tartim(client, uretici["id"])                                                       # A 1.000 kg (çatlak 90, randıman 58)
    tartim(client, uretici["id"], brut=4300, dara=3800, catlak_orani=75, randiman=50)   # B 500 kg
    baska = client.post(f"{API}/ureticiler", json={"tc_kimlik_no": "10000000078", "ad_soyad": "Fatma Demir",
                                                   "telefon": "0533 444 55 66", "ilce": "Merkez"}).json()
    tartim(client, baska["id"], brut=5800, dara=3800)                                   # A 2.000 kg
    client.post(f"{API}/tartimlar", json={"uretici_id": baska["id"], "plaka": "56 DE 456", "brut_kg": 4400, "dara_kg": 3200,
                                          "emanet": True, "kalite": {"catlak_orani": 90, "randiman": 58, "bos_orani": 2,
                                                                     "curuk_orani": 1, "nem_orani": 6}})   # emanet: alım sayılmaz

    a = analiz(client).json()                                                           # varsayılan: son 30 gün
    assert len(a["gunluk"]) == 30 and a["bitis"] == str(date.today())
    o = a["ozet"]
    assert (o["teslimat_sayisi"], o["uretici_sayisi"], Decimal(o["toplam_kg"])) == (3, 2, 3500)
    assert Decimal(o["emanet_kg"]) == 1200
    assert Decimal(o["ortalama_randiman"]) == Decimal("56.86")                          # (1000·58 + 500·50 + 2000·58) / 3500
    assert Decimal(a["gunluk"][-1]["kg"]) == 3500
    siniflar = {s["kalite_kodu"]: s for s in a["siniflar"]}
    assert (Decimal(siniflar["A"]["kg"]), Decimal(siniflar["B"]["kg"])) == (3000, 500)
    assert sum(Decimal(s["yuzde"]) for s in a["siniflar"]) == Decimal("100.0")
    assert [u["ad_soyad"] for u in a["ureticiler"]] == ["Fatma Demir", "Ahmet Yılmaz"]  # kg'a göre sıralı
    assert Decimal(a["ureticiler"][0]["kg"]) == 2000
    a_fiyat = next(f for f in a["fiyatlar"] if f["kalite_kodu"] == "A")
    assert Decimal(a_fiyat["noktalar"][-1]["fiyat"]) == 300 and a_fiyat["noktalar"][0]["fiyat"] is None
    assert Decimal(o["net_hakedis"]) == Decimal(o["odenen"]) + Decimal(o["acik_hakedis"])


def test_tarih_dogrulamalari(client):
    assert analiz(client, baslangic="2026-10-10", bitis="2026-10-01").status_code == 422
    assert analiz(client, baslangic="2024-01-01", bitis="2026-01-01").status_code == 422   # 366 günden uzun
    assert analiz(client, baslangic="2025-10-28", bitis="2026-10-27").status_code == 200   # tam 1 yıl


def test_yetkiler(anonim):
    assert analiz(anonim).status_code == 401
    for kullanici, beklenen in (("kantar", 403), ("depo", 403), ("muhasebe", 200), ("fiyat", 200)):
        giris_yap(anonim, kullanici)
        assert analiz(anonim).status_code == beklenen, kullanici
