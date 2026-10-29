"""Kabul kriterleri ve kalite senaryoları (docs/03_KABUL_KRITERLERI_VE_KALITE.md) — yalnızca Java sürümü.

Her test, belgedeki bir Given-When-Then senaryosunun ya da kalite senaryosunun birebir karşılığıdır (KK-1…KK-4, KS-1, KS-2).
Çalıştırma:  .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_kabul.py
"""
import statistics
import threading
import time
from decimal import Decimal as D

import httpx
import pytest

from tests.conftest import fiyat_gir, tartim

API = "/api/v1"
KALITE = {"catlak_orani": 90, "randiman": 58, "bos_orani": 2, "curuk_orani": 0, "nem_orani": 6}


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Kabul senaryoları Java sürümünün kurallarını dener (--java ile çalıştırın)")


def onizle(client, **kalite):
    r = client.post(f"{API}/tartimlar/onizleme", json={"brut_kg": 1000, "dara_kg": 0, "kalite": KALITE | kalite})
    assert r.status_code == 200, r.text
    return r.json()


def stok(client, kod):
    return next(D(s["stok_kg"]) for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == kod)


def silo_id(client, kod):
    return next(s["silo_id"] for s in client.get(f"{API}/depo/stok-durumu").json()["silolar"] if s["silo_kodu"] == kod)


def ayni_anda(client, istekler):
    """İstekleri ayrı bağlantılardan aynı anda gönderir (yarış durumu); durum kodlarını döndürür."""
    sonuc, engel = [None] * len(istekler), threading.Barrier(len(istekler))

    def calis(i, yol, govde):
        with httpx.Client(base_url=client.base_url, headers=dict(client.headers), timeout=60) as c:
            engel.wait()
            sonuc[i] = c.post(yol, json=govde)

    is_parcaciklari = [threading.Thread(target=calis, args=(i, *x)) for i, x in enumerate(istekler)]
    for t in is_parcaciklari:
        t.start()
    for t in is_parcaciklari:
        t.join()
    return sonuc


# ─── KK-1: Fiyat kilidi ───

def test_kk1_fiyat_degisince_eski_fisin_hakedisi_degismez(client, uretici):
    fiyat_gir(client, A=300)
    sabah = tartim(client, uretici["id"]).json()                              # 1.000 kg, A
    assert D(sabah["fiyat"]["birim_fiyat"]) == 300 and D(sabah["hakedis"]["brut_tutar"]) == 300000
    fiyat_gir(client, A=320)                                                   # gün içinde fiyat değişir
    ogle = tartim(client, uretici["id"]).json()
    assert D(ogle["fiyat"]["birim_fiyat"]) == 320 and ogle["fis_no"] != sabah["fis_no"]
    tekrar = client.get(f"{API}/tartimlar/{sabah['id']}").json()
    assert D(tekrar["fiyat"]["birim_fiyat"]) == 300 and D(tekrar["hakedis"]["brut_tutar"]) == 300000


# ─── KK-2: Eksi bakiye ve avans mahsubu ───

def test_kk2_avans_hakedisten_buyukse_net_odeme_sifir_bakiye_eksi(client, uretici):
    fiyat_gir(client, A=300)
    a = client.post(f"{API}/avanslar", json={"uretici_id": uretici["id"], "tutar": 500000}).json()
    assert D(a["uretici_guncel_bakiye"]) == -500000                           # teslimat yokken avans: bakiye eksi
    h = tartim(client, uretici["id"]).json()["hakedis"]                      # brüt 300.000, kesinti 9.600
    assert D(h["avans_mahsubu_toplami"]) == 290400 and D(h["net_odenecek"]) == 0 and h["durum"] == "OFFSET_ONLY"
    o = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()["ozet"]
    assert D(o["bakiye"]) == -209600 and D(o["acik_avans_bakiyesi"]) == 209600 and o["bakiye_durumu"] == "BORCLU"


# ─── KK-3: Çatlak primi ve boş tane iskontosu (sınır değerleri) ───

@pytest.mark.parametrize("catlak, bos, prim, iskonto, birim", [
    (85, 2, 0, 0, 300),        # baz değer: prim yok
    (85.5, 2, 1, 0, 301),      # baz +0,5 puan → +1 ₺
    (95, 2, 20, 0, 320),       # tam azami sınır: +20
    (99, 2, 20, 0, 320),       # sınır üstü: +20'de kalır
    (90, 3, 10, 0, 310),       # boş eşiğe eşit: iskonto yok
    (90, 3.2, 10, 1, 309),     # eşik +0,2 puan → −1 ₺
    (90, 5, 10, 10, 300),      # A sınıfının izin verdiği en yüksek boş oranı
])
def test_kk3_catlak_primi_ve_bos_iskontosu_sinirlari(client, catlak, bos, prim, iskonto, birim):
    fiyat_gir(client, A=300)
    r = client.put(f"{API}/fiyatlar/catlak-primi/DRY", json={
        "baz_catlak_orani": 85, "puan_basi_tl": 2, "azami_fark_tl": 20, "aktif": True,
        "bos_esik_orani": 3, "bos_puan_basi_tl": 5, "bos_aktif": True})
    assert r.status_code == 200, r.text
    o = onizle(client, catlak_orani=catlak, bos_orani=bos)
    assert o["kalite_kodu"] == "A"
    assert (D(o["catlak_primi"]), D(o["bos_iskontosu"]), D(o["birim_fiyat"])) == (prim, iskonto, birim)


