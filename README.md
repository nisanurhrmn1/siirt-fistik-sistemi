# Siirt Fıstığı Alım ve Üretici Ödeme Sistemi

> Yazılım Mühendisliği Dönem Projesi · Siirt Üniversitesi
> **Hazırlayanlar:** Nisanur HARMAN (230304014), Vesile KARADEMİR (230304034) · **Danışman:** Prof. Dr. Musa ATAŞ · **Dönem:** 2026–2027 Güz

Siirt fıstığı alım merkezlerinde yapılan işlerin tamamını tek sistemde toplayan bir web uygulaması:
kantar tartımı, kalite analizi, günlük fiyat, üretici hakedişi, avans, ödeme ve depo stoğu.
Sistem bir **REST API** (Java 21 · Spring Boot 3 · PostgreSQL) ve bu API'yi kullanan bir **web yönetim paneli**nden oluşur.
Panel dahil her şey **internetsiz** çalışır.

### 🌐 Canlı demo: **https://siirt-fistik.onrender.com**
Giriş ekranındaki düğmelerle her rolü (Kantar memuru, Muhasebe, Fiyat yöneticisi…) tek tıkla deneyebilirsiniz. Demo verisi her gece 03:00'te, sunucu uykudaysa da uyandığında (o gün henüz tartım yoksa) sıfırlanır; ekranlar ve gün sonu raporu her zaman güncel tarihli veriyle açılır. Sunucu ücretsiz planda çalıştığı için 15 dakika kullanılmazsa uyur; ilk açılış 30–60 saniye sürebilir.

![Günlük özet kartları](docs/ekran-goruntuleri/01-ozet-paneli.png)

---

## Proje dokümanları

| Doküman | İçerik |
|---|---|
| 📋 [Gereksinimler (SRS)](docs/GEREKSINIMLER.md) | Fonksiyonel ve fonksiyonel olmayan gereksinimler, iş kuralları, kullanım durumları, izlenebilirlik matrisi |
| 📐 [UML diyagramları](docs/UML.md) | Kullanım durumu, sınıf, sıralama, etkinlik, durum ve bileşen/dağıtım diyagramları |
| 🗄️ [Veritabanı (ER)](docs/ER.md) | ER diyagramı, tablolar ve iş kuralı → şema eşlemesi |
| 🌐 [İnternette yayınlama](docs/YAYINLAMA.md) | Neon (PostgreSQL) + Render (Docker) ile ücretsiz yayın, adım adım |

