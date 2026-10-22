# Siirt Fıstığı Alım ve Üretici Ödeme Sistemi

> Yazılım Mühendisliği Dönem Projesi · Siirt Üniversitesi
> **Hazırlayan:** [Nisanur HARMAN, Vesile KARADEMİR] · [230304014, 230304034] · **Danışman:** [Prof. Dr. Musa ATAŞ] · **Dönem:** 2026–2027 Güz

Siirt fıstığı alım merkezlerinde yapılan işlerin tamamını tek sistemde toplayan bir web uygulaması:
kantar tartımı, kalite analizi, günlük fiyat, üretici hakedişi, avans, ödeme ve depo stoğu.
Sistem bir **REST API** (FastAPI) ve bu API'yi kullanan bir **web yönetim paneli**nden oluşur.
Panel dahil her şey **internetsiz** çalışır.

![Günlük özet kartları](docs/ekran-goruntuleri/01-ozet-paneli.png)

---

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
| Kalite | Çatlak, randıman, boş, çürük ve nem oranından otomatik sınıf (A / B / Iskarta) |
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
- **Para hassasiyeti:** Hiçbir yerde `float` kullanılmaz. Veritabanında `NUMERIC`, Python'da `Decimal` ve `ROUND_HALF_UP` kullanılır. API para değerlerini metin olarak döndürür (`"300000.00"`).

---

## 3. Sistem mimarisi

Katmanlı bir mimari kullanılır. Her katman yalnızca bir altındaki katmanı bilir.

```mermaid
flowchart TB
    subgraph İstemci
        P["Web Paneli<br/>Jinja2 + Alpine.js + Tailwind"]
        S["Swagger / ReDoc"]
    end
    subgraph "FastAPI Uygulaması"
        R["API Router'ları<br/>app/api/v1"]
        V["Pydantic Şemaları<br/>doğrulama, 422"]
        SV["Servisler<br/>app/services"]
        D["İş Kuralları<br/>app/domain/rules.py"]
        M["ORM Modelleri<br/>SQLAlchemy 2.0"]
    end
    DB[("SQLite / PostgreSQL<br/>+ trigger'lar, view'lar")]

    P -- "fetch /api/v1 (JSON)" --> R
    S --> R
    R --> V --> SV --> D --> M --> DB
```

| Katman | Sorumluluk |
|---|---|
| **Sunum** (`app/templates`, `app/static`) | Tek sayfalık panel. Veriyi yalnızca REST API'den alır; iş kuralı içermez. |
| **API** (`app/api`) | HTTP yönlendirmesi, `get_db` bağımlılığı ve hata → HTTP kodu eşlemesi (400, 404, 409, 422). |
| **Şemalar** (`app/schemas`) | İstek ve yanıt modelleri. TC, IBAN, plaka ve oran doğrulaması burada yapılır; hata alan bazlı Türkçe mesajla döner. |
| **Servisler** (`app/services`) | İş akışları. Örneğin tartım → kalite → fiyat kilidi → hakediş → stok adımları tek transaction'da çalışır. |
| **Alan / iş kuralları** (`app/domain/rules.py`) | Fiyat bulma, FIFO avans mahsubu, kesinti hesabı, ödeme dağıtımı. HTTP'den bağımsızdır, doğrudan test edilebilir. |
| **Veri** (`app/db`) | 19 tablo, CHECK / UNIQUE / EXCLUDE kısıtları, trigger'lar ve rapor view'ları. |

Bir kuralın birden fazla katmanda tekrarlanması bilinçli bir tercihtir (derinlemesine savunma). Örneğin "net kg pozitif olmalı" kuralı Pydantic şemasında, servis katmanında ve veritabanındaki `CHECK` kısıtında ayrı ayrı denetlenir.

Veritabanı şemasının ayrıntıları ve ER diyagramı: **[docs/ER.md](docs/ER.md)**

---

## 4. Kullanılan teknolojiler

| Alan | Teknoloji | Neden |
|---|---|---|
| Dil | Python 3.11+ | Okunabilirlik; `Decimal` ile kesin para hesabı |
| Web çatısı | **FastAPI** | Tip tabanlı doğrulama, otomatik OpenAPI/Swagger, yüksek performans |
| Doğrulama | **Pydantic v2** | İstek/yanıt şemaları, alan bazlı hata mesajları |
| ORM | **SQLAlchemy 2.0** | Tipli `Mapped[...]` modelleri; hem SQLite hem PostgreSQL desteği |
| Veritabanı | SQLite (geliştirme/demo), **PostgreSQL 15+** (üretim) | PostgreSQL'de `EXCLUDE` kısıtı, trigger'lar ve `timestamptz` |
| Arayüz | **Jinja2** + **Alpine.js** + **Tailwind CSS** | Node/React derleme adımı olmadan reaktif, modern arayüz |
| Sunucu | Uvicorn | ASGI sunucusu |
| Test | pytest + FastAPI `TestClient` | Her test kendi bellek içi veritabanında çalışır |

