"""Alıcı tahsilatları, alıcı cari ekstresi ve resmî kesinti ayarları (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_tahsilat.py
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import httpx
import pytest
from sqlalchemy import exc, text

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"
TR = timezone(timedelta(hours=3))


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Tahsilat ve kesinti ayarları yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def giris_yap(c, kullanici):
    c.headers["Authorization"] = "Bearer " + c.post(f"{API}/oturum/demo-giris", json={"kullanici_adi": kullanici}).json()["token"]


@pytest.fixture
def alici(client, uretici):
    """Antep Fıstıkçılık: 500 kg vadeli (210.000) + 500 kg peşin havale (210.000)."""
    fiyat_gir(client, A=300)
    tartim(client, uretici["id"], brut=4800, dara=3800)
    silo = next(s["silo_id"] for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == "S-A1")
    for tahsilat in ("ON_ACCOUNT", "BANK_TRANSFER"):
        r = client.post(f"{API}/sevkiyatlar", json={"musteri_adi": "Antep Fıstıkçılık A.Ş.", "plaka": "27 AF 270", "silo_id": silo,
                                                    "brut_kg": 8700, "dara_kg": 8200, "birim_fiyat": 420, "tahsilat": tahsilat})
        assert r.status_code == 201, r.text
    return client.get(f"{API}/musteriler").json()[0]


def tahsil(c, musteri_id, tutar, **ek):
    return c.post(f"{API}/musteriler/{musteri_id}/tahsilatlar", json={"tutar": tutar, "odeme_yontemi": "BANK_TRANSFER"} | ek)


def test_acik_hesap_ve_tahsilat(client, alici):
    assert (Decimal(alici["toplam_tutar"]), Decimal(alici["vadeli_satis"]), Decimal(alici["tahsil_edilen"]),
            Decimal(alici["acik_hesap"])) == (420000, 210000, 210000, 210000)
    r = tahsil(client, alici["id"], 150000, referans_no="EFT-1051", aciklama="Ekim ödemesi")
    assert r.status_code == 201, r.text
    t = r.json()
    assert t["makbuz_no"].startswith("TH-") and Decimal(t["kalan_acik_hesap"]) == 60000
    m = client.get(f"{API}/musteriler").json()[0]
    assert (Decimal(m["tahsilat_toplami"]), Decimal(m["tahsil_edilen"]), Decimal(m["acik_hesap"])) == (150000, 360000, 60000)
    # Açık hesaptan fazla tahsilat alıcı avansı olur (bakiye eksiye döner)
    assert Decimal(tahsil(client, alici["id"], 100000, odeme_yontemi="CHEQUE").json()["kalan_acik_hesap"]) == -40000


def test_alici_ekstresi_yuruyen_bakiye(client, alici):
    tahsil(client, alici["id"], 150000, referans_no="EFT-1051")
    e = client.get(f"{API}/musteriler/{alici['id']}/ekstre").json()
    turler = [h["tur"] for h in e["hareketler"]]
    assert turler == ["SATIS", "SATIS", "PESIN", "TAHSILAT"]
    assert [Decimal(h["bakiye"]) for h in e["hareketler"]] == [210000, 420000, 210000, 60000]
    assert e["hareketler"][0]["belge_no"].startswith("SV-") and Decimal(e["hareketler"][0]["kg"]) == 500
    assert e["hareketler"][3]["aciklama"] == "EFT-1051" and e["hareketler"][3]["odeme_yontemi"] == "BANK_TRANSFER"
    # Gelecek dönem: hareket yok, devreden bakiye açık hesap
    yarin = (datetime.now(TR) + timedelta(days=1)).date().isoformat()
    d = client.get(f"{API}/musteriler/{alici['id']}/ekstre", params={"baslangic": yarin}).json()
    assert d["hareketler"] == [] and Decimal(d["devreden_bakiye"]) == 60000


def test_tahsilat_dogrulamalari_ve_tarih(client, alici):
    assert tahsil(client, alici["id"], 0).status_code == 422
    assert tahsil(client, alici["id"], 100, odeme_yontemi=None).status_code == 422
    yarin = (datetime.now(TR) + timedelta(days=1)).date().isoformat()
    r = tahsil(client, alici["id"], 100, tarih=yarin)
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "tarih"
    dun = (datetime.now(TR) - timedelta(days=1)).date().isoformat()
    t = tahsil(client, alici["id"], 100, tarih=dun).json()
    assert t["tarih"].startswith(dun)
    assert tahsil(client, 999999, 100).status_code == 404


def test_tahsilat_yetkisi(anonim, alici):
    for kullanici in ("kantar", "depo", "fiyat"):
        giris_yap(anonim, kullanici)
        assert tahsil(anonim, alici["id"], 100).status_code == 403, kullanici
    giris_yap(anonim, "muhasebe")
    assert tahsil(anonim, alici["id"], 100).status_code == 201


def test_tahsilat_kaydi_degistirilemez(client, alici, db_engine):
    t = tahsil(client, alici["id"], 1000).json()
    with pytest.raises(exc.DBAPIError, match="değiştirilemez"):
        with db_engine.begin() as c:
            c.execute(text("UPDATE customer_payments SET amount = 1 WHERE id = :i"), {"i": t["id"]})


def test_gun_sonu_alici_tahsilati_kasaya_girer(client, alici):
    tahsil(client, alici["id"], 50000, odeme_yontemi="CASH")
    k = client.get(f"{API}/raporlar/gun-sonu").json()["kasa"]
    assert Decimal(k["alici_nakit_tahsilat"]) == 50000 and Decimal(k["alici_tahsilat_toplami"]) == 50000
    assert Decimal(k["kasa_net_nakit"]) == 50000                     # nakit satış yok, nakit ödeme yok
    csv = client.get(f"{API}/raporlar/gun-sonu.csv").content.decode("utf-8-sig")
    assert "ALICI TAHSİLATLARI" in csv and "Alıcı tahsilatı (giriş)" in csv


def test_resmi_kesinti_ayarlari(client, anonim, uretici):
    kesintiler = {k["kod"]: k for k in client.get(f"{API}/kesintiler").json()}
    assert set(kesintiler) >= {"STOPAJ", "BORSA", "KOMISYON", "BAGKUR"}
    assert kesintiler["STOPAJ"]["aktif"] and not kesintiler["BAGKUR"]["aktif"]
    fiyat_gir(client, A=300)
    once = tartim(client, uretici["id"]).json()["hakedis"]               # 300.000 brüt; stopaj 2, borsa 0,2, komisyon 1
    assert Decimal(once["kesinti_toplami"]) == Decimal("9600.00")
    # Komisyonu kapat, Bağ-Kur %2 aç, stopajı %1 yap
    assert client.put(f"{API}/kesintiler/KOMISYON", json={"oran": 1, "aktif": False}).status_code == 200
    assert client.put(f"{API}/kesintiler/BAGKUR", json={"oran": 2, "aktif": True}).status_code == 200
    assert client.put(f"{API}/kesintiler/stopaj", json={"oran": 1, "aktif": True}).json()["oran"] in ("1", "1.0000", "1.00")
    sonra = tartim(client, uretici["id"]).json()["hakedis"]
    kodlar = {k["kod"]: Decimal(k["tutar"]) for k in sonra["kesintiler"]}
    assert kodlar == {"STOPAJ": 3000, "BORSA": 600, "BAGKUR": 6000}
    assert Decimal(sonra["net_odenecek"]) == Decimal(sonra["brut_tutar"]) - 9600
    # Önceki hakedişin kesintileri değişmez (oran snapshot'ı)
    assert Decimal(client.get(f"{API}/hakedisler/{once['id']}").json()["kesinti_toplami"]) == Decimal("9600.00")
    assert client.put(f"{API}/kesintiler/STOPAJ", json={"oran": 150, "aktif": True}).status_code == 422
    assert client.put(f"{API}/kesintiler/YOK", json={"oran": 1, "aktif": True}).status_code == 404
    giris_yap(anonim, "kantar")
    assert anonim.put(f"{API}/kesintiler/STOPAJ", json={"oran": 0, "aktif": False}).status_code == 403