## İçindekiler
1. [Projenin amacı](#1-projenin-amacı)
2. [Çözülen belirsizlikler (edge case'ler)](#2-çözülen-belirsizlikler-edge-caseler)
3. [Sistem mimarisi](#3-sistem-mimarisi)
4. [Kullanılan teknolojiler](#4-kullanılan-teknolojiler)
5. [Kurulum ve çalıştırma](#5-kurulum-ve-çalıştırma)
6. [API uç noktaları](#6-api-uç-noktaları)
7. [Ekran görüntüleri](#7-ekran-görüntüleri)
8. [Testler](#8-testler)
9. [Proje yapısı](#9-proje-yapısı)
10. [Bilinen kısıtlar ve geliştirme önerileri](#10-bilinen-kısıtlar-ve-geliştirme-önerileri)

---

## 1. Projenin amacı

Siirt'te fıstık alımı çoğu yerde hâlâ kâğıt fiş ve el defteriyle yürütülüyor. Bunun yol açtığı sorunlar:

- Aynı gün birden çok kez gelen üreticinin teslimatları birbirine karışıyor.
- Gün içinde fiyat değiştiğinde hangi teslimatın hangi fiyattan hesaplanacağı tartışma konusu oluyor.
- Sezon öncesi verilen avansların hangi teslimattan düşüldüğü izlenemiyor.
- Hangi siloda hangi kalitede ne kadar fıstık olduğu bilinmiyor.

Bu proje alım sürecinin tamamını kayıt altına alır ve para hesaplarını **kuralla** yapar:

| Modül | Kapsam |
|---|---|
| Üretici kaydı | TC/VKN (kontrol basamağı doğrulamalı), iletişim, IBAN (mod-97 doğrulamalı) |
| Kantar | Brüt / dara / net kg, araç plakası, benzersiz fiş ve lot numarası |
| Kalite | Çatlak, randıman, boş, çürük ve nem oranından otomatik sınıf; kuru-kavlak (A / B / Iskarta) ve yaş fıstık (YA / YB / YISK) ayrı |
| Fiyat | Kalite sınıfı bazında, zaman aralığıyla geçerli, onaylı taban fiyat |
| Avans ve kesinti | Stopaj, borsa tescil ve komisyon kesintileri; avansın sonraki teslimatlardan mahsubu |
| Ödeme | Net hakediş, parçalı veya tam ödeme, cari hesap ekstresi |
| Depo | Silo, lot ve kaliteye göre stok; kalite ayrıştırması ve kapasite kontrolü |
| Raporlama | Günlük alım, ortalama randıman, ödenen tutar, üretici bakiyeleri |

---

## 2. Çözülen belirsizlikler (edge case'ler)

### 2.1 Aynı gün birden fazla teslimat
**Sorun:** Bir üretici aynı gün birkaç kez gelebilir. Teslimatlar "üretici + tarih" ile tanımlanırsa birbirinin üzerine yazılır.

**Çözüm:**
- Her tartım, üretici ve tarihten bağımsız ayrı bir kayıttır.
- Her tartım iki benzersiz numara alır: fiş (`KF-2026-000001`) ve lot (`LOT-2026-000001`).
- Numaralar yıl bazlı bir sayaç tablosundan (`document_sequences`) satır kilidiyle (`SELECT … FOR UPDATE`) üretilir. Aynı anda iki kantar fişi kesilse bile numara çakışmaz veya atlanmaz.
- Veritabanında `ticket_no` ve `lot_no` için `UNIQUE` kısıtı vardır; (üretici, tarih) üzerinde bilerek kısıt **yoktur**.

### 2.2 Fiyat kilitleme (snapshot)
**Sorun:** Fiyat gün içinde değişebilir. Kalite analizi ve ödeme tartımdan saatler sonra yapılabilir. Hangi fiyat geçerli olmalı?

**Çözüm:** Geçerli olan, **tartım anında onaylı** fiyattır. Bu fiyat teslimata kopyalanır ve bir daha değişmez.

```
09:00  A sınıfı fiyat 300 ₺ onaylandı ──┐
11:30  Ahmet'in 2. teslimatı tartıldı ──┼─► fiyat 300 ₺ olarak KİLİTLENDİ
12:00  A sınıfı fiyat 320 ₺ oldu        │   (eski kayıt kapatıldı, yeni kayıt açıldı)
13:00  Kalite analizi yapıldı           │
16:00  Hakediş ödendi ──────────────────┘   hesap yine 300 ₺ ile yapılır
```

| Kural | Nasıl uygulanıyor |
|---|---|
| Fiyat tartım anına göre bulunur | `valid_from ≤ tartım < valid_to` **ve** `approved_at ≤ tartım` |
| Bulunan fiyat teslimata kopyalanır | `weigh_tickets.daily_price_id` ve `unit_price` |
| Kopyalanan fiyat değiştirilemez | ORM doğrulayıcısı ve PostgreSQL trigger'ı |
| Onaylı fiyat düzenlenemez | Fiyat değişince eski kayıt kapanır, yeni kayıt açılır (geçmiş korunur) |
| Geçmişe dönük fiyat girilemez | Başlangıcı geçmişte olan fiyat 400 hatası alır |
| Aynı sınıfın onaylı fiyatları çakışamaz | PostgreSQL `EXCLUDE USING gist` kısıtı |
| Sonradan iptal edilen fiyat geçmişi bozmaz | Fiyat aranırken `revoked_at > tartım` olan kayıtlar da sayılır |

### 2.3 Avans mahsubu ve eksi bakiye
**Sorun:** Üreticiye sezon başında avans verilir. Avans ilk teslimatın tutarından büyük olabilir.

**Çözüm:** Her üreticinin bir **cari hesabı** (`producer_ledger_entries`) vardır. Bu hesap yalnızca kayıt ekleyerek ilerler; bakiyenin tek doğruluk kaynağıdır.

1. Avans verildiği anda cari hesaba **borç** yazılır. Bakiye eksiye düşebilir.
2. Her hakedişte açık avanslar, **en eski avanstan başlayarak** (FIFO), kesinti sonrası tutar kadar mahsup edilir.
3. Kapanmayan avans açık kalır ve sonraki teslimatlardan otomatik düşülür.
4. Hakediş oluştuktan **sonra** verilen avans, ödeme yapılırken kalan tutardan mahsup edilir.

| Adım | Brüt − kesinti | Avans mahsubu | Net ödenecek | Açık avans | Cari bakiye |
|---|---:|---:|---:|---:|---:|
| 500.000 ₺ avans verildi | — | — | — | 500.000 | **−500.000** |
| 1. teslimat (1.000 kg × 300 ₺) | 290.400 | 290.400 | 0 *(avansa mahsup)* | 209.600 | **−209.600** |
| 2. teslimat (800 kg × 300 ₺) | 232.320 | 209.600 | 22.720 | 0 | **+22.720** |

```
Net hakediş = (Net kg × Kilitli fiyat) − Yasal kesintiler − Avans mahsubu        (her zaman ≥ 0)
```

Hakedişin net tutarı hiçbir zaman eksi olmaz. Eksi bakiye hakedişte değil, cari hesapta tutulur. Böylece "eksi tutarlı hakediş nasıl ödenir" sorunu ortadan kalkar ve hangi avansın hangi teslimattan düşüldüğü (`advance_offsets`) izlenebilir.

### 2.4 Diğer kurallar
- **Parçalı ödeme:** Bir hakediş birden çok ödemeyle kapatılabilir. Hakedişin durumu `Ödenmedi → Kısmi ödendi → Ödendi` şeklinde ilerler. Bir ödeme ne hakedişin kalan tutarını ne de üreticinin cari bakiyesini aşabilir.
- **Kesinti oranları:** Hakediş oluşurken kesinti oranları hakedişe kopyalanır. Mevzuattaki oran sonradan değişse de eski hakedişler etkilenmez.
- **Kalite ayrıştırması:** Bir siloya yalnızca kendi kalite sınıfı girebilir. Sınıfa ayrılmamış bir siloda da farklı kaliteler karışamaz. Siloların kapasitesi de kontrol edilir.
- **Denetlenebilirlik:** Cari hesap kayıtları silinemez ve güncellenemez (trigger ile korunur). Düzeltme ancak ters kayıtla (`REVERSAL`) yapılır.
- **Para hassasiyeti:** Hiçbir yerde `float` kullanılmaz. Veritabanında `NUMERIC`, Java'da `BigDecimal` ve `HALF_UP` yuvarlama kullanılır. API para değerlerini metin olarak döndürür (`"300000.00"`).

---

## 3. Sistem mimarisi

Sunucu **Java 21 + Spring Boot 3** ile yazılmıştır ve katmanlı bir mimari kullanır. Her katman yalnızca bir altındaki katmanı bilir.

```mermaid
flowchart TB
    subgraph İstemci
        P["Web Paneli<br/>HTML + Alpine.js + Tailwind"]
        S["Swagger UI / ReDoc"]
    end
    subgraph "Spring Boot Uygulaması (java-backend)"
        R["REST Kontrolcüleri<br/>api/Kontrolculer"]
        V["İstek kayıtları + Bean Validation<br/>api/Istekler, 422"]
        SV["Servisler (@Transactional)<br/>servis/*"]
        D["İş Kuralları<br/>kural/Kurallar"]
        M["JPA Varlıkları (Hibernate)<br/>alan/*"]
    end
    DB[("PostgreSQL<br/>Flyway göçleri, trigger'lar, view'lar")]

    P -- "fetch /api/v1 (JSON)" --> R
    S --> R
    R --> V --> SV --> D --> M --> DB
```

| Katman | Java paketi | Sorumluluk |
|---|---|---|
| **Sunum** | `resources/static` | Tek sayfalık panel. Veriyi yalnızca REST API'den alır; iş kuralı içermez. |
| **API** | `api` (`Kontrolculer`, `HataYoneticisi`) | HTTP yönlendirmesi, kullanıcı çözümleme, hata → HTTP kodu (400, 401, 404, 409, 422). |
| **İstek/yanıt** | `api` (`Istekler`, `Yanitlar`, `Gecerli`) | Java `record` sınıfları. TC, IBAN, plaka ve oran doğrulaması Bean Validation ile yapılır; hata alan bazlı Türkçe mesajla döner. |
| **Servisler** | `servis` | İş akışları. Örneğin tartım → kalite → fiyat kilidi → hakediş → stok adımları tek `@Transactional` işlemde çalışır. |
| **Alan / iş kuralları** | `kural` (`Kurallar`, `Dogrulama`) | Fiyat bulma, FIFO avans mahsubu, kesinti hesabı, ödeme dağıtımı, satır kilitleri. HTTP'den bağımsızdır. |
| **Veri** | `alan` + `resources/db/migration` | 19 JPA varlığı; şema Flyway ile kurulur. CHECK / UNIQUE / EXCLUDE kısıtları, trigger'lar ve rapor view'ları. |

Bir kuralın birden fazla katmanda tekrarlanması bilinçli bir tercihtir (derinlemesine savunma). Örneğin "net kg pozitif olmalı" kuralı istek doğrulamasında, iş kuralı katmanında ve veritabanındaki `CHECK` kısıtında ayrı ayrı denetlenir.

Veritabanı şemasının ayrıntıları ve ER diyagramı: **[docs/ER.md](docs/ER.md)** · Sınıf, sıralama ve durum diyagramları: **[docs/UML.md](docs/UML.md)**

### Python prototipinden Java'ya

Sistem önce Python (FastAPI) ile prototiplendi, ardından aynı mimariyle Java'ya taşındı. Python sürümü depoda `app/` klasöründe ve **`python-surum`** Git etiketinde durur. İki sürüm **aynı REST sözleşmesini**, aynı veritabanı şemasını ve aynı paneli kullanır; aynı 27 API testi ikisinde de geçer (bkz. [Testler](#8-testler)).

| Kavram | Python prototipi | Java sürümü |
|---|---|---|
| İş kuralları | `app/domain/rules.py` | `kural/Kurallar.java` |
| Varlıklar / ORM | SQLAlchemy `app/db/models.py` | JPA/Hibernate `alan/*.java` |
| İstek doğrulama | Pydantic `app/schemas` | Bean Validation `api/Istekler.java` |
| Servisler | `app/services` | `servis/*Servisi.java` |
| Uç noktalar | FastAPI router'ları | Spring `@RestController` |
| Şema kurulumu | `create_all` + SQL dosyası | Flyway `V1__sema.sql`, `V2__tetikleyiciler_ve_viewlar.sql` |

---

## 4. Kullanılan teknolojiler

| Alan | Teknoloji | Neden |
|---|---|---|
| Dil | **Java 21** (LTS) | Güçlü tip sistemi; `BigDecimal` ile kesin para hesabı; `record` ile kısa veri sınıfları |
| Çatı | **Spring Boot 3.3** (Web MVC) | Endüstri standardı; bağımlılık enjeksiyonu, deklaratif transaction |
| ORM | **Spring Data JPA / Hibernate 6** | Varlık sınıfları, JPQL sorguları, satır kilitleri (`PESSIMISTIC_WRITE`) |
| Doğrulama | **Jakarta Bean Validation** | Alan kısıtları ve özel `@Gecerli` (TC, IBAN, plaka, telefon) kısıtı |
| Veritabanı | **PostgreSQL** (18.6 ile test edildi) | `EXCLUDE` kısıtı, trigger'lar, rapor view'ları, `timestamptz` |
| Şema yönetimi | **Flyway** | Sürümlü SQL göçleri |
| API dokümanı | **springdoc-openapi** (Swagger UI) | Koddan otomatik OpenAPI 3; jar içinden çalışır |
| Arayüz | **Alpine.js** + **Tailwind CSS** | Node/React derleme adımı olmadan reaktif, modern arayüz |
| Derleme | **Maven** (`mvnw` sarmalayıcısı) | Maven kurmadan derleme |
| Test | **JUnit 5** + ortak sözleşme testleri (pytest) | Birim testleri ve uçtan uca API testleri |
| Güvenlik | **Spring Security** + **JWT** (HS256), BCrypt | Rol bazlı yetki (`@PreAuthorize`), durumsuz (stateless) oturum |
| Kod kısaltma | **Lombok** | Varlık sınıflarında getter/setter |

Arayüz kütüphaneleri (Tailwind, Alpine.js, Inter yazı tipi, ReDoc) `static/vendor/` klasöründedir; Swagger UI jar içinde gelir. Sistem **hiçbir dış adrese istek atmaz**.

---

## 5. Kurulum ve çalıştırma

### Gereksinimler
- Windows 10/11, macOS veya Linux
- **Java 21** (`java -version`) — örn. [Eclipse Temurin](https://adoptium.net)
- **PostgreSQL 15+** ([indir](https://www.postgresql.org/download/windows/); pgAdmin birlikte gelir)
- Python 3.11+ — yalnızca veritabanı kurulum betiği ve sözleşme testleri için
- İnternet yalnızca ilk derlemede, bağımlılıkları indirmek için gerekir. Sonrasında sistem tamamen çevrimdışı çalışır.

### 1) Veritabanını hazırlayın (bir kez)

```bash
git clone https://github.com/nisanurhrmn1/siirt-fistik-sistemi.git
cd siirt-fistik-sistemi
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m scripts.postgres_kurulum
```

Kurulum betiği `postgres` yönetici şifresini sorar ve şunları yapar:
- `fistik` adında ayrı bir uygulama kullanıcısı oluşturur; uygulama yönetici hesabıyla çalışmaz.
- `siirt_fistik` (asıl) ve `siirt_fistik_test` (testler) veritabanlarını oluşturur.
- Rastgele üretilen uygulama şifresini yalnızca `.env` dosyasına yazar (`.env` Git'e yüklenmez).

<details><summary>Python kullanmadan, elle kurulum</summary>

pgAdmin'deki Query Tool'da (postgres kullanıcısıyla):

```sql
CREATE ROLE fistik LOGIN PASSWORD 'kendi-sifreniz';
CREATE DATABASE siirt_fistik OWNER fistik ENCODING 'UTF8' TEMPLATE template0;
```

Ardından proje kökünde `.env` dosyası oluşturun:

```
DATABASE_URL=postgresql://fistik:kendi-sifreniz@localhost:5432/siirt_fistik
```
</details>

### 2) ⚡ Tek tıkla başlatma (önerilen)

**Windows:** proje klasöründeki **`baslat.bat`** dosyasına çift tıklayın. Betik ilk seferde Java projesini derler, sunucuyu başlatır ve paneli tarayıcıda açar. Örnek veriyle başlatmak için (üretici tablosu boşsa yükler):

```bash
baslat.bat --fistik.demo-verisi=true
```

İlk açılışta Flyway tabloları, trigger'ları ve view'ları kurar. Uygulama da yönetici kullanıcıyı, A/B/Iskarta kalite sınıflarını, örnek kesinti türlerini ve siloları otomatik oluşturur.

### Geliştirici komutları

```bash
cd java-backend
.\mvnw.cmd spring-boot:run          # geliştirme sunucusu (http://127.0.0.1:8000)
.\mvnw.cmd test                     # JUnit birim testleri
.\mvnw.cmd package                  # target/siirt-fistik.jar
java -jar target\siirt-fistik.jar --server.port=8080
```

| Adres | İçerik |
|---|---|
| http://127.0.0.1:8000/ | Web yönetim paneli |
| http://127.0.0.1:8000/docs | Swagger UI (API'yi tarayıcıdan deneme) |
| http://127.0.0.1:8000/redoc | ReDoc (okunabilir API dokümanı) |
| http://127.0.0.1:8000/openapi.json | OpenAPI 3 tanımı |

Tartım yapabilmek için önce **Fiyat & Depo** sekmesinden günlük fiyat girilmelidir. Veriler pgAdmin'de `siirt_fistik` veritabanı altında incelenebilir; view'lar (`v_producer_balance`, `v_daily_purchases`, `v_silo_stock` …) doğrudan SQL raporu olarak kullanılabilir.

### Ayarlar

| Ayar | Varsayılan | Açıklama |
|---|---|---|
| `DATABASE_URL` (`.env`) | — | `postgresql://kullanici:sifre@localhost:5432/siirt_fistik` |
| `TEST_DATABASE_URL` (`.env`) | — | Sözleşme testleri için ayrı veritabanı |
| `--server.port` / `PORT` | `8000` | Sunucu portu |
| `DEMO_VERISI` / `--fistik.demo-verisi` | `false` | `true` ise boş veritabanına örnek veri yükler |
| `DEMO_HESAPLARI` | `true` | Giriş ekranında tek tıkla demo girişi |
| `GECE_SIFIRLAMA` | `false` | `true` ise demo verisini her gece 03:00'te ve açılışta o güne ait tartım yoksa sıfırlar |
| `JWT_SECRET` (`.env`) | geçici | Giriş token'larının imza anahtarı (en az 32 karakter) |
| `SEED_DEMO_DATA` | `true` | Kalite sınıfı, kesinti ve silo tanımlarını oluşturur |
| `YONETICI_KULLANICI`, `YONETICI_SIFRESI`, `YONETICI_AD` | — | Kalıcı yönetici hesabı: açılışta oluşturulur, gece sıfırlamasında silinmez. Şifre yalnızca ortam değişkeninde durur; panelden değiştirilebilir (`YONETICI_SIFRE_SIFIRLA=true` ile bir sonraki açılışta ortam değişkenindeki şifreye döner) |
| `SMS_SAGLAYICI` | `simulasyon` | `netgsm` ise gerçek SMS gönderilir (`SMS_KULLANICI`, `SMS_SIFRE`, `SMS_BASLIK` gerekir); eksikse simülasyona düşer |
| `SMS_ETKIN` | `true` | `false` ise tartım ve avans SMS'i hiç gönderilmez |
| `API_DOKUMANI` | `true` | `false` ise Swagger (`/docs`), ReDoc ve `/openapi.json` tamamen kapanır (gerçek kullanımda önerilir). Paneldeki "API" bağlantısı zaten yalnızca Yöneticiye görünür. |

### Kantar sahasında kullanım

Kantar sekmesi fare kullanmadan, klavye ve kantar indikatörüyle çalışacak şekilde tasarlandı.

**Kantar indikatörü (seri port).** Formun sağ üstündeki **Bağlan** düğmesiyle indikatörün bağlı olduğu COM portu seçilir (⚙ menüsünden baud hızı; çoğu cihazda 9600). Brüt ve dara alanlarındaki **⚖ Oku** düğmeleri (ya da `F2` / `F3`) indikatörden **kararlı** değeri okur:

- `US` (kararsız, araç hâlâ sallanıyor) satırları atlanır, `ST` (kararlı) satırı beklenir. Durum bayrağı göndermeyen cihazlarda art arda 3 aynı değer kararlı sayılır.
- Okunan değer alana **kilitli** yazılır ve fişte `(K)` olarak görünür. "Elle düzelt" ile kilit açılırsa ya da değer elle yazılırsa fişte `(E)` yazar; kaynak veritabanına da kaydedilir (`gross_source`, `tare_source`).
- Tarayıcı seri portu desteklemiyorsa (Firefox, Safari, telefon) ya da cihaz takılı değilse sistem durmaz: uyarı verir ve elle girişe izin verir.
- Web Serial yalnızca **Chrome / Edge masaüstü** ve **HTTPS** (ya da `localhost`) üzerinde çalışır. İzin verilen port bir sonraki açılışta sormadan bağlanır.

**Klavye akışı.** Plaka → Üretici (yazıp `Enter`; `↑` `↓` ile eşleşenler arasında gezilir) → Ürün türü (`←` `→`) → Brüt → Araç darası → Çuval sayısı → Kalite → Kaydet. `Enter` bir sonraki alana geçer; `F9` veya `Ctrl`+`Enter` kaydedip fişi basar. Fiş kapanınca imleç bir sonraki aracın plakasına döner.

**Çuval darası.** Net = Brüt − Araç darası − (Çuval sayısı × Çuval darası). Çuval darası ⚙ menüsünden ayarlanır (varsayılan 1 kg) ve o bilgisayarda saklanır.

**Ürün türü.** Kuru-kavlak ve yaş fıstığın kalite sınıfları, fiyatları ve siloları ayrıdır: `A` / `B` / `ISK` ve `YA` / `YB` / `YISK`. Fiyat ekranında iki grup ayrı görünür. Yaş fıstıkta nem sınırı uygulanmaz. Yaş fıstık kurutma firesi nedeniyle kuru-kavlağın yaklaşık %40–50'si fiyatından alınır; demo verisinde A / B / ISK = 300 / 260 / 150 ₺, YA / YB / YISK = 135 / 115 / 65 ₺. Eşikler örnektir; merkezin kendi yönergesine göre `quality_grades` tablosundan güncellenmelidir.

**Üretici cari hesap dökümü.** Üretici & Finans sekmesinde üretici seçilip **Cari Hesap Ekstresi Al** ile açılır; isteğe bağlı tarih aralığı seçilir. A4 sayfada:

- başlık, üretici bilgileri (ad soyad, T.C. kimlik / vergi no, üretici kodu, telefon) ve rapor tarihi
- tarih sıralı tek hareket tablosu: Tarih/Saat · Belge/Fiş No · İşlem türü (teslimat, kesinti, avans, ödeme; ödeme yöntemiyle) · Ürün & Sınıf · Miktar (kg) · Birim fiyat · Borç · Alacak · Bakiye (A: merkez borçlu, B: üretici borçlu)
- alt toplam: teslim edilen kg, brüt hakediş, kesintiler, verilen avans, nakit ve havale ödemeler, **kalan net bakiye**
- "Üretici İmzası" ve "Yetkili İmzası" alanları

**Yazdır / PDF** ile yazdırılır; yazdırma penceresinde "PDF olarak kaydet" seçilirse PDF olur.

**Müstahsil bilgilendirme SMS'i.** Tartım kaydedilince ve avans verilince üreticiye SMS gider:

> Sn. Fatma Demir, KF-2026-000007 ile 1.000 kg Kuru-kavlak A sınıfı fıstığınız teslim alınmıştır. Avans mahsubu sonrası net hakediş: 290.400,00 TL. Güncel bakiyeniz: 711.480,00 TL.

- Sağlayıcı `SmsSaglayici` arayüzüyle değiştirilebilir. Hazır olanlar: **Netgsm** (HTTP API) ve **simülasyon**. Yeni bir sağlayıcı (ör. İletiMerkezi) için arayüz uygulanıp `Sms.SmsAyarlari`'na eklenir.
- Varsayılan **simülasyon**dur: gerçek SMS gitmez, mesaj loglanır, panelde "SMS Gönderildi (Simülasyon)" bildirimi ve fişte "SMS Gönderildi ✓" görünür.
- SMS hiçbir zaman tartımı veya avansı bozmaz: işlem kaydedildikten sonra gönderilir; sağlayıcı hata verirse işlem geçerli kalır, fişte "SMS gönderilemedi" ve nedeni görünür.
- Her gönderim `sms_messages` tablosuna yazılır (`GET /api/v1/sms`).
- Tutarlar SMS'te "₺" yerine "TL" ile yazılır (₺ işareti standart SMS karakter setinde yok). Bu mesajlar bilgilendirme amaçlıdır; ticari ileti sayılmadığı için İYS onayı gerektirmez, yine de üreticinin telefonunun SMS için kullanılacağı kayıt sırasında kendisine söylenmelidir.

**Gün sonu raporu.** Kantar sekmesinde Bugünkü Teslimatlar tablosunun üstünden gün seçilip **Gün Sonu Raporu Al** ile indirilir. CSV dosyası Excel'de çift tıklayınca doğrudan açılır. İçinde özet (kg, tutar, kesinti, avans mahsubu, kalan hakediş), kasa (nakit / havale / çek ödemeler ve avanslar, kasadan çıkan nakit), ürün türü kırılımı, teslimat listesi, kasa hareketleri ve silo bazlı giren stok bulunur.

**Emanet fıstık.** Kantar formunda **Teslim şekli: Emanete bırak** seçilirse fıstık tartılır, kalite sınıfı belirlenir, lotu siloya girer ve üreticinin **emanet stoğuna** yazılır; fiyat kilitlenmez, hakediş ve cari hareket oluşmaz. Fişte "EMANET" yazar, üreticiye "emanet olarak teslim alınmıştır" SMS'i gider.

- Üretici bozdurmak istediğinde Üretici & Finans sekmesindeki teslimat satırında **Satışa Çevir** (Yönetici, Muhasebe) düğmesine basılır. Pencere bugünkü fiyat, çatlak primi, kesintiler ve avans mahsubuyla net hakedişi gösterir; onaylanınca fiyat **o anda** kilitlenir ve hakediş normal akışla oluşur.
- Emanet fişine sonradan fiyat yazılamaz; bir fiş yalnızca bir kez satışa çevrilebilir. Bu kurallar veritabanında da korunur.
- Silo kartlarında ve sevkiyat ekranında silodaki emanet miktarı görünür. Sevkiyatta önce merkezin satın aldığı lotlar çıkar, emanet en son kullanılır; emanete dokunacak bir çıkışta uyarı verilir.
- Gün sonu raporunda emanet teslimatlar ve o gün satışa çevrilenler ayrı listelenir.

**Numune analizi ve çatlak primi.** Kantar formunda **Randıman analizi (numuneden)** açılırsa numune gramı (varsayılan 100 g), çatlak gram ve kör/boş gram girilir; çatlak ve boş oranı hem ekranda hem sunucuda hesaplanır, gramlar da kaydedilir ve fişte yazar. Fiyat & Depo sekmesindeki **Çatlak Primi / İskontosu** kartından (Yönetici, Fiyat yöneticisi) ürün türü başına kural tanımlanır:

> Birim fiyat = baz fiyat + (çatlak % − baz çatlak %) × puan başı ₺, en fazla ± azami fark

Örneğin baz %85, puan başı 2 ₺, azami ±20 ₺ iken %90 çatlak +10 ₺/kg prim, %80 çatlak −10 ₺/kg iskonto alır. Kural kapalıyken baz fiyat aynen uygulanır. Prim fiyat kilidinin parçasıdır: fişte baz fiyat, prim ve birim fiyat ayrı görünür, sonradan değiştirilemez. Demo verisinde kuru-kavlak için kural açıktır.

**Satış ve sevkiyat (depodan çıkış).** **Sevkiyat** sekmesinden alıcı firmaya kamyonla çıkış yapılır (Yönetici ve Depo sorumlusu; diğer roller yalnızca görür). Akış ve klavye sırası: Alıcı → Plaka → Şoför → Çıkış silosu → Dara (boş araç, `F3`) → Brüt (dolu araç, `F2`) → Satış birim fiyatı → Ödeme durumu (nakit alındı / havale alındı / vadeli-açık hesap) → `F9` ile onay.

- Net çıkış kadar silo stoğu düşer. Düşüm, silodaki lotlardan **ilk giren ilk çıkar** sırasıyla yapılır; irsaliyede hangi üreticinin lotundan ne kadar çıktığı görünür.
- Silodaki stoktan fazla çıkış hem ekranda hem sunucuda reddedilir; veritabanı da bir lotun eksiye düşmesine izin vermez (`trg_stock_non_negative`).
- Alıcılar müşteri kartı olarak saklanır; "ANTEP FISTIKÇILIK" ile "Antep Fıstıkçılık" aynı müşteridir. Vadeli satışların açık hesap toplamı müşteri listesinde görünür.
- Onaydan sonra **Kantar Çıkış ve Sevk İrsaliyesi** 80 / 58 mm termal veya A5 olarak basılır. Bu belge kantar çıkış belgesidir; resmî e-irsaliye yerine geçmez.
- Gün sonu raporunda sevkiyatlar, satış tahsilatı (nakit / havale / vadeli), kasa net nakdi ve silo bazlı giren / çıkan kg da yer alır.

**Termal fiş.** Fiş 80 mm (veya ⚙ menüsünden 58 mm) rulo kâğıda göre tasarlandı: sayfanın geri kalanı yazdırılmaz, rulodan boş kâğıt akmaz. Yazıcı ayarında kenar boşluğu **Yok**, ölçek **%100** seçilmelidir. Yazdırma penceresini her seferinde görmemek için kantar bilgisayarında Chrome `--kiosk-printing` parametresiyle açılabilir; fiş varsayılan yazıcıya doğrudan basılır.

### Kullanıcılar ve roller

Panel açıldığında giriş ekranı gelir. Her rol yalnızca kendi işlemlerini yapabilir. Bu kurallar **sunucuda** (`@PreAuthorize`) uygulanır; panel ayrıca yetkisi olmayan butonları kilitler ve nedenini gösterir.

| Rol | Kullanıcı adı | Yapabildikleri |
|---|---|---|
| Yönetici | `admin` | Her şey |
| Kantar memuru | `kantar` | Tartım ve fiş kesme (canlı hakediş önizlemesi dahil) |
| Muhasebe | `muhasebe` | Üretici kaydı, avans verme, hakediş ödemesi |
| Fiyat yöneticisi | `fiyat` | Günlük taban fiyat belirleme |
| Depo sorumlusu | `depo` | Satış ve sevkiyat (depodan çıkış), stok ve raporları izleme |

Okuma işlemleri (listeler, raporlar, ekstre) giriş yapmış herkese açıktır. Her kayıt, işlemi yapan kullanıcıyla imzalanır (`weighed_by_id`, `created_by_id`…).

**Yeni kullanıcılar nasıl eklenir?** Herkese açık bir "kaydol" yoktur, çünkü rolü kullanıcı değil yönetici belirler (aksi halde herkes kendini Muhasebe yapıp ödeme girebilirdi). İki yol vardır:

1. **Hesap talebi:** Giriş ekranındaki **"Hesap talep edin"** bağlantısıyla çalışan ad, kullanıcı adı, görev ve şifresini gönderir. Hesap, yönetici onaylayana kadar giriş yapamaz. Yönetici rolü talep edilemez.
2. **Yöneticinin eklemesi:** Yönetici **Kullanıcılar** sekmesinden kullanıcıyı geçici bir şifreyle ekler. Kullanıcı ilk girişte kendi şifresini belirler.

Yönetici aynı sekmeden talepleri onaylar (gerekirse rolü değiştirerek) veya reddeder, rol değiştirir, hesabı pasifleştirir ve şifre sıfırlar. Rol değişikliği ve pasifleştirme, kullanıcının açık oturumunda bir sonraki istekte geçerli olur (rol token'dan değil veritabanından okunur). Her kullanıcı üst bardaki **Şifre** düğmesiyle kendi şifresini değiştirebilir.

- **Demo modu** (varsayılan açık): Giriş ekranında her rol için **tek tıkla giriş** düğmesi vardır. Hesaplar ilk açılışta oluşturulur; başlangıç şifresi `application.properties` içindeki `fistik.baslangic-sifresi` ayarıdır. Herkese açık demoda bu hesaplar kilitlidir (rolü, şifresi değiştirilemez); denemek için yeni kullanıcı eklenebilir. Gece sıfırlaması açıksa sonradan eklenen kullanıcılar da her gece silinir.
- **Gerçek kullanım:** `DEMO_HESAPLARI=false` ve güçlü bir `BASLANGIC_SIFRESI` ortam değişkeni verin.
- **Güvenlik ayrıntıları:**
  - Şifreler BCrypt özeti olarak saklanır.
  - Token 8 saat (bir vardiya) geçerlidir; `.env` içindeki `JWT_SECRET` ile imzalanır.
  - Yanlış girişte "kullanıcı adı veya şifre hatalı" denir; hangi kullanıcı adlarının var olduğu sızdırılmaz.

### İnternette yayınlama

Depoda bir `Dockerfile` ve bir Render kurulum dosyası (`render.yaml`) bulunur. Sistem ücretsiz olarak **Neon** (PostgreSQL) ve **Render** (sunucu) üzerinde yayınlanabilir. Herkese açık demo için her gece 03:00'te demo verisi kendini sıfırlar. Adım adım rehber: **[docs/YAYINLAMA.md](docs/YAYINLAMA.md)**

### Python prototipi (isteğe bağlı)

İnternetsiz ve PostgreSQL'siz hızlı bir gösterim için Python prototipi hâlâ çalışır: **`python-demo.bat`** ayrı bir SQLite dosyasıyla (`demo.db`) açılır. Python sunucusu `.\.venv\Scripts\python.exe -m uvicorn app.main:app` komutuyla da başlatılabilir; `.env` varsa aynı PostgreSQL veritabanını kullanır.

---

## 6. API uç noktaları

Tüm uç noktalar `/api/v1` önekiyle başlar. Ayrıntılı istek/yanıt örnekleri `/docs` adresindedir.

Giriş dışındaki tüm `/api/v1` uç noktaları `Authorization: Bearer <token>` başlığı ister. Swagger'da sağ üstteki **Authorize** düğmesiyle token girilebilir.

| Metot | Yol | Açıklama | Başarı |
|---|---|---|---|
| **Oturum** ||||
| `POST` | `/oturum/giris` | Kullanıcı adı ve şifreyle giriş → JWT | 200 |
| `POST` | `/oturum/demo-giris` | Demo hesabıyla tek tıkla giriş (yalnızca demo modunda) | 200 |
| `GET` | `/oturum/demo-hesaplar` | Giriş ekranındaki demo hesapları (şifre içermez) | 200 |
| `GET` | `/oturum/ben` | Oturumdaki kullanıcı, rolü ve yetkileri | 200 |
| `POST` | `/oturum/hesap-talebi` | Hesap talebi (giriş gerektirmez; yönetici onayına düşer) | 201 |
| `POST` | `/oturum/sifre-degistir` | Kendi şifresini değiştirme | 200 |
| **Kullanıcılar** *(yalnızca Yönetici)* ||||
| `GET` | `/kullanicilar` | Kullanıcılar ve bekleyen talepler | 200 |
| `POST` | `/kullanicilar` | Geçici şifreyle kullanıcı ekleme | 201 |
| `POST` | `/kullanicilar/{id}/onayla` | Talebi onaylama (isteğe bağlı farklı rol) | 200 |
| `DELETE` | `/kullanicilar/{id}/talep` | Talebi reddetme | 204 |
| `PATCH` | `/kullanicilar/{id}` | Ad, rol veya aktiflik değiştirme | 200 |
| `POST` | `/kullanicilar/{id}/sifre-sifirla` | Geçici şifre belirleme | 200 |
| **Üreticiler** ||||
| `POST` | `/ureticiler` | Yeni üretici (TC/VKN, telefon, IBAN doğrulamalı) | 201 |
| `GET` | `/ureticiler` | Sayfalı, aranabilir üretici listesi | 200 |
| `GET` | `/ureticiler/{id}` | Üretici detayı | 200 |
| `GET` | `/ureticiler/{id}/bakiye-ekstre?baslangic=&bitis=` | Cari hesap özeti, teslimatlar (ürün türüyle), avanslar, ödemeler, yürüyen bakiyeli hareketler | 200 |
| **Kantar ve Alım** ||||
| `POST` | `/tartimlar` | Tartım + kalite → fiyat kilidi → hakediş → stok (tek transaction) | 201 |
| `POST` | `/tartimlar/onizleme` | Kaydetmeden tahmini hakediş (paneldeki canlı hesaplama) | 200 |
| `GET` | `/tartimlar?tarih=` | Günün teslimatları | 200 |
| `GET` | `/tartimlar/{id}` | Teslimat / kantar fişi detayı | 200 |
| **Fiyat Yönetimi** ||||
| `POST` | `/fiyatlar` | Kalite sınıflarına göre taban fiyat girişi | 201 |
| `GET` | `/fiyatlar/guncel` | Şu an geçerli fiyatlar | 200 |
| **Finans** ||||
| `POST` | `/avanslar` | Üreticiye avans verme | 201 |
| `GET` | `/hakedisler/{id}` | Hakediş detayı (kesintiler, avans mahsupları) | 200 |
| `POST` | `/hakedisler/{id}/odeme-yap` | Kısmi veya tam ödeme (önce açık avansı mahsup eder) | 201 / 200* |
| **Depo ve Raporlar** ||||
| `GET` | `/depo/stok-durumu?gruplama=silo\|kalite` | Silo veya kalite sınıfına göre stok ve doluluk | 200 |
| `GET` | `/raporlar/gunluk-ozet?tarih=` | Günlük alım (kg), ödenen tutar, ortalama randıman | 200 |
| `GET` | `/raporlar/uretici-bakiyeleri` | Tüm üreticilerin bakiye ve açık avansı | 200 |
| `GET` | `/raporlar/gun-sonu?tarih=` | Gün sonu kasa & kantar raporu (JSON) | 200 |
| `POST` | `/tartimlar/{id}/satisa-cevir` | Emaneti bugünkü fiyat ve çatlak primiyle satışa çevir (Yönetici, Muhasebe) | 200 |
| `GET` | `/tartimlar/{id}/satis-onizleme` | Emanet bugün satılsaydı: fiyat, prim, kesinti, avans mahsubu, net | 200 |
| `GET` | `/fiyatlar/catlak-primi` | Çatlak primi / iskontosu kuralları | 200 |
| `PUT` | `/fiyatlar/catlak-primi/{urunTuru}` | Kuralı güncelle (Yönetici, Fiyat yöneticisi) | 200 |
| **Satış ve Sevkiyat** ||||
| `POST` | `/sevkiyatlar` | Sevkiyat kaydı; silo stoğu lot bazında düşer (Yönetici, Depo sorumlusu) | 201 |
| `GET` | `/sevkiyatlar?tarih=` | Sevkiyatlar (tarih yoksa son 100) | 200 |
| `GET` | `/sevkiyatlar/{id}` | Sevkiyat detayı ve çıkan lotlar (irsaliye) | 200 |
| `GET` | `/sms?uretici_id=` | Gönderilen bilgilendirme SMS'leri (Yönetici, Muhasebe) | 200 |
| `GET` | `/musteriler` | Alıcı firmalar, satış toplamları ve açık hesap | 200 |
| `GET` | `/raporlar/gun-sonu.csv?tarih=` | Aynı rapor, Excel'de açılan CSV (UTF-8 BOM, `;`, ondalık virgül) | 200 |
| **Sistem** ||||
| `GET` | `/saglik` *(önek yok)* | Sağlık kontrolü | 200 |

\* Kalan tutarın tamamı avansa mahsup edildiyse ödeme kaydı oluşmaz ve **200** döner.

### Hata yanıtları

| Kod | Ne zaman | Örnek gövde |
|---|---|---|
| 400 | İş kuralı ihlali | `{"hata": "A sınıfı için tartım anında (22.10.2026 08:15) onaylı fiyat yok."}` |
| 401 | Giriş yok / token geçersiz / yanlış şifre | `{"hata": "Oturum açmanız gerekiyor."}` |
| 403 | Rolün yetkisi yok | `{"hata": "Bu işlem için yetkiniz yok (rolünüz: Kantar memuru)."}` |
| 404 | Kayıt yok | `{"hata": "Üretici bulunamadı (id=42)."}` |
| 409 | Tekrarlı kayıt | `{"hata": "Bu TC Kimlik No ile kayıtlı bir üretici zaten var (id=1)."}` |
| 422 | Doğrulama hatası | `{"hata": "Gönderilen veriler geçersiz.", "detaylar": [{"alan": "kalite.nem_orani", "mesaj": "En fazla 100 olabilir."}]}` |

### Örnek: tartım kaydı

```bash
curl -X POST http://127.0.0.1:8000/api/v1/tartimlar -H "Content-Type: application/json" -d "{\"uretici_id\": 1, \"plaka\": \"56 ABC 123\", \"brut_kg\": 4800, \"dara_kg\": 3800, \"kalite\": {\"catlak_orani\": 90, \"randiman\": 58, \"bos_orani\": 2, \"curuk_orani\": 1, \"nem_orani\": 6}}"
```

---

## 7. Ekran görüntüleri

**Günlük özet kartları** — bugünkü alım, brüt tutar, kasadan çıkan ödeme, ortalama randıman

![Günlük özet](docs/ekran-goruntuleri/01-ozet-paneli.png)

| **Kantar & Teslimat** — tartım ve kalite girişi, canlı hakediş hesabı | **Kantar fişi** — kilitli fiyat, kesintiler, yazdırılabilir |
|---|---|
| ![Kantar ekranı](docs/ekran-goruntuleri/02-kantar-ekrani.png) | ![Kantar fişi](docs/ekran-goruntuleri/03-kantar-fisi.png) |

**Üretici & Finans** — üretici listesi, cari bakiye, açık avans, hakedişler ve ödeme

![Üretici ve finans](docs/ekran-goruntuleri/04-uretici-finans.png)

**Fiyat & Depo** — günlük taban fiyat paneli, silo doluluk kartları, kaliteye göre stok

![Fiyat ve depo](docs/ekran-goruntuleri/05-fiyat-depo.png)

**Swagger** — `/docs` adresindeki etkileşimli API dokümantasyonu

![Swagger](docs/ekran-goruntuleri/06-swagger-docs.png)

---

## 8. Testler

**Java birim testleri (JUnit 5)** — veritabanı gerektirmez:

```bash
cd java-backend
.\mvnw.cmd test
```

`KurallarTest`: TC Kimlik ve IBAN algoritmaları, plaka/telefon normalizasyonu, kuruş yuvarlama, kesinti yöntemleri, Türkiye saatine göre gün sınırı.

**Sözleşme testleri (uçtan uca)** — aynı test paketi hem Java hem Python sunucusuna karşı çalışır. Böylece iki sürümün aynı davrandığı kanıtlanır:

```bash
# Java sunucusunu test veritabanına bağlı başlatın (ayrı bir terminalde):
#   $env:DATABASE_URL = "<.env içindeki TEST_DATABASE_URL>"; java -jar java-backend\target\siirt-fistik.jar --server.port=8020
.\.venv\Scripts\python.exe -m pytest -q --java http://127.0.0.1:8020   # Java (79 test: 27 + 10 güvenlik + 9 kullanıcı + 8 saha + 4 rapor + 8 sevkiyat + 4 SMS + 9 emanet/prim)
.\.venv\Scripts\python.exe -m pytest -q --pg                           # Python + PostgreSQL (27 test)
.\.venv\Scripts\python.exe -m pytest -q                                # Python + SQLite (19 test)
```

- **`tests/test_api.py`** (19 test): Uç noktaların hepsini HTTP üzerinden dener. Kapsam:
  - doğrulama hataları (negatif kg, %100'ü aşan oran, geçersiz TC/IBAN)
  - fiyat kilidi ve aynı gün çoklu teslimat
  - avansın teslimattan büyük olması ve sonradan verilen avansın mahsubu
  - parçalı ödeme, stok ve günlük rapor
  - panelin ve doküman sayfalarının hiçbir dış adrese bağlı olmaması
- **`tests/test_postgres.py`** (8 test): Uygulamayı atlayıp doğrudan SQL ile veri bozmaya çalışır ve PostgreSQL'in reddettiğini doğrular. Kapsam: cari hesap defterinin değiştirilememesi, onaylı ve kilitli fiyatların korunması, çakışan fiyat aralığı, hakediş formülü, yanlış kalitenin siloya girememesi, rapor view'larının API ile aynı sonucu vermesi.
- **`tests/test_guvenlik.py`** (10 test, yalnızca Java): Kapsam:
  - giriş yapılmadan API'ye erişilememesi; sahte token ve yanlış şifrenin reddi
  - yanlış girişte kullanıcı adlarının sızdırılmaması
  - rol kuralları (kantar memuru ödeme yapamaz, muhasebe tartım yapamaz, depo sorumlusu avans ve ödeme yapamaz)
  - her kaydın işlemi yapan kullanıcıyla imzalanması
- **`tests/test_kullanicilar.py`** (9 test, yalnızca Java): Kapsam:
  - hesap talebinin onaylanmadan giriş yapamaması, onayda rolün değiştirilebilmesi, reddedilen talebin silinmesi
  - yönetici rolü talep edilememesi, tekrarlı kullanıcı adı ve kısa şifrenin reddi
  - geçici şifrenin ilk girişte değiştirilmesi ve şifre sıfırlama
  - pasifleştirmenin ve rol değişikliğinin açık oturumda hemen geçerli olması
  - demo hesaplarının ve yöneticinin kendi hesabının korunması
- **`tests/test_emanet.py`** (9 test, yalnızca Java): emanetin siloya girip hakediş ve cari hareket oluşturmaması; fiyat yokken de alınabilmesi; satışa çevirmenin o günün fiyatıyla ve avans mahsubuyla hakediş oluşturması, ikinci kez çevrilememesi, yetkisi; sevkiyatta önce merkezin kendi lotlarının çıkması; çatlak primi, iskontosu ve azami sınır; numune gramlarından oran hesabı; veritabanının emanet fişine fiyat yazılmasını engellemesi.
- **`tests/test_sms.py`** (4 test, yalnızca Java): tartımda ve avansta SMS metni ve simülasyon durumu, fişte SMS bilgisinin görünmesi, SMS kayıt listesi; cari dökümde hareketlerin belge no, ürün & sınıf, kg ve birim fiyat içermesi.
- **`tests/test_sevkiyat.py`** (8 test, yalnızca Java): ilk giren ilk çıkar lot düşümü ve tutar; stoktan fazla ve boş silodan çıkışın reddi; doğrulamalar; yalnızca Yönetici ve Depo sorumlusunun sevkiyat yapabilmesi; müşteri kartının Türkçe harf farkıyla tekrarlanmaması ve açık hesap toplamı; veritabanının eksi stoğu reddetmesi; gün sonu raporunda sevkiyat ve tahsilat.
- **`tests/test_raporlar.py`** (4 test, yalnızca Java): cari dökümde ürün türü, avans ve ödeme listeleri ve dönem filtresi; gün sonu raporunda nakit/havale ayrımı, kasadan çıkan nakit, kalan hakediş, ürün türü ve silo girişleri; CSV'nin Excel uyumu (BOM, `;`, ondalık virgül).
- **`tests/test_saha.py`** (8 test, yalnızca Java): Kapsam:
  - çuval darasının nete ve önizlemeye yansıması, neti sıfırlayan çuval darasının reddi
  - yaş fıstığın kendi sınıfı, fiyatı ve silosuyla alınması (%35 nemle Iskarta sayılmaması); yaş fiyatı yoksa kuru fiyatla alınmaması
  - elle atanan sınıfın ürün türüyle uyuşması, ağırlık kaynağının (kantar / elle) kaydedilmesi

> PostgreSQL testleri (`--pg`) test veritabanının şemasını silip yeniden kurar. Ardından Java testlerini çalıştıracaksanız Java sunucusunu yeniden başlatın; Flyway şemayı tamamlar.

> Java'ya geçişte bu testler bir sıralama hatasını yakaladı: Hibernate, yeni fiyatı onaylayan güncellemeyi eski fiyatı kapatan güncellemeden önce gönderiyordu. O an iki onaylı fiyat çakıştığı için PostgreSQL'in `EXCLUDE` kısıtı işlemi reddetti. Hata `Kurallar.fiyatOnayla` içinde düzeltildi; veritabanı koruması amacına uygun çalıştı.

---

## 9. Proje yapısı

```
siirt-fistik-sistemi/
├── baslat.bat                Java sunucusunu derler/başlatır, paneli açar (Windows)
├── Dockerfile, render.yaml   Bulutta yayın (Docker imajı, Render Blueprint)
├── java-backend/             ★ Ana sistem: Java 21 + Spring Boot 3
│   ├── pom.xml, mvnw.cmd     Maven projesi ve sarmalayıcısı
│   └── src/
│       ├── main/java/tr/edu/siirt/fistik/
│       │   ├── SiirtFistikApplication.java   Giriş noktası
│       │   ├── alan/         19 JPA varlığı + enum'lar
│       │   ├── kural/        Kurallar (iş kuralları), Dogrulama, Hatalar
│       │   ├── servis/       Tartım, Finans, Fiyat, Rapor, Üretici servisleri; başlangıç ve demo verisi
│       │   ├── api/          REST kontrolcüleri, istek/yanıt record'ları, hata yönetimi, @Gecerli
│       │   ├── web/          Swagger/ReDoc ve OpenAPI ayarları
│       │   └── config/       .env okuyucu, JSON (snake_case, BigDecimal → metin)
│       ├── main/resources/
│       │   ├── application.properties
│       │   ├── db/migration/ V1__sema.sql, V2__tetikleyiciler_ve_viewlar.sql (Flyway)
│       │   └── static/       Panel (index.html, redoc.html, static/css·js·img·vendor)
│       └── test/java/        JUnit testleri
├── app/                      Python (FastAPI) prototipi — panelin kaynak şablonları da burada
├── db/                       schema.sql, postgres_triggers_views.sql
├── docs/                     GEREKSINIMLER.md, UML.md, ER.md, ekran-goruntuleri/
├── scripts/
│   ├── postgres_kurulum.py   PostgreSQL kullanıcı/veritabanı kurulumu, .env üretimi
│   ├── java_panel_aktar.py   Paneli (app/templates) Java projesine aktarır
│   ├── demo.py, demo_verisi.py, scenario_test.py, export_ddl.py   Python prototipi araçları
├── tests/                    Ortak sözleşme testleri (Java: --java, Python: --pg)
├── python-demo.bat           Python prototipi, SQLite ile çevrimdışı demo
├── requirements.txt          Python bağımlılıkları (kurulum betiği ve testler için)
└── .env.example              Ortam değişkenleri örneği (.env Git'e yüklenmez)
```

---

## 10. Bilinen kısıtlar ve geliştirme önerileri

| Konu | Mevcut durum | Öneri |
|---|---|---|
| Şifre unutma | Şifreyi yalnızca yönetici sıfırlayabilir; hatalı giriş denemesi sınırı yok | E-postayla sıfırlama bağlantısı ve art arda hatalı girişte geçici kilit |
| Kesinti oranları | Örnek değerler (stopaj %2, borsa %0,2, komisyon %1) | Güncel mevzuata göre yönetim ekranından düzenleme |
| Tailwind | Tarayıcıda derleyen Play CDN sürümü (yerel dosya) | Üretimde Tailwind CLI ile önceden derlenmiş CSS |
| Kantar entegrasyonu | Seri port (Web Serial) ile okunur; yalnızca Chrome/Edge masaüstü. Kantar kaynağı tarayıcıdan bildirildiği için tek başına hileye karşı kesin kanıt değildir | Kantar bilgisayarında indikatörü sunucuya doğrudan imzalı gönderen küçük bir servis; TCP/IP indikatör desteği |
| Raporlar | Cari döküm yazdır / PDF (tarayıcı), gün sonu raporu CSV | Sunucuda üretilen PDF ve gerçek .xlsx, sezonluk raporlar |

---

## Lisans

Bu proje eğitim amaçlı geliştirilmiştir. Arayüzde kullanılan üçüncü taraf kütüphanelerin lisansları için:
[`app/static/vendor/README.md`](app/static/vendor/README.md)

Önceki Python (FastAPI) sürümüne `git checkout python-surum` ile dönülebilir.
