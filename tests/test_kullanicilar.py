"""Hesap talebi, kullanıcı yönetimi ve şifre işlemleri (yalnızca Java sürümü).

Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_kullanicilar.py
"""
import httpx
import pytest

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"
SIFRE = "Deneme-1234"          # test şifresi (yalnızca test veritabanında)
YENI_SIFRE = "Yeni-Sifre-5678"


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Kullanıcı yönetimi yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


def talep(c, kullanici_adi="ayse.kaya", rol="SCALE_OPERATOR", sifre=SIFRE, **ek):
    return c.post(f"{API}/oturum/hesap-talebi", json={
        "kullanici_adi": kullanici_adi, "ad_soyad": "Ayşe Kaya", "istenen_rol": rol, "sifre": sifre} | ek)


def giris(c, kullanici_adi, sifre):
    c.headers.pop("Authorization", None)     # eski (geçersizleşmiş) token gönderilmesin
    return c.post(f"{API}/oturum/giris", json={"kullanici_adi": kullanici_adi, "sifre": sifre})


def oturum_ac(c, kullanici_adi, sifre):
    r = giris(c, kullanici_adi, sifre)
    assert r.status_code == 200, r.text
    c.headers["Authorization"] = f"Bearer {r.json()['token']}"
    return r.json()["kullanici"]


def kullanici_bul(client, kullanici_adi):
    return next(u for u in client.get(f"{API}/kullanicilar").json() if u["kullanici_adi"] == kullanici_adi)


def yeni_kullanici(client, kullanici_adi="mehmet.oz", rol="SCALE_OPERATOR"):
    r = client.post(f"{API}/kullanicilar", json={
        "kullanici_adi": kullanici_adi, "ad_soyad": "Mehmet Öz", "rol": rol, "gecici_sifre": SIFRE})
    assert r.status_code == 201, r.text
    return r.json()


def test_talep_onaylanmadan_giris_yapilamaz_onaydan_sonra_yapilir(client, anonim):
    r = talep(anonim, kullanici_adi="  Ayse.Kaya ", aciklama="Kurtalan kantarında çalışıyorum.")
    assert r.status_code == 201, r.text
    r = giris(anonim, "ayse.kaya", SIFRE)
    assert r.status_code == 401 and "onaylanmadı" in r.json()["hata"]

    u = kullanici_bul(client, "ayse.kaya")                    # kullanıcı adı küçük harfe çevrildi
    assert u["durum"] == "BEKLIYOR" and u["aciklama"] == "Kurtalan kantarında çalışıyorum."
    r = client.post(f"{API}/kullanicilar/{u['id']}/onayla", json={"rol": "QUALITY"})   # yönetici rolü değiştirerek onaylar
    assert r.status_code == 200 and r.json()["durum"] == "AKTIF" and r.json()["rol"] == "QUALITY"

    k = oturum_ac(anonim, "ayse.kaya", SIFRE)
    assert k["rol"] == "QUALITY" and k["sifre_degistirmeli"] is False


def test_talep_dogrulamalari(client, anonim):
    r = talep(anonim, rol="ADMIN")
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "istenen_rol"
    r = talep(anonim, sifre="kisa")
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "sifre"
    assert talep(anonim, kullanici_adi="a b").status_code == 422
    assert talep(anonim, kullanici_adi="admin").status_code == 409        # mevcut kullanıcı adı
    assert talep(anonim).status_code == 201
    assert talep(anonim).status_code == 409                              # aynı talep ikinci kez


def test_talep_reddedilince_silinir_ve_ad_yeniden_kullanilabilir(client, anonim):
    talep(anonim)
    u = kullanici_bul(client, "ayse.kaya")
    assert client.delete(f"{API}/kullanicilar/{u['id']}/talep").status_code == 204
    assert all(x["kullanici_adi"] != "ayse.kaya" for x in client.get(f"{API}/kullanicilar").json())
    assert talep(anonim).status_code == 201
    # Aktif kullanıcı "reddedilemez"; pasifleştirilmelidir
    admin = kullanici_bul(client, "admin")
    assert client.delete(f"{API}/kullanicilar/{admin['id']}/talep").status_code == 400


def test_kullanici_yonetimi_yalnizca_yoneticiye_acik(anonim):
    assert anonim.get(f"{API}/kullanicilar").status_code == 401
    r = anonim.post(f"{API}/oturum/demo-giris", json={"kullanici_adi": "muhasebe"})
    anonim.headers["Authorization"] = f"Bearer {r.json()['token']}"
    assert anonim.get(f"{API}/kullanicilar").status_code == 403
    assert anonim.post(f"{API}/kullanicilar", json={}).status_code == 403
    assert "kullanici_yonet" not in anonim.get(f"{API}/oturum/ben").json()["yetkiler"]


