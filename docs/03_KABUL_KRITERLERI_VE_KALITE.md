# 03 · Kabul Kriterleri ve Kalite Senaryoları

> BMH-405 Yazılım Mühendisliği · Hafta 5 — Davranış odaklı kabul kriterleri (BDD) ve ölçülebilir kalite senaryoları
> İlgili belgeler: [01 Kapsam ve aktörler](01_KAPSAM_VE_AKTORLER.md) · [02 Belirsizlik kaydı](02_BELIRSIZLIK_KAYDI.md) · [Gereksinimler (SRS)](GEREKSINIMLER.md)

Bu belgedeki her senaryo **çalıştırılabilir**: `tests/test_kabul.py` dosyasında aynı adla (KK-1…KK-4, KS-1, KS-2) bir otomatik testi vardır ve test, senaryodaki sayıların aynısını kullanır. Senaryo ile kod arasındaki bu bire bir bağ, kabul kriterinin yalnızca bir niyet olarak kalmamasını sağlar.

```bash
.\.venv\Scripts\python.exe -m pytest -q --java http://127.0.0.1:8020 tests/test_kabul.py
```

**Ortak ön koşullar (Background):** Kuru-kavlak A sınıfı taban fiyatı **300 ₺/kg**. Resmî kesintiler açık: gelir vergisi stopajı %2, borsa tescil %0,2, komisyon %1 (toplam **%3,2**). Bir tartım: brüt 4.800 kg, dara 3.800 kg → **net 1.000 kg**; numune çatlak %90, randıman %58, boş %2 (A sınıfı).

---

## 1. Kabul kriterleri (Given-When-Then)

### KK-1 · Fiyat kilidi ve değişkenlik koruması

```gherkin
# language: tr
Özellik: Fiyat tartım anında kilitlenir
  Bir teslimatın fiyatı, sonradan yapılan fiyat değişikliklerinden etkilenmemelidir.

  Senaryo: Gün içinde fiyat değişince sabahki fişin hakedişi değişmez
    Diyelim ki A sınıfı taban fiyatı 300 ₺/kg
    Ve üretici sabah net 1.000 kg A sınıfı fıstık teslim etmiş
    Ve sabahki fişin birim fiyatı 300 ₺ ve brüt hakedişi 300.000 ₺
    Eğer ki fiyat yöneticisi öğlen A sınıfı fiyatını 320 ₺/kg yaparsa
    Ve aynı üretici öğlen aynı miktarı yeniden teslim ederse
    O zaman öğlen fişi farklı bir fiş numarası almalı ve birim fiyatı 320 ₺ olmalı
    Ve sabahki fiş yeniden okunduğunda birim fiyatı hâlâ 300 ₺ olmalı
    Ve sabahki fişin brüt hakedişi hâlâ 300.000 ₺ olmalı
```

| Sınır / gerekçe | Doğrulayan |
|---|---|
| Değişiklik anı ile tartım anı arasında tek bir an bile çakışma olmamalı: fiyat `[başlangıç, bitiş)` aralığıyla tutulur, aynı sınıf için onaylı aralıklar çakışamaz (PostgreSQL `EXCLUDE` kısıtı). | `test_kabul.py::test_kk1_fiyat_degisince_eski_fisin_hakedisi_degismez`, `test_postgres.py::test_ayni_sinif_icin_cakisan_onayli_fiyat_reddedilir` |
| Uygulama atlanıp SQL ile fişin fiyatı güncellenmeye çalışılırsa veritabanı reddeder. | `test_postgres.py::test_teslimatin_kilitli_fiyati_ve_tartimi_degistirilemez` |

### KK-2 · Eksi bakiye ve avans mahsubu

