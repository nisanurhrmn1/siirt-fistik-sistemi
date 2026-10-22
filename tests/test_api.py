from decimal import Decimal as D

from tests.conftest import IBAN, fiyat_gir, tartim

API = "/api/v1"


def alanlar(r):
    return {d["alan"]: d["mesaj"] for d in r.json()["detaylar"]}


# ─── Üretici ───

def test_uretici_kaydi_normalize_eder(uretici):
    assert uretici["telefon"] == "05321234567"
    assert uretici["banka_hesaplari"][0]["iban"] == IBAN
    assert uretici["banka_hesaplari"][0]["varsayilan"] is True


def test_uretici_dogrulama_hatalari_422(client):
    r = client.post(f"{API}/ureticiler", json={
        "tc_kimlik_no": "12345678901", "ad_soyad": "X Y Z", "telefon": "123",
        "banka_hesabi": {"banka_adi": "Ziraat", "iban": "TR000006100519786457841326"}})
    assert r.status_code == 422
    hatalar = alanlar(r)
    assert "TC Kimlik No geçersiz" in hatalar["tc_kimlik_no"]
    assert "telefon" in hatalar and "banka_hesabi.iban" in hatalar


def test_tuzel_kisi_vkn_zorunlu_ve_tanimsiz_alan(client):
    govde = {"tip": "COMPANY", "ad_soyad": "Fıstık A.Ş.", "telefon": "04842234567"}
    r = client.post(f"{API}/ureticiler", json=govde)
    assert r.status_code == 422 and "vergi_no zorunludur" in alanlar(r)["istek"]
    r = client.post(f"{API}/ureticiler", json=govde | {"vergi_no": "1234567890", "fazla": 1})
    assert r.status_code == 422 and "Tanımsız alan" in alanlar(r)["fazla"]


def test_ayni_tc_ikinci_kayit_409(client, uretici):
    r = client.post(f"{API}/ureticiler", json={"tc_kimlik_no": "10000000146", "ad_soyad": "Başka Biri",
                                               "telefon": "05320000000"})
    assert r.status_code == 409
    assert f"id={uretici['id']}" in r.json()["hata"]


def test_uretici_listele_ve_404(client, uretici):
    r = client.get(f"{API}/ureticiler", params={"arama": "Ahmet"})
    assert r.status_code == 200 and r.json()["toplam"] == 1
    assert client.get(f"{API}/ureticiler/{uretici['id']}").status_code == 200
    r = client.get(f"{API}/ureticiler/999")
    assert r.status_code == 404 and "bulunamadı" in r.json()["hata"]


# ─── Fiyat ───

def test_fiyat_girisi_ve_guncel(client):
    fiyat_gir(client, A=300, B=260)
    kalemler = {k["kalite_kodu"]: k["fiyat"] for k in client.get(f"{API}/fiyatlar/guncel").json()["kalemler"]}
    assert D(kalemler["A"]["birim_fiyat"]) == 300 and kalemler["ISK"] is None


def test_fiyat_hatalari(client):
    r = client.post(f"{API}/fiyatlar", json={"fiyatlar": [{"kalite_kodu": "Z", "birim_fiyat": 10}]})
    assert r.status_code == 404
    r = client.post(f"{API}/fiyatlar", json={"fiyatlar": [{"kalite_kodu": "A", "birim_fiyat": 10}],
                                             "gecerlilik_baslangici": "2020-01-01T00:00:00+03:00"})
    assert r.status_code == 400 and "onay anından önce" in r.json()["hata"]
    r = client.post(f"{API}/fiyatlar", json={"fiyatlar": [{"kalite_kodu": "A", "birim_fiyat": -5}]})
    assert r.status_code == 422


# ─── Tartım ───

def test_tartim_validasyonlari_422(client, uretici):
    r = tartim(client, uretici["id"], brut=-10)
    assert r.status_code == 422 and "brut_kg" in alanlar(r)
    r = tartim(client, uretici["id"], nem_orani=120, randiman=-1)
    assert r.status_code == 422
    assert set(alanlar(r)) >= {"kalite.nem_orani", "kalite.randiman"}
    r = tartim(client, uretici["id"], brut=3000, dara=3000)
    assert r.status_code == 422 and "Dara" in alanlar(r)["istek"]


def test_fiyat_yoksa_tartim_400(client, uretici):
    r = tartim(client, uretici["id"])
    assert r.status_code == 400 and "onaylı fiyat yok" in r.json()["hata"]