Arayüz kütüphaneleri (Tailwind, Alpine.js, Inter yazı tipi, Swagger UI, ReDoc) `app/static/vendor/` klasöründedir. Sistem **hiçbir dış adrese istek atmaz**.

---

## 5. Kurulum ve çalıştırma

### Gereksinimler
- Windows 10/11, macOS veya Linux
- Python **3.11 veya üstü** (`python --version`)
- İnternet yalnızca ilk kurulumda, paketleri indirmek için gerekir.

### ⚡ Tek komutla demo (önerilen)

**Windows:** proje klasöründeki **`demo.bat`** dosyasına çift tıklayın. Betik şunları yapar:
1. İlk çalıştırmada sanal ortamı (`.venv`) oluşturur ve bağımlılıkları kurar.
2. `demo.db` veritabanını sıfırdan kurar ve örnek veriyi yükler: 4 üretici, günlük fiyatlar, 1 avans, 6 teslimat, 1 ödeme.
3. Sunucuyu başlatır ve paneli tarayıcıda açar.

Sanal ortam zaten kuruluysa aynı işi tek komutla yapabilirsiniz:

```bash
.\.venv\Scripts\python.exe -m scripts.demo
```

| Seçenek | Açıklama |
|---|---|
| `--koru` | Önceki demo verisini silmeden devam eder |
| `--port 8080` | Farklı bir port kullanır |
| `--tarayici-acma` | Tarayıcıyı otomatik açmaz |

Demo, asıl çalışma veritabanına (`siirt_fistik.db`) dokunmaz.

### Adım adım kurulum

```bash
git clone <depo-adresi> siirt-fistik-sistemi
cd siirt-fistik-sistemi
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```
> macOS/Linux'ta `.\.venv\Scripts\python.exe` yerine `.venv/bin/python` yazın.

Sunucuyu başlatmak için (boş veritabanı, `siirt_fistik.db`):

```bash
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

| Adres | İçerik |
|---|---|
| http://127.0.0.1:8000/ | Web yönetim paneli |
| http://127.0.0.1:8000/docs | Swagger UI (API'yi tarayıcıdan deneme) |
| http://127.0.0.1:8000/redoc | ReDoc (okunabilir API dokümanı) |

İlk açılışta yönetici kullanıcı, A/B/Iskarta kalite sınıfları, örnek kesinti türleri ve silolar otomatik oluşturulur. Tartım yapabilmek için önce **Fiyat & Depo** sekmesinden günlük fiyat girilmelidir.

### Ortam değişkenleri

| Değişken | Varsayılan | Açıklama |
|---|---|---|
| `DATABASE_URL` | `sqlite:///./siirt_fistik.db` | PostgreSQL için: `postgresql+psycopg://kullanici:sifre@localhost:5432/siirt_fistik` |
| `SEED_DEMO_DATA` | `1` | İlk açılışta kalite sınıfı, kesinti ve silo tanımlarını oluşturur |
| `SQL_ECHO` | `0` | `1` ise üretilen SQL sorgularını loglar |

PostgreSQL kullanıldığında uygulama `btree_gist` eklentisini, trigger'ları ve rapor view'larını (`db/postgres_triggers_views.sql`) kendisi kurar. Saf SQL şeması `db/schema.sql` dosyasındadır; `python -m scripts.export_ddl` komutuyla modellerden yeniden üretilebilir.

---

## 6. API uç noktaları

Tüm uç noktalar `/api/v1` önekiyle başlar. Ayrıntılı istek/yanıt örnekleri `/docs` adresindedir.

| Metot | Yol | Açıklama | Başarı |
|---|---|---|---|
| **Üreticiler** ||||
| `POST` | `/ureticiler` | Yeni üretici (TC/VKN, telefon, IBAN doğrulamalı) | 201 |
| `GET` | `/ureticiler` | Sayfalı, aranabilir üretici listesi | 200 |
| `GET` | `/ureticiler/{id}` | Üretici detayı | 200 |
| `GET` | `/ureticiler/{id}/bakiye-ekstre` | Cari hesap özeti, teslimatlar, açık avanslar, hareketler | 200 |
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
| **Sistem** ||||
| `GET` | `/saglik` *(önek yok)* | Sağlık kontrolü | 200 |

\* Kalan tutarın tamamı avansa mahsup edildiyse ödeme kaydı oluşmaz ve **200** döner.

