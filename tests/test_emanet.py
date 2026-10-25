"""Emanet fıstık ve numuneye dayalı çatlak primi (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_emanet.py
"""
from decimal import Decimal

import httpx
import pytest
from sqlalchemy import exc, text

from tests.conftest import fiyat_gir

API = "/api/v1"
KALITE = {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "curuk_orani": 1, "nem_orani": 6}


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Emanet ve çatlak primi yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def teslim(client, uretici_id, emanet=False, brut=4800, dara=3800, kalite=None):
    return client.post(f"{API}/tartimlar", json={
        "uretici_id": uretici_id, "plaka": "56 ABC 123", "brut_kg": brut, "dara_kg": dara,
        "kalite": kalite or KALITE, "emanet": emanet})


def prim_kurali(client, aktif=True, baz=85, puan=2, azami=20, tur="DRY"):
    r = client.put(f"{API}/fiyatlar/catlak-primi/{tur}", json={
        "baz_catlak_orani": baz, "puan_basi_tl": puan, "azami_fark_tl": azami, "aktif": aktif})
    assert r.status_code == 200, r.text
    return r.json()


def ekstre(client, uretici_id):
    return client.get(f"{API}/ureticiler/{uretici_id}/bakiye-ekstre").json()


def test_emanet_siloya_girer_hakedis_ve_cari_hareket_olusmaz(client, uretici):
    fiyat_gir(client, A=300)
    r = teslim(client, uretici["id"], emanet=True)
    assert r.status_code == 201, r.text
    t = r.json()
    assert t["durum"] == "DEPOSITED" and t["emanet"] is True and t["teslim_sekli_adi"] == "Emanet"
    assert t["fiyat"] is None and t["hakedis"] is None
    assert t["kalite"]["sinif_kodu"] == "A" and t["stok"][0]["silo_kodu"] == "S-A1"
    e = ekstre(client, uretici["id"])
    assert Decimal(e["ozet"]["emanet_stok_kg"]) == 1000 and Decimal(e["ozet"]["bakiye"]) == 0 and e["hareketler"] == []
    assert e["teslimatlar"][0]["emanet"] is True
    b = next(x for x in client.get(f"{API}/raporlar/uretici-bakiyeleri").json() if x["uretici_id"] == uretici["id"])
    assert Decimal(b["emanet_kg"]) == 1000
    silo = next(s for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == "S-A1")
    assert Decimal(silo["stok_kg"]) == 1000 and Decimal(silo["emanet_kg"]) == 1000
    assert "emanet olarak teslim alınmıştır" in t["sms"]["mesaj"]


def test_emanet_fiyat_olmadan_da_alinabilir(client, uretici):
    assert teslim(client, uretici["id"]).status_code == 400                     # hemen satış: fiyat şart
    assert teslim(client, uretici["id"], emanet=True).status_code == 201       # emanet: fiyat gerekmez


def test_satisa_cevirme_o_gunun_fiyatiyla_ve_avans_mahsubuyla(client, uretici):
    fiyat_gir(client, A=300)
    t = teslim(client, uretici["id"], emanet=True).json()
    client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 10000, "odeme_yontemi": "CASH"})
    fiyat_gir(client, A=320)                                                    # fiyat sonradan yükseldi
    o = client.get(f"{API}/tartimlar/{t['id']}/satis-onizleme").json()
    assert Decimal(o["birim_fiyat"]) == 320 and Decimal(o["avans_mahsubu"]) == 10000
    r = client.post(f"{API}/tartimlar/{t['id']}/satisa-cevir")
    assert r.status_code == 200, r.text
    c = r.json()
    assert c["durum"] == "SETTLED" and c["emanetten_satis_zamani"] and Decimal(c["fiyat"]["birim_fiyat"]) == 320
    h = c["hakedis"]
    assert Decimal(h["brut_tutar"]) == 320000 and Decimal(h["avans_mahsubu_toplami"]) == 10000
    assert Decimal(h["net_odenecek"]) == Decimal(o["tahmini_net_odenecek"])
    assert "satışa çevrilmiştir" in c["sms"]["mesaj"]
    e = ekstre(client, uretici["id"])
    assert Decimal(e["ozet"]["emanet_stok_kg"]) == 0
    assert [x["tur"] for x in e["hareketler"]][:2] == ["ADVANCE", "DELIVERY"]
    # İkinci kez çevrilemez
    r2 = client.post(f"{API}/tartimlar/{t['id']}/satisa-cevir")
    assert r2.status_code == 400 and "zaten satışa çevrilmiş" in r2.json()["hata"]
    rapor = client.get(f"{API}/raporlar/gun-sonu").json()
    assert [d["fis_no"] for d in rapor["emanet_donusumleri"]] == [t["fis_no"]]


