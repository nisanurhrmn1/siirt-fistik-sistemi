"""Müstahsil bilgilendirme SMS'i (simülasyon sağlayıcısıyla) ve cari döküm hareket ayrıntıları (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_sms.py
"""
from decimal import Decimal

import pytest

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("SMS yalnızca Java sürümünde var (--java ile çalıştırın)")


def avans(client, uretici_id, tutar=10000, yontem="CASH"):
    r = client.post(f"{API}/avanslar", json={"uretici_id": uretici_id, "tutar": tutar, "odeme_yontemi": yontem})
    assert r.status_code == 201, r.text
    return r.json()


def test_tartimda_sms_gonderilir_ve_fiste_gorunur(client, uretici):
    fiyat_gir(client, A=300)
    t = tartim(client, uretici["id"]).json()
    sms = t["sms"]
    assert sms["durum"] == "SIMULATED" and sms["simulasyon"] is True and sms["saglayici"] == "simulasyon"
    assert sms["telefon"] == uretici["telefon"]
    m = sms["mesaj"]
    assert m.startswith("Sn. Ahmet Yılmaz, " + t["fis_no"] + " ile 1.000 kg Kuru-kavlak A sınıfı fıstığınız teslim alınmıştır.")
    assert "Avans mahsubu sonrası net hakediş:" in m and "Güncel bakiyeniz:" in m
    # Fiş daha sonra açıldığında da SMS bilgisi gelir
    assert client.get(f"{API}/tartimlar/{t['id']}").json()["sms"]["id"] == sms["id"]


def test_avansta_sms_ve_eksi_bakiye(client, uretici):
    a = avans(client, uretici["id"])
    sms = a["sms"]
    assert sms["durum"] == "SIMULATED"
    assert sms["mesaj"] == ("Sn. Ahmet Yılmaz, hesabınıza 10.000,00 TL avans kaydedilmiştir. "
                            "Güncel bakiyeniz: -10.000,00 TL (avans borcu).")


def test_sms_kayitlari_listelenir(client, uretici):
    fiyat_gir(client, A=300)
    avans(client, uretici["id"])
    tartim(client, uretici["id"])
    kayitlar = client.get(f"{API}/sms", params={"uretici_id": uretici["id"]}).json()
    assert len(kayitlar) == 2 and kayitlar[0]["mesaj"].startswith("Sn. Ahmet Yılmaz, KF-")   # en yeni önce


def test_dokum_hareketleri_belge_urun_miktar_ve_fiyat_icerir(client, uretici):
    fiyat_gir(client, A=300)
    avans(client, uretici["id"], yontem="BANK_TRANSFER")
    t = tartim(client, uretici["id"]).json()
    h = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()["hareketler"]
    assert [x["tur"] for x in h][:2] == ["ADVANCE", "DELIVERY"]
    av, tes = h[0], h[1]
    assert av["belge_no"].startswith("AV-") and av["odeme_yontemi"] == "BANK_TRANSFER" and Decimal(av["borc"]) == 10000
    assert tes["belge_no"] == t["fis_no"] and tes["urun_sinif"] == "Kuru-kavlak · A"
    assert Decimal(tes["miktar_kg"]) == 1000 and Decimal(tes["birim_fiyat"]) == 300 and Decimal(tes["alacak"]) == 300000
    kesintiler = [x for x in h if x["tur"] == "DEDUCTION"]
    assert kesintiler and all(x["belge_no"] == t["fis_no"] and x["miktar_kg"] is None for x in kesintiler)