### Hata yanıtları

| Kod | Ne zaman | Örnek gövde |
|---|---|---|
| 400 | İş kuralı ihlali | `{"hata": "A sınıfı için tartım anında (22.10.2026 08:15) onaylı fiyat yok."}` |
| 401 | Kullanıcı bulunamadı | `{"hata": "Kullanıcı bulunamadı veya pasif (id=9)."}` |
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

```bash
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m scripts.scenario_test
```

- **`tests/test_api.py`** (19 test): Uç noktaların hepsini HTTP üzerinden dener. Doğrulama hataları (negatif kg, %100'ü aşan oran, geçersiz TC/IBAN), fiyat kilidi, aynı gün çoklu teslimat, avansın teslimattan büyük olması, parçalı ödeme, sonradan verilen avansın mahsubu, stok ve günlük rapor kapsanır. Ayrıca panelin ve doküman sayfalarının hiçbir dış adrese bağlı olmadığı denetlenir. Her test kendi bellek içi veritabanında çalışır.
- **`scripts/scenario_test.py`**: İş kurallarını HTTP olmadan, saat saat kurgulanmış bir gün senaryosuyla doğrular: 07:00 avans, 08:00 / 11:30 / 14:00 teslimatlar, 12:00 fiyat değişimi.

---

## 9. Proje yapısı

```
siirt-fistik-sistemi/
├── demo.bat                  Tek tıkla kurulum + demo (Windows)
├── requirements.txt
├── app/
│   ├── main.py               FastAPI uygulaması (giriş noktası)
│   ├── web.py                Panel, /docs ve /redoc (yerel dosyalarla)
│   ├── api/
│   │   ├── deps.py           get_db, get_current_user
│   │   ├── errors.py         Hata → HTTP kodu, Türkçe doğrulama mesajları
│   │   └── v1/               ureticiler, tartimlar, fiyatlar, finans, depo_rapor
│   ├── schemas/              Pydantic istek/yanıt modelleri
│   ├── services/             İş akışları (tartım, finans, fiyat, rapor, üretici)
│   ├── domain/rules.py       İş kuralları (HTTP'den bağımsız)
│   ├── db/                   models.py, enums.py, session.py, seed.py
│   ├── core/                 Ayarlar, hata sınıfları, saat/para biçimlendirme
│   ├── templates/            Jinja2 şablonları (index.html + partials/)
│   └── static/               css/, js/app.js, img/, vendor/ (çevrimdışı kütüphaneler)
├── db/
│   ├── schema.sql            PostgreSQL DDL (modellerden üretilir)
│   └── postgres_triggers_views.sql   Trigger'lar ve rapor view'ları
├── docs/
│   ├── ER.md                 ER diyagramı ve iş kuralı → şema eşlemesi
│   └── ekran-goruntuleri/
├── scripts/
│   ├── demo.py               Tek komutla demo
│   ├── demo_verisi.py        Örnek veri yükleyici
│   ├── scenario_test.py      İş kuralı senaryo testi
│   └── export_ddl.py         Modellerden schema.sql üretimi
└── tests/                    pytest API testleri
```

---

## 10. Bilinen kısıtlar ve geliştirme önerileri

| Konu | Mevcut durum | Öneri |
|---|---|---|
| Kimlik doğrulama | Yok. İşlemi yapan kullanıcı `X-Kullanici-Id` başlığıyla verilir; başlık yoksa yönetici kabul edilir. | JWT/OAuth2 ve rol bazlı yetki (kantar memuru, muhasebe, fiyat yöneticisi) |
| Veritabanı göçleri | Tablolar açılışta `create_all` ile oluşturulur | Alembic migration'ları |
| PostgreSQL | Şema ve trigger'lar hazır; testler SQLite'ta çalışıyor | PostgreSQL üzerinde entegrasyon testleri (Docker) |
| Kesinti oranları | Örnek değerler (stopaj %2, borsa %0,2, komisyon %1) | Güncel mevzuata göre yönetim ekranından düzenleme |
| Tailwind | Tarayıcıda derleyen Play CDN sürümü (yerel dosya) | Üretimde Tailwind CLI ile önceden derlenmiş CSS |
| Kantar entegrasyonu | Ağırlık elle girilir | Kantar göstergesinden seri port/TCP ile otomatik okuma |
| Raporlar | Ekranda görüntüleme | PDF/Excel dışa aktarma, sezonluk raporlar |

---

## Lisans

Bu proje eğitim amaçlı geliştirilmiştir. Arayüzde kullanılan üçüncü taraf kütüphanelerin lisansları için:
[`app/static/vendor/README.md`](app/static/vendor/README.md)