def test_satisa_cevirme_yetkisi_ve_fiyat_yoksa(client, anonim, uretici):
    t = teslim(client, uretici["id"], emanet=True).json()
    r = client.post(f"{API}/tartimlar/{t['id']}/satisa-cevir")
    assert r.status_code == 400 and "bugün" in r.json()["hata"]                 # A fiyatı hiç girilmedi
    anonim.headers["Authorization"] = "Bearer " + anonim.post(f"{API}/oturum/demo-giris", json={"kullanici_adi": "kantar"}).json()["token"]
    assert anonim.post(f"{API}/tartimlar/{t['id']}/satisa-cevir").status_code == 403
    satis = teslim(client, uretici["id"], kalite=KALITE)
    assert satis.status_code == 400                                             # (fiyat yok) – emanet olmayan fiş de çevrilemez


def test_sevkiyat_once_merkezin_kendi_malini_cikarir(client, uretici):
    fiyat_gir(client, A=300)
    emanet = teslim(client, uretici["id"], emanet=True).json()                  # önce emanet girdi
    kendi = teslim(client, uretici["id"]).json()
    silo = emanet["stok"][0]["silo_id"]
    s = client.post(f"{API}/sevkiyatlar", json={"musteri_adi": "Antep Fıstıkçılık", "plaka": "27 AF 270", "silo_id": silo,
                                                "brut_kg": 9000, "dara_kg": 8200, "birim_fiyat": 420, "tahsilat": "CASH"}).json()
    assert [(l["lot_no"], l["emanet"]) for l in s["lotlar"]] == [(kendi["lot_no"], False)]


def test_catlak_primi_ve_iskontosu(client, uretici):
    fiyat_gir(client, A=300, B=260)
    assert Decimal(teslim(client, uretici["id"]).json()["fiyat"]["birim_fiyat"]) == 300     # kural kapalı
    prim_kurali(client)                                                          # baz %85, 2 ₺/puan, ±20
    t = teslim(client, uretici["id"]).json()                                     # %90 çatlak → +10
    f = t["fiyat"]
    assert (Decimal(f["baz_fiyat"]), Decimal(f["catlak_primi"]), Decimal(f["birim_fiyat"])) == (300, 10, 310)
    assert Decimal(t["hakedis"]["brut_tutar"]) == 310000
    yuksek = teslim(client, uretici["id"], kalite=KALITE | {"catlak_orani": 99}).json()["fiyat"]
    assert Decimal(yuksek["catlak_primi"]) == 20                                 # azami +20 ile sınırlı
    o = client.post(f"{API}/tartimlar/onizleme", json={"brut_kg": 1000, "dara_kg": 0, "kalite": KALITE | {"catlak_orani": 80}}).json()
    assert o["kalite_kodu"] == "B"                                               # %80 çatlak B sınıfı
    assert Decimal(o["catlak_primi"]) == -10 and Decimal(o["birim_fiyat"]) == 250  # %80 → −10 iskonto (B 260)


def test_catlak_primi_yalnizca_fiyat_yoneticisi_degistirir(anonim, db_engine):
    anonim.headers["Authorization"] = "Bearer " + anonim.post(f"{API}/oturum/demo-giris", json={"kullanici_adi": "kantar"}).json()["token"]
    r = anonim.put(f"{API}/fiyatlar/catlak-primi/DRY", json={"baz_catlak_orani": 85, "puan_basi_tl": 2, "aktif": True})
    assert r.status_code == 403
    assert len(anonim.get(f"{API}/fiyatlar/catlak-primi").json()) == 2


def test_numune_gramlarindan_oran_hesaplanir(client, uretici, db_engine):
    fiyat_gir(client, A=300)
    k = {"numune_gram": 100, "catlak_gram": 91, "bos_gram": 2, "randiman": 58, "curuk_orani": 1, "nem_orani": 6}
    t = teslim(client, uretici["id"], kalite=k).json()
    q = t["kalite"]
    assert (Decimal(q["catlak_orani"]), Decimal(q["bos_orani"])) == (91, 2)
    assert (Decimal(q["numune_gram"]), Decimal(q["catlak_gram"]), Decimal(q["bos_gram"])) == (100, 91, 2)
    r = teslim(client, uretici["id"], kalite=k | {"catlak_gram": 120})
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "kalite.catlak_gram"


def test_veritabani_emanet_fisine_fiyat_yazilmasini_engeller(client, uretici, db_engine):
    fiyat_gir(client, A=300)
    t = teslim(client, uretici["id"], emanet=True).json()
    with pytest.raises(exc.DBAPIError):
        with db_engine.begin() as c:
            c.execute(text("UPDATE weigh_tickets SET daily_price_id = (SELECT max(id) FROM daily_prices), unit_price = 300, "
                           "priced_at = now() WHERE id = :i"), {"i": t["id"]})