```gherkin
# language: tr
Özellik: Avans hakedişten mahsup edilir, fazlası borç olarak kalır

  Senaryo: Avans hakedişten büyükse net ödeme sıfır kalır, bakiye eksi olur
    Diyelim ki üreticinin hiç teslimatı yok
    Eğer ki muhasebe üreticiye 500.000 ₺ avans verirse
    O zaman üreticinin cari bakiyesi -500.000 ₺ olmalı
    Eğer ki üretici net 1.000 kg A sınıfı fıstık teslim ederse
    O zaman brüt hakediş 300.000 ₺, kesintiler 9.600 ₺ olmalı
    Ve kesinti sonrası kalan 290.400 ₺'nin tamamı avanstan mahsup edilmeli
    Ve net ödenecek tutar 0 ₺ olmalı ve hakedişin durumu "yalnızca mahsup" olmalı
    Ve üreticinin bakiyesi -209.600 ₺ olmalı, durumu "borçlu" görünmeli
    Ve açık avans bakiyesi 209.600 ₺ olmalı
```

| Sınır / gerekçe | Doğrulayan |
|---|---|
| Mahsup, **kesinti sonrası** tutarla sınırlıdır: 290.400 ₺ mahsup edilir, 300.000 ₺ değil. Net ödeme hiçbir zaman eksi olmaz; tam 0 ₺'de durur. | `test_kabul.py::test_kk2_avans_hakedisten_buyukse_net_odeme_sifir_bakiye_eksi` |
| Sonraki teslimatta (net 800 kg → kesinti sonrası 232.320 ₺) kalan 209.600 ₺ kapanır, net ödeme 22.720 ₺ olur; tamamen mahsup edilmiş hakedişe ödeme yapılamaz (HTTP 400). | `test_api.py::test_avans_teslimattan_buyukse_eksi_bakiye_ve_mahsup` |

### KK-3 · Çatlak primi ve boş tane iskontosu (sınır değerleri)

Kural (fiyat yöneticisi tanımlar): **çatlak primi** baz %85, puan başı 2 ₺, en fazla ±20 ₺; **boş tane iskontosu** eşik %3, eşik üstü puan başı 5 ₺.
Birim fiyat = taban fiyat + çatlak primi − boş tane iskontosu.

```gherkin
# language: tr
Özellik: Çatlak primi ve boş tane iskontosu birim fiyata yansır

  Senaryo taslağı: Sınır değerlerinde birim fiyat
    Diyelim ki A sınıfı taban fiyatı 300 ₺/kg
    Ve çatlak primi kuralı baz %85, puan başı 2 ₺, azami ±20 ₺ ile açık
    Ve boş tane iskontosu eşik %3, puan başı 5 ₺ ile açık
    Eğer ki numunede çatlak oranı <çatlak> ve boş oranı <boş> ölçülürse
    O zaman kalite sınıfı A olmalı
    Ve çatlak primi <prim> ₺, boş tane iskontosu <iskonto> ₺ olmalı
    Ve birim fiyat <birim> ₺/kg olmalı

    Örnekler:
      | çatlak | boş  | prim | iskonto | birim | açıklama                              |
      | %85    | %2   | 0    | 0       | 300   | tam baz değer: prim yok               |
      | %85,5  | %2   | 1    | 0       | 301   | baz +0,5 puan                          |
      | %95    | %2   | 20   | 0       | 320   | tam azami sınır                        |
      | %99    | %2   | 20   | 0       | 320   | sınır üstü: +20'de kalır               |
      | %90    | %3   | 10   | 0       | 310   | boş eşiğe eşit: iskonto yok            |
      | %90    | %3,2 | 10   | 1       | 309   | eşik +0,2 puan                         |
      | %90    | %5   | 10   | 10      | 300   | A sınıfının izin verdiği en yüksek boş |

  Senaryo: Baz değerin hemen altında iskonto uygulanır ve sınıf düşer
    Diyelim ki A 300 ₺, B 260 ₺ ve çatlak primi kuralı açık
    Eğer ki çatlak oranı %84,9 ölçülürse
    O zaman fıstık B sınıfına düşmeli (A için çatlak ≥ %85 gerekir)
    Ve çatlak iskontosu -0,20 ₺, birim fiyat 259,80 ₺/kg olmalı
```

| Sınır / gerekçe | Doğrulayan |
|---|---|
| Eşitlik durumları (%85 → 0, %3 → 0, %95 → tam +20) ayrı ayrı denenir; "büyüktür" ile "büyük eşittir" karışıklığı burada yakalanır. | `test_kabul.py::test_kk3_catlak_primi_ve_bos_iskontosu_sinirlari` (7 örnek) |
| %84,9 hem fiyat hem sınıf sınırıdır: iskonto ve sınıf değişikliği aynı anda doğrulanır. | `test_kabul.py::test_kk3_baz_altinda_iskonto_ve_sinif_degisir` |

