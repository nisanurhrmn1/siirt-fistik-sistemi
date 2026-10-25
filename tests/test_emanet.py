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
    assert c["kaynak_fis_no"] == t["fis_no"] and c["fis_no"].startswith("EB-") and Decimal(c["emanet_kalan_kg"]) == 0
    rapor = client.get(f"{API}/raporlar/gun-sonu").json()
    assert [(d["fis_no"], d["kaynak_fis_no"]) for d in rapor["emanet_donusumleri"]] == [(c["fis_no"], t["fis_no"])]


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


def test_parcali_satis_kalan_emanette_kalir_ve_lot_bolunur(client, uretici):
    fiyat_gir(client, A=300)
    e = teslim(client, uretici["id"], emanet=True, brut=4400, dara=3200).json()             # 1.200 kg emanet
    assert Decimal(e["emanet_kalan_kg"]) == 1200
    o = client.get(f"{API}/tartimlar/{e['id']}/satis-onizleme", params={"miktar_kg": 400}).json()
    assert Decimal(o["net_kg"]) == 400 and Decimal(o["brut_tutar"]) == 120000
    c1 = client.post(f"{API}/tartimlar/{e['id']}/satisa-cevir", json={"miktar_kg": 400}).json()
    assert c1["fis_no"].startswith("EB-") and Decimal(c1["net_kg"]) == 400 and Decimal(c1["emanet_kalan_kg"]) == 800
    assert Decimal(c1["hakedis"]["brut_tutar"]) == 120000 and c1["kalite"]["sinif_kodu"] == "A"
    assert "emanetinizden 400 kg" in c1["sms"]["mesaj"] and "Kalan emanetiniz: 800 kg" in c1["sms"]["mesaj"]
    # Üretici emanet stoğu ve silodaki emanet satılan kadar düşer; toplam stok değişmez
    ek = ekstre(client, uretici["id"])
    assert Decimal(ek["ozet"]["emanet_stok_kg"]) == 800
    silo = next(s for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == "S-A1")
    assert Decimal(silo["stok_kg"]) == 1200 and Decimal(silo["emanet_kg"]) == 800
    # Kalandan fazlası satılamaz; kalanın tamamı (gövdesiz) satılabilir
    fazla = client.post(f"{API}/tartimlar/{e['id']}/satisa-cevir", json={"miktar_kg": 900})
    assert fazla.status_code == 400 and "800 kg" in fazla.json()["hata"]
    fiyat_gir(client, A=320)
    c2 = client.post(f"{API}/tartimlar/{e['id']}/satisa-cevir").json()
    assert Decimal(c2["net_kg"]) == 800 and Decimal(c2["fiyat"]["birim_fiyat"]) == 320 and Decimal(c2["emanet_kalan_kg"]) == 0
    assert Decimal(ekstre(client, uretici["id"])["ozet"]["emanet_stok_kg"]) == 0
    silo = next(s for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == "S-A1")
    assert Decimal(silo["emanet_kg"]) == 0 and Decimal(silo["stok_kg"]) == 1200
    assert client.get(f"{API}/tartimlar/{e['id']}").json()["durum"] == "DEPOSITED"   # emanet belgesi kalır
    assert client.post(f"{API}/tartimlar/{e['id']}/satisa-cevir").status_code == 400


def test_bos_tane_iskontosu(client, uretici):
    fiyat_gir(client, A=300)
    r = client.put(f"{API}/fiyatlar/catlak-primi/DRY", json={
        "baz_catlak_orani": 85, "puan_basi_tl": 2, "azami_fark_tl": 20, "aktif": True,
        "bos_esik_orani": 3, "bos_puan_basi_tl": 5, "bos_aktif": True})
    assert r.status_code == 200 and r.json()["bos_aktif"] is True
    # %90 çatlak (+10), %2 boş: eşik altı, kesinti yok
    f = teslim(client, uretici["id"]).json()["fiyat"]
    assert (Decimal(f["catlak_primi"]), Decimal(f["bos_iskontosu"]), Decimal(f["birim_fiyat"])) == (10, 0, 310)
    # %90 çatlak (+10), %5 boş: (5 − 3) × 5 = −10 → 300
    k = KALITE | {"bos_orani": 5, "curuk_orani": 0}
    t = teslim(client, uretici["id"], kalite=k).json()
    assert t["kalite"]["sinif_kodu"] == "A"
    f = t["fiyat"]
    assert (Decimal(f["baz_fiyat"]), Decimal(f["catlak_primi"]), Decimal(f["bos_iskontosu"]), Decimal(f["birim_fiyat"])) == (300, 10, 10, 300)
    o = client.post(f"{API}/tartimlar/onizleme", json={"brut_kg": 1000, "dara_kg": 0, "kalite": k}).json()
    assert Decimal(o["bos_iskontosu"]) == 10 and any("boş tane kesintisi" in u for u in o["uyarilar"])
