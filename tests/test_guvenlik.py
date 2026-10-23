"""Kimlik doğrulama ve rol yetkileri (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_guvenlik.py
"""
import httpx
import pytest

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Kimlik doğrulama yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def giris_yap(c, kullanici):
    r = c.post(f"{API}/oturum/demo-giris", json={"kullanici_adi": kullanici})
    assert r.status_code == 200, r.text
    c.headers["Authorization"] = f"Bearer {r.json()['token']}"
    return r.json()["kullanici"]


def test_acik_sayfalar_giris_istemez(anonim):
    for yol in ("/", "/saglik", "/docs", "/openapi.json", "/static/js/app.js", f"{API}/oturum/demo-hesaplar"):
        assert anonim.get(yol).status_code == 200, yol


def test_api_giris_olmadan_401(anonim):
    for yontem, yol in (("GET", f"{API}/ureticiler"), ("GET", f"{API}/raporlar/gunluk-ozet"),
                        ("POST", f"{API}/tartimlar"), ("GET", f"{API}/oturum/ben")):
        r = anonim.request(yontem, yol, json={} if yontem == "POST" else None)
        assert r.status_code == 401, (yol, r.status_code)
        assert "Oturum açmanız gerekiyor" in r.json()["hata"]


def test_sahte_veya_bozuk_token_401(anonim):
    r = anonim.get(f"{API}/ureticiler", headers={"Authorization": "Bearer abc.def.ghi"})
    assert r.status_code == 401


def test_yanlis_sifre_401_ve_kullanici_adi_sizdirmaz(anonim):
    r1 = anonim.post(f"{API}/oturum/giris", json={"kullanici_adi": "admin", "sifre": "yanlis-sifre"})
    r2 = anonim.post(f"{API}/oturum/giris", json={"kullanici_adi": "olmayan-kisi", "sifre": "yanlis-sifre"})
    assert r1.status_code == r2.status_code == 401
    assert r1.json()["hata"] == r2.json()["hata"] == "Kullanıcı adı veya şifre hatalı."


def test_demo_hesaplari_listelenir_sifre_icermez(anonim):
    hesaplar = anonim.get(f"{API}/oturum/demo-hesaplar").json()
    assert {h["rol"] for h in hesaplar} >= {"ADMIN", "SCALE_OPERATOR", "ACCOUNTING", "PRICE_MANAGER", "WAREHOUSE"}
    assert all("sifre" not in h and "password" not in str(h).lower() for h in hesaplar)


def test_ben_rol_ve_yetkileri_doner(anonim):
    k = giris_yap(anonim, "kantar")
    assert k["rol"] == "SCALE_OPERATOR" and k["rol_adi"] == "Kantar memuru" and k["yetkiler"] == ["tartim"]
    ben = anonim.get(f"{API}/oturum/ben").json()
    assert ben["kullanici_adi"] == "kantar"


def test_kantar_memuru_tartim_yapar_ama_odeme_ve_fiyat_yapamaz(client, anonim, uretici):
    fiyat_gir(client, A=300)                                  # yönetici fiyat girer
    giris_yap(anonim, "kantar")
    t = tartim(anonim, uretici["id"])
    assert t.status_code == 201, t.text
    h = t.json()["hakedis"]
    r = anonim.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={})
    assert r.status_code == 403 and "rolünüz: Kantar memuru" in r.json()["hata"]
    assert anonim.post(f"{API}/fiyatlar", json={"fiyatlar": [{"kalite_kodu": "A", "birim_fiyat": 1}]}).status_code == 403
    assert anonim.get(f"{API}/raporlar/gunluk-ozet").status_code == 200   # okuma herkese açık


def test_muhasebe_odeme_yapar_ama_tartim_yapamaz(client, anonim, uretici):
    fiyat_gir(client, A=300)
    h = tartim(client, uretici["id"]).json()["hakedis"]
    giris_yap(anonim, "muhasebe")
    assert tartim(anonim, uretici["id"]).status_code == 403
    r = anonim.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={"tutar": 1000})
    assert r.status_code == 201, r.text


def test_islemi_yapan_kullanici_kaydedilir(client, anonim, db_engine, uretici):
    from sqlalchemy import text
    fiyat_gir(client, A=300)
    kantar = giris_yap(anonim, "kantar")
    t = tartim(anonim, uretici["id"]).json()
    with db_engine.connect() as c:
        tartan = c.execute(text("SELECT weighed_by_id FROM weigh_tickets WHERE id = :i"), {"i": t["id"]}).scalar()
    assert tartan == kantar["id"]


def test_depo_sorumlusu_yalnizca_okur(anonim, uretici):
    giris_yap(anonim, "depo")
    assert anonim.get(f"{API}/depo/stok-durumu").status_code == 200
    assert anonim.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 100}).status_code == 403