### KK-4 · Kısmi emanet bozdurma

```gherkin
# language: tr
Özellik: Emanet parça parça satışa çevrilebilir

  Senaryo: 1.200 kg emanetin 400 kg'ı bozulunca emanet stoğu 800 kg'a iner
    Diyelim ki A sınıfı taban fiyatı 300 ₺/kg
    Ve üretici net 1.200 kg fıstığı emanete bırakmış (brüt 4.400, dara 3.200)
    Ve emanet fişinde hakediş yok, kalan emanet 1.200 kg
    Eğer ki üretici emanetinden 400 kg'ı satışa çevirirse
    O zaman ana fişe bağlı yeni bir bozdurma fişi oluşmalı, net 400 kg
    Ve bozdurma fişinin brüt hakedişi 120.000 ₺ olmalı (bugünkü 300 ₺ fiyatla)
    Ve kalan emanet 800 kg olmalı
    Ve üreticinin cari özetinde emanet stoğu 800 kg görünmeli
    Ve S-A1 silosundaki fiziksel stok 1.200 kg kalmalı (yalnızca 400 kg'ın sahibi değişti)
```

| Sınır / gerekçe | Doğrulayan |
|---|---|
| Kalandan fazlası (900 kg) satılamaz, hata mesajı kalan miktarı söyler; kalan 800 kg ertesi gün **o günün** fiyatıyla (ör. 320 ₺) satılabilir. | `test_kabul.py::test_kk4_1200_kg_emanetin_400_kgi_bozulunca_800_kalir`, `test_emanet.py::test_parcali_satis_kalan_emanette_kalir_ve_lot_bolunur` |

---

## 2. Ölçülebilir kalite senaryoları

Bass, Clements & Kazman'ın kalite senaryosu modeli dört soruya indirgenmiş biçimde kullanıldı: **Ne zaman?** (ortam) · **Ne olunca?** (uyarıcı ve kaynağı) · **Sistem ne yapmalı?** (yanıt) · **Ne kadar iyi?** (yanıt ölçüsü).

### KS-1 · Performans: kantar tartımı tepki süresi

| Soru | Cevap |
|---|---|
| **Ne zaman?** | Hasat döneminde, normal çalışma koşullarında; uygulama sunucusu ile veritabanı aynı bölgede (canlı ortam: Render ve Neon, ABD Ohio). |
| **Ne olunca?** | Kantar memuru tartım formunda bir değeri değiştirir (önizleme isteği) ya da **F9** ile tartımı kaydeder. |
| **Sistem ne yapmalı?** | Önizlemede sınıfı, birim fiyatı ve tahmini hakedişi hesaplayıp göstermeli; kayıtta fiş, fiyat kilidi, hakediş, cari hareket ve silo yerleşimini **tek işlemde** tamamlayıp fişi döndürmeli. |
| **Ne kadar iyi?** | Sunucu tarafı işlem süresi **< 500 ms** (önizlemede p95, kayıtta en kötü durum). Ağ gecikmesi bu ölçüye dahil değildir: kantar bilgisayarı ile sunucu arasındaki mesafe ayrı bir dağıtım kararıdır. |

**Ölçüm** (`test_kabul.py::test_ks1_tartim_onizleme_ve_kayit_tepki_suresi`: 20 önizleme + 5 kayıt; eşik `--sure-esigi 500` ile verilince doğrulanır):

| Ortam | Önizleme | Kayıt | Yorum |
|---|---|---|---|
| Yerel sunucu, uzak test veritabanı (Neon) | medyan 61 ms, p95 144 ms | medyan 354 ms, en kötü 405 ms | Veritabanı yurt dışında olmasına rağmen hedefin altında. |
| Canlı sunucu (Render), Türkiye'den istemci | medyan 423 ms, p95 537 ms | — (canlı demoya kayıt yazılmadı) | Aynı ortamda yalnızca ağ gidiş-dönüşü (`/saglik`) medyan **308 ms**; önizlemenin sunucu payı ≈ **115 ms**. Uçtan uca süreyi Türkiye'den kısaltmanın yolu sunucuyu yakın bir bölgeye taşımaktır (dağıtım kararı). |