def test_kk3_baz_altinda_iskonto_ve_sinif_degisir(client):
    fiyat_gir(client, A=300, B=260)
    client.put(f"{API}/fiyatlar/catlak-primi/DRY", json={"baz_catlak_orani": 85, "puan_basi_tl": 2, "azami_fark_tl": 20, "aktif": True})
    alt = onizle(client, catlak_orani=84.9)                                    # A eşiğinin hemen altı
    assert alt["kalite_kodu"] == "B" and D(alt["catlak_primi"]) == D("-0.20") and D(alt["birim_fiyat"]) == D("259.80")


# ─── KK-4: Kısmi emanet bozdurma ───

def test_kk4_1200_kg_emanetin_400_kgi_bozulunca_800_kalir(client, uretici):
    fiyat_gir(client, A=300)
    e = client.post(f"{API}/tartimlar", json={"uretici_id": uretici["id"], "plaka": "56 ABC 123", "brut_kg": 4400,
                                               "dara_kg": 3200, "kalite": KALITE, "emanet": True}).json()
    assert D(e["emanet_kalan_kg"]) == 1200 and e["hakedis"] is None
    c = client.post(f"{API}/tartimlar/{e['id']}/satisa-cevir", json={"miktar_kg": 400}).json()
    assert D(c["net_kg"]) == 400 and D(c["emanet_kalan_kg"]) == 800 and D(c["hakedis"]["brut_tutar"]) == 120000
    o = client.get(f"{API}/ureticiler/{uretici['id']}/bakiye-ekstre").json()["ozet"]
    assert D(o["emanet_stok_kg"]) == 800
    assert stok(client, "S-A1") == 1200                                       # fiziksel stok değişmez, sahibi değişir


# ─── KS-2: Silo stoğunda yarış durumu ───

def test_ks2_ayni_stoga_esanli_iki_sevkiyattan_yalnizca_biri_gecer(client, uretici):
    fiyat_gir(client, A=300)
    tartim(client, uretici["id"])                                             # S-A1: 1.000 kg
    silo = silo_id(client, "S-A1")
    govde = {"musteri_adi": "Antep Fıstıkçılık A.Ş.", "plaka": "27 AF 270", "silo_id": silo,
             "brut_kg": 8900, "dara_kg": 8200, "birim_fiyat": 420, "tahsilat": "BANK_TRANSFER"}   # her biri 700 kg
    kodlar = sorted(r.status_code for r in ayni_anda(client, [(f"{API}/sevkiyatlar", govde)] * 2))
    assert kodlar == [201, 400], kodlar
    assert stok(client, "S-A1") == 300                                        # asla eksiye düşmez


def test_ks2_ayni_siloya_esanli_iki_tartim_kapasiteyi_asamaz(client, uretici):
    fiyat_gir(client, A=300)
    silo = silo_id(client, "S-A1")                                           # kapasite 12.000 kg
    ilk = client.post(f"{API}/tartimlar", json={"uretici_id": uretici["id"], "plaka": "56 ABC 123", "brut_kg": 14300,
                                                 "dara_kg": 3800, "kalite": KALITE, "silo_id": silo})
    assert ilk.status_code == 201, ilk.text                                   # 10.500 kg
    # Farklı iki üretici: aynı üretici olsa üretici satırının kilidi iki tartımı zaten sıraya sokardı
    ikinci = client.post(f"{API}/ureticiler", json={"tc_kimlik_no": "10000000078", "ad_soyad": "Fatma Demir",
                                                     "telefon": "0533 444 55 66", "ilce": "Merkez"}).json()
    govde = lambda u: {"uretici_id": u, "plaka": "56 ABC 123", "brut_kg": 4800, "dara_kg": 3800,
                       "kalite": KALITE, "silo_id": silo}                     # her biri 1.000 kg; ikisi birden sığmaz
    kodlar = sorted(r.status_code for r in ayni_anda(client, [(f"{API}/tartimlar", govde(uretici["id"])),
                                                               (f"{API}/tartimlar", govde(ikinci["id"]))]))
    assert kodlar == [201, 400], kodlar
    assert stok(client, "S-A1") == 11500


# ─── KS-1: Kantar tartımı tepki süresi ───

def test_ks1_tartim_onizleme_ve_kayit_tepki_suresi(client, uretici, request):
    """Ölçüm: 20 önizleme + 5 kayıt. Eşik yalnızca sunucu veritabanıyla aynı bölgedeyken anlamlıdır;
    uzak veritabanıyla çalışırken (yerel geliştirme) süreler raporlanır ama eşik uygulanmaz."""
    fiyat_gir(client, A=300)
    onizlemeler, kayitlar = [], []
    for _ in range(20):
        t = time.perf_counter()
        onizle(client)
        onizlemeler.append((time.perf_counter() - t) * 1000)
    for _ in range(5):
        t = time.perf_counter()
        assert tartim(client, uretici["id"]).status_code == 201
        kayitlar.append((time.perf_counter() - t) * 1000)
    p95 = statistics.quantiles(onizlemeler, n=20)[-1]
    print(f"\nKS-1 önizleme medyan {statistics.median(onizlemeler):.0f} ms, p95 {p95:.0f} ms; "
          f"kayıt medyan {statistics.median(kayitlar):.0f} ms, en kötü {max(kayitlar):.0f} ms")
    esik = request.config.getoption("--sure-esigi", default=None)
    if esik:
        assert p95 < float(esik) and max(kayitlar) < float(esik)