def test_tartim_fiyat_kilidi_hakedis_ve_stok(client, uretici):
    fiyat_gir(client, A=300, B=260, ISK=150)
    r1 = tartim(client, uretici["id"])  # net 1000 kg, A sınıfı
    assert r1.status_code == 201, r1.text
    t1 = r1.json()
    assert t1["kalite"]["sinif_kodu"] == "A" and D(t1["net_kg"]) == 1000
    assert D(t1["fiyat"]["birim_fiyat"]) == 300
    h = t1["hakedis"]
    assert D(h["brut_tutar"]) == D("300000") and D(h["kesinti_toplami"]) == D("9600")
    assert D(h["net_odenecek"]) == D("290400") and h["durum"] == "UNPAID"
    assert {k["kod"] for k in h["kesintiler"]} == {"STOPAJ", "BORSA", "KOMISYON"}
    assert t1["stok"] == [{"silo_id": 1, "silo_kodu": "S-A1", "miktar_kg": "1000.00"}]

    # Fiyat değişir; yeni tartım yeni fiyatı, eski tartım kilitli fiyatı korur
    fiyat_gir(client, A=320)
    t2 = tartim(client, uretici["id"]).json()
    assert t2["fis_no"] != t1["fis_no"] and t2["lot_no"] != t1["lot_no"]  # aynı gün ikinci teslimat
    assert D(t2["fiyat"]["birim_fiyat"]) == 320
    assert D(client.get(f"{API}/tartimlar/{t1['id']}").json()["fiyat"]["birim_fiyat"]) == 300

    # Düşük kalite → B silosuna gider
    t3 = tartim(client, uretici["id"], catlak_orani=75, randiman=50).json()
    assert t3["kalite"]["sinif_kodu"] == "B" and t3["stok"][0]["silo_kodu"] == "S-B1"


# ─── Avans, hakediş, ödeme ───

def test_avans_teslimattan_buyukse_eksi_bakiye_ve_mahsup(client, uretici):
    fiyat_gir(client, A=300)
    r = client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 500000})
    assert r.status_code == 201 and D(r.json()["uretici_guncel_bakiye"]) == D("-500000")

    h1 = tartim(client, uretici["id"]).json()["hakedis"]
    assert D(h1["avans_mahsubu_toplami"]) == D("290400") and D(h1["net_odenecek"]) == 0
    assert h1["durum"] == "OFFSET_ONLY"

    ekstre = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()
    assert D(ekstre["ozet"]["bakiye"]) == D("-209600") and ekstre["ozet"]["bakiye_durumu"] == "BORCLU"
    assert D(ekstre["ozet"]["acik_avans_bakiyesi"]) == D("209600")

    h2 = tartim(client, uretici["id"], brut=4600).json()["hakedis"]  # net 800 kg → 232.320 TL
    assert D(h2["avans_mahsubu_toplami"]) == D("209600") and D(h2["net_odenecek"]) == D("22720")

    # Tamamen mahsup edilmiş hakedişe ödeme → 400
    r = client.post(f"{API}/hakedisler/{h1['id']}/odeme-yap", json={})
    assert r.status_code == 400

    ekstre = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()
    assert D(ekstre["ozet"]["bakiye"]) == D("22720") and ekstre["acik_avanslar"] == []
    assert D(ekstre["hareketler"][-1]["bakiye"]) == D("22720")


def test_parcali_ve_tam_odeme(client, uretici):
    fiyat_gir(client, A=300)
    h = tartim(client, uretici["id"]).json()["hakedis"]  # net 290.400

    r = client.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={"tutar": 100000, "referans_no": "EFT-1"})
    assert r.status_code == 201, r.text
    s = r.json()
    assert s["hakedis"]["durum"] == "PARTIALLY_PAID" and D(s["hakedis"]["kalan"]) == D("190400")
    assert s["odeme"]["iban"] == IBAN

    r = client.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={"tutar": 999999})
    assert r.status_code == 400 and "aşamaz" in r.json()["hata"]

    r = client.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={})  # kalanın tamamı
    assert r.status_code == 201 and r.json()["hakedis"]["durum"] == "PAID"
    assert D(r.json()["uretici_guncel_bakiye"]) == 0
    assert client.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={}).status_code == 400
    assert client.post(f"{API}/hakedisler/999/odeme-yap", json={}).status_code == 404


def test_hakedisten_sonra_verilen_avans_odemede_mahsup_edilir(client, uretici):
    fiyat_gir(client, A=300)
    h = tartim(client, uretici["id"]).json()["hakedis"]  # net 290.400, ödenmedi
    client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 40000, "odeme_yontemi": "CASH"})

    r = client.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={})
    assert r.status_code == 201
    s = r.json()
    assert D(s["bu_islemde_mahsup_edilen_avans"]) == D("40000")
    assert D(s["odeme"]["tutar"]) == D("250400") and s["hakedis"]["durum"] == "PAID"
    assert D(s["uretici_guncel_bakiye"]) == 0

    # Büyük avans kalanın tamamını kapatırsa ödeme gerekmez → 200
    h2 = tartim(client, uretici["id"]).json()["hakedis"]
    client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 400000, "odeme_yontemi": "CASH"})
    r = client.post(f"{API}/hakedisler/{h2['id']}/odeme-yap", json={})
    assert r.status_code == 200 and r.json()["odeme"] is None
    assert r.json()["hakedis"]["durum"] == "OFFSET_ONLY"


