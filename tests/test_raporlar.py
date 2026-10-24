"""Müstahsil cari hesap dökümü ve gün sonu kasa & kantar raporu (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_raporlar.py
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from tests.conftest import fiyat_gir

API = "/api/v1"
TR = timezone(timedelta(hours=3))
KALITE = {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "curuk_orani": 1, "nem_orani": 6}
YAS = {"catlak_orani": 85, "randiman": 47, "bos_orani": 3, "curuk_orani": 1, "nem_orani": 35}


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Döküm ve gün sonu raporu yalnızca Java sürümünde var (--java ile çalıştırın)")


def teslimat(client, uretici_id, urun="DRY", brut=4800, dara=3800):
    r = client.post(f"{API}/tartimlar", json={
        "uretici_id": uretici_id, "plaka": "56 ABC 123", "brut_kg": brut, "dara_kg": dara, "urun_turu": urun,
        "kalite": YAS if urun == "FRESH" else KALITE})
    assert r.status_code == 201, r.text
    return r.json()


@pytest.fixture
def gun(client, uretici):
    """Bir kuru (1000 kg A) ve bir yaş (1000 kg YA) teslimat, nakit avans ve nakit + havale ödeme."""
    fiyat_gir(client, A=300, YA=135)
    a = client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 10000, "odeme_yontemi": "CASH"})
    assert a.status_code == 201, a.text
    kuru = teslimat(client, uretici["id"])
    yas = teslimat(client, uretici["id"], urun="FRESH", brut=2500, dara=1500)
    for hakedis, tutar, yontem in ((kuru["hakedis"]["id"], 50000, "CASH"), (yas["hakedis"]["id"], 20000, "BANK_TRANSFER")):
        r = client.post(f"{API}/hakedisler/{hakedis}/odeme-yap", json={"tutar": tutar, "odeme_yontemi": yontem})
        assert r.status_code == 201, r.text
    return {"kuru": kuru, "yas": yas}


def test_dokum_teslimat_avans_ve_odemeleri_listeler(client, uretici, gun):
    e = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()
    assert [t["urun_turu"] for t in e["teslimatlar"]] == ["DRY", "FRESH"]
    assert e["teslimatlar"][1]["urun_turu_adi"] == "Yaş fıstık"
    assert [Decimal(a["tutar"]) for a in e["avanslar"]] == [Decimal("10000")]
    assert sorted(Decimal(o["tutar"]) for o in e["odemeler"]) == [Decimal("20000"), Decimal("50000")]
    assert Decimal(e["hareketler"][-1]["bakiye"]) == Decimal(e["ozet"]["bakiye"])   # yürüyen bakiye = net bakiye
    # Gelecekteki bir dönem: hareket yok, devreden bakiye bugünkü bakiyedir
    yarin = (datetime.now(TR) + timedelta(days=1)).date().isoformat()
    d = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre", params={"baslangic": yarin}).json()
    assert d["teslimatlar"] == [] and d["avanslar"] == [] and d["odemeler"] == []
    assert Decimal(d["devreden_bakiye"]) == Decimal(e["ozet"]["bakiye"])


def test_gun_sonu_kasa_ve_hakedis(client, gun):
    r = client.get(f"{API}/raporlar/gun-sonu").json()
    k = r["kasa"]
    assert Decimal(k["nakit_odeme"]) == Decimal("50000") and Decimal(k["havale_odeme"]) == Decimal("20000")
    assert Decimal(k["nakit_avans"]) == Decimal("10000")
    assert Decimal(k["kasadan_cikan_nakit"]) == Decimal("60000")          # nakit ödeme + nakit avans
    net = Decimal(gun["kuru"]["hakedis"]["net_odenecek"]) + Decimal(gun["yas"]["hakedis"]["net_odenecek"])
    assert Decimal(k["gunun_net_hakedisi"]) == net
    assert Decimal(k["gunun_kalan_hakedisi"]) == net - Decimal("70000")
    assert len(r["teslimatlar"]) == 2 and len(r["kasa_hareketleri"]) == 3
    turler = {u["urun_turu"]: u for u in r["urun_turleri"]}
    assert Decimal(turler["DRY"]["net_kg"]) == Decimal("1000") and Decimal(turler["FRESH"]["net_kg"]) == Decimal("1000")
    silolar = {s["silo_kodu"]: Decimal(s["giren_kg"]) for s in r["silo_girisleri"]}
    assert silolar == {"S-A1": Decimal("1000"), "S-YA": Decimal("1000")}


def test_gun_sonu_csv_excel_uyumlu(client, gun):
    r = client.get(f"{API}/raporlar/gun-sonu.csv")
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv")
    assert "attachment" in r.headers["content-disposition"] and ".csv" in r.headers["content-disposition"]
    assert r.content.startswith(b"\xef\xbb\xbf")                           # UTF-8 BOM: Excel Türkçe karakterleri bozmaz
    metin = r.content.decode("utf-8-sig")
    assert "GÜN SONU KASA VE KANTAR RAPORU" in metin and "Yaş fıstık" in metin
    assert gun["kuru"]["fis_no"] in metin and gun["yas"]["fis_no"] in metin
    assert "Kasadan çıkan nakit (TL);60000,00" in metin                    # ';' ayraç, ondalık virgül
    assert "\r\n" in metin


def test_gun_sonu_bos_gun_ve_gecersiz_tarih(client):
    gecmis = client.get(f"{API}/raporlar/gun-sonu", params={"tarih": "2020-01-01"}).json()
    assert gecmis["teslimatlar"] == [] and Decimal(gecmis["kasa"]["toplam_odeme"]) == 0
    assert "Bu gün teslimat yok." in client.get(f"{API}/raporlar/gun-sonu.csv", params={"tarih": "2020-01-01"}).text
    assert client.get(f"{API}/raporlar/gun-sonu", params={"tarih": "2020-13-45"}).status_code == 422