def test_gecici_sifre_ilk_giriste_degistirilir(client, anonim):
    yeni_kullanici(client)
    k = oturum_ac(anonim, "mehmet.oz", SIFRE)
    assert k["sifre_degistirmeli"] is True

    r = anonim.post(f"{API}/oturum/sifre-degistir", json={"mevcut_sifre": "yanlis-sifre", "yeni_sifre": YENI_SIFRE})
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "mevcut_sifre"
    r = anonim.post(f"{API}/oturum/sifre-degistir", json={"mevcut_sifre": SIFRE, "yeni_sifre": SIFRE})
    assert r.status_code == 422 and r.json()["detaylar"][0]["alan"] == "yeni_sifre"
    r = anonim.post(f"{API}/oturum/sifre-degistir", json={"mevcut_sifre": SIFRE, "yeni_sifre": YENI_SIFRE})
    assert r.status_code == 200, r.text

    assert giris(anonim, "mehmet.oz", SIFRE).status_code == 401
    assert oturum_ac(anonim, "mehmet.oz", YENI_SIFRE)["sifre_degistirmeli"] is False

    # Yönetici şifreyi sıfırlarsa yeniden geçici şifre olur
    u = kullanici_bul(client, "mehmet.oz")
    r = client.post(f"{API}/kullanicilar/{u['id']}/sifre-sifirla", json={"yeni_sifre": SIFRE})
    assert r.status_code == 200 and r.json()["sifre_degistirmeli"] is True
    assert oturum_ac(anonim, "mehmet.oz", SIFRE)["sifre_degistirmeli"] is True


def test_pasiflestirme_acik_oturumu_hemen_kapatir(client, anonim):
    u = yeni_kullanici(client)
    oturum_ac(anonim, "mehmet.oz", SIFRE)
    assert anonim.get(f"{API}/raporlar/gunluk-ozet").status_code == 200

    assert client.patch(f"{API}/kullanicilar/{u['id']}", json={"aktif": False}).json()["durum"] == "PASIF"
    assert anonim.get(f"{API}/raporlar/gunluk-ozet").status_code == 401       # token süresi dolmadan
    r = giris(anonim, "mehmet.oz", SIFRE)
    assert r.status_code == 401 and "pasif" in r.json()["hata"]

    client.patch(f"{API}/kullanicilar/{u['id']}", json={"aktif": True})
    assert giris(anonim, "mehmet.oz", SIFRE).status_code == 200


def test_rol_degisikligi_acik_oturumda_hemen_gecerli(client, anonim, uretici):
    fiyat_gir(client, A=300)
    u = yeni_kullanici(client, rol="SCALE_OPERATOR")
    oturum_ac(anonim, "mehmet.oz", SIFRE)
    assert tartim(anonim, uretici["id"]).status_code == 201

    client.patch(f"{API}/kullanicilar/{u['id']}", json={"rol": "ACCOUNTING"})
    r = tartim(anonim, uretici["id"])                                          # aynı token
    assert r.status_code == 403 and "rolünüz: Muhasebe" in r.json()["hata"]
    assert anonim.get(f"{API}/oturum/ben").json()["rol"] == "ACCOUNTING"


def test_demo_hesaplari_ve_kendi_hesabi_korunur(client):
    admin, kantar = kullanici_bul(client, "admin"), kullanici_bul(client, "kantar")
    assert admin["demo_hesabi"] and kantar["demo_hesabi"]
    r = client.patch(f"{API}/kullanicilar/{admin['id']}", json={"aktif": False})
    assert r.status_code == 400
    assert client.patch(f"{API}/kullanicilar/{kantar['id']}", json={"rol": "ADMIN"}).status_code == 400
    assert client.post(f"{API}/kullanicilar/{kantar['id']}/sifre-sifirla", json={"yeni_sifre": SIFRE}).status_code == 400
    assert client.post(f"{API}/oturum/sifre-degistir", json={"mevcut_sifre": "x", "yeni_sifre": YENI_SIFRE}).status_code == 400


def test_yonetici_kendi_rolunu_dusuremez(client, anonim):
    u = yeni_kullanici(client, kullanici_adi="ikinci.yonetici", rol="ADMIN")
    oturum_ac(anonim, "ikinci.yonetici", SIFRE)
    r = anonim.patch(f"{API}/kullanicilar/{u['id']}", json={"rol": "ACCOUNTING"})
    assert r.status_code == 400 and "Kendi rolünüzü" in r.json()["hata"]
    # Yönetici ikinci yöneticiyi düşürebilir (aktif yönetici sayısı 2)
    assert client.patch(f"{API}/kullanicilar/{u['id']}", json={"rol": "ACCOUNTING"}).status_code == 200