*Ölçüm tarihi: 29.10.2026. Sayılar ortamın o anki yüküne göre değişir; test her çalıştırmada yeniden ölçer ve yazdırır.*

### KS-2 · Tutarlılık: silo stoğunda yarış durumu (race condition)

| Soru | Cevap |
|---|---|
| **Ne zaman?** | Yoğun saatte, iki kullanıcı aynı anda işlem yaparken (iki kantar bilgisayarı, ya da depo sorumlusu ile kantar memuru). |
| **Ne olunca?** | (a) Aynı silodan, toplamı stoğu aşan **iki sevkiyat aynı anda** kaydedilir. (b) Aynı siloya, ikisi birden kapasiteyi aşacak **iki tartım aynı anda** kaydedilir. |
| **Sistem ne yapmalı?** | İşlemlerden birini tamamlamalı, diğerini güncel stokla yeniden kontrol edip açık bir hata mesajıyla reddetmeli. Stok hiçbir an eksiye düşmemeli, kapasite hiçbir an aşılmamalı. |
| **Ne kadar iyi?** | **%100:** her denemede tam olarak bir istek başarılı (HTTP 201), diğeri reddedilir (HTTP 400); sonrasında stok = başlangıç − kabul edilen miktar. Kaybolan veya çift düşülen kg yok. |

**Mekanizma (savunma derinliği):**

1. **Satır kilidi (`PESSIMISTIC_WRITE`).** Sevkiyatta silo satırı stok kontrolünden önce kilitlenir (`SevkiyatServisi.kaydet`); tartımda lot siloya yerleştirilirken kapasite kontrolü de silo satırı kilitlenerek yapılır (`Kurallar.siloKabulEderMi`). PostgreSQL `SELECT … FOR UPDATE` ile ikinci işlem birincinin bitmesini bekler ve güncel stoğu görür.
2. **Veritabanı tetikleyicisi.** Uygulama katmanı atlansa bile `trg_stock_non_negative` bir lotun herhangi bir silodaki stoğunu eksiye düşürecek hareketi reddeder.
3. **Belge sayacı kilidi.** Her tartım fiş numarası için yıl bazlı sayaç satırını kilitler; bu, tartımları zaten sıraya sokar. Silo kilidi bu dolaylı korumaya bağımlı kalmamak için ayrıca vardır.

**Doğrulama:**

| Test | Ne yapar | Sonuç |
|---|---|---|
| `test_kabul.py::test_ks2_ayni_stoga_esanli_iki_sevkiyattan_yalnizca_biri_gecer` | 1.000 kg stoklu siloya, iki ayrı bağlantıdan aynı anda (`threading.Barrier`) 700'er kg sevkiyat gönderir. | Biri 201, biri 400; kalan stok 300 kg. |
| `test_kabul.py::test_ks2_ayni_siloya_esanli_iki_tartim_kapasiteyi_asamaz` | 12.000 kg kapasiteli silo 10.500 kg doluyken, iki farklı üreticinin 1.000'er kg'lık tartımını aynı anda gönderir. | Biri 201, biri 400; silo 11.500 kg. |
| `test_sevkiyat.py::test_veritabani_eksi_stoga_izin_vermez` | Uygulamayı atlayıp SQL ile stoğu eksiye düşürecek hareket ekler. | Veritabanı reddeder ("Stok eksiye düşemez"). |

> **Not (dürüst sınır):** Tartım yarış testi, silo kilidi kaldırılarak da çalıştırıldı ve yine geçti; çünkü tartımlar 3. maddedeki belge sayacı kilidiyle zaten sıraya giriyor. Bu test bugün bir hatayı yakalamaktan çok bir **değişmezi korur**: ileride numaralandırma değişip sayaç kilidi kalkarsa, kapasite kuralı silo kilidiyle korunmaya devam eder ve test bunu doğrular.