def test_avans_bilinmeyen_uretici_404(client):
    r = client.post(f"{API}/avanslar", json={"uretici_id": 99, "tutar": 100})
    assert r.status_code == 404


# ─── Panel için yardımcı uç noktalar ───

def test_onizleme_kayit_yapmadan_hesaplar(client, uretici):
    govde = {"uretici_id": uretici["id"], "brut_kg": 4800, "dara_kg": 3800,
             "kalite": {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "nem_orani": 6}}
    r = client.post(f"{API}/tartimlar/onizleme", json=govde)
    assert r.status_code == 200 and r.json()["birim_fiyat"] is None and r.json()["uyarilar"]

    fiyat_gir(client, A=300)
    client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 100000, "odeme_yontemi": "CASH"})
    o = client.post(f"{API}/tartimlar/onizleme", json=govde).json()
    assert o["kalite_kodu"] == "A" and D(o["brut_tutar"]) == D("300000")
    assert D(o["avans_mahsubu"]) == D("100000") and D(o["tahmini_net_odenecek"]) == D("190400")
    assert client.get(f"{API}/tartimlar").json() == []  # hiçbir şey kaydedilmedi


def test_tartim_listesi_ve_uretici_bakiyeleri(client, uretici):
    fiyat_gir(client, A=300)
    tartim(client, uretici["id"])
    liste = client.get(f"{API}/tartimlar").json()
    assert len(liste) == 1 and liste[0]["uretici_adi"] == "Ahmet Yılmaz"
    b = client.get(f"{API}/raporlar/uretici-bakiyeleri").json()
    assert D(b[0]["bakiye"]) == D("290400") and D(b[0]["odenebilir"]) == D("290400")


def test_panel_sayfasi_ve_statik_dosyalar(client):
    r = client.get("/")
    assert r.status_code == 200 and "x-data=\"panel\"" in r.text and "/static/js/app.js" in r.text
    assert client.get("/static/js/app.js").status_code == 200
    assert client.get("/static/css/app.css").status_code == 200


def test_cevrimdisi_calisir_dis_kaynak_yok(client):
    """Panel ve doküman sayfaları yalnızca yerel /static dosyalarını kullanmalı (internetsiz sunum)."""
    import re
    for yol in ("/", "/docs", "/redoc"):
        html = client.get(yol).text
        dis = [u for u in re.findall(r'(?:src|href)=["\'](https?://[^"\']+)', html)
               if not u.startswith("http://testserver/")]
        assert dis == [], f"{yol} dış kaynak içeriyor: {dis}"
    for dosya in ("tailwindcss-3.4.5.js", "alpinejs-3.14.1.min.js", "swagger-ui-5.17.14/swagger-ui-bundle.js",
                  "redoc-2.1.5.standalone.js", "fonts/inter-latin-ext-wght-normal.woff2"):
        assert client.get(f"/static/vendor/{dosya}").status_code == 200, dosya


# ─── Depo ve rapor ───

def test_stok_durumu_ve_gunluk_ozet(client, uretici):
    fiyat_gir(client, A=300, B=260)
    tartim(client, uretici["id"])                                     # A 1000 kg, randıman 58
    tartim(client, uretici["id"], catlak_orani=75, randiman=50)       # B 1000 kg, randıman 50
    h = tartim(client, uretici["id"], brut=4300).json()["hakedis"]    # A 500 kg
    client.post(f"{API}/hakedisler/{h['id']}/odeme-yap", json={"tutar": 50000})

    silo = client.get(f"{API}/depo/stok-durumu").json()
    assert D(silo["toplam_stok_kg"]) == 2500
    s_a1 = next(s for s in silo["silolar"] if s["silo_kodu"] == "S-A1")
    assert D(s_a1["stok_kg"]) == 1500 and D(s_a1["doluluk_orani"]) == D("12.50")  # 1500 / 12000

    kalite = client.get(f"{API}/depo/stok-durumu", params={"gruplama": "kalite"}).json()
    assert {k["kalite_kodu"]: D(k["stok_kg"]) for k in kalite["kaliteler"]} == {"A": 1500, "B": 1000}

    ozet = client.get(f"{API}/raporlar/gunluk-ozet").json()
    assert ozet["teslimat_sayisi"] == 3 and D(ozet["toplam_net_kg"]) == 2500
    assert D(ozet["ortalama_randiman"]) == D("54.80")  # (58×1500 + 50×1000) / 2500
    assert D(ozet["odenen_toplam"]) == D("50000") and ozet["odeme_sayisi"] == 1

    assert client.get(f"{API}/raporlar/gunluk-ozet", params={"tarih": "2020-01-01"}).json()["teslimat_sayisi"] == 0
    assert client.get(f"{API}/raporlar/gunluk-ozet", params={"tarih": "dun"}).status_code == 422
    assert client.get(f"{API}/depo/stok-durumu", params={"gruplama": "x"}).status_code == 422
