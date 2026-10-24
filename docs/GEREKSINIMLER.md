# Yazılım Gereksinimleri Belirtimi (SRS)

**Proje:** Siirt Fıstığı Alım ve Üretici Ödeme Sistemi · **Sürüm:** 2.0 (Java / Spring Boot) · **Tarih:** 23.10.2026
**Hazırlayanlar:** Nisanur HARMAN (230304014), Vesile KARADEMİR (230304034) · **Danışman:** Prof. Dr. Musa ATAŞ

> Bu belge IEEE 830 / ISO/IEC/IEEE 29148 yapısı esas alınarak sadeleştirilmiştir.
> UML diyagramları: [UML.md](UML.md) · Veritabanı şeması: [ER.md](ER.md)

---

## 1. Giriş

### 1.1 Amaç
Bu belge, Siirt fıstığı alım merkezlerinde kantar tartımından üretici ödemesine kadar olan sürecin
yazılım gereksinimlerini tanımlar. Geliştiriciler, test edenler ve proje değerlendiricileri için hazırlanmıştır.

### 1.2 Kapsam
Sistem şu işleri kapsar:
- Üretici kaydı
- Kantar tartımı ve kalite analizi
- Kalite sınıfına göre günlük fiyat
- Hakediş, avans ve kesinti hesabı
- Üretici ödemeleri
- Silo/lot bazlı stok
- Günlük raporlama

Sistem bir REST API ve tarayıcıdan kullanılan bir yönetim panelinden oluşur.

**Kapsam dışı:**
- Muhasebe/ERP entegrasyonu
- Banka ile otomatik EFT
- e-Fatura / e-Müstahsil
- Kantar cihazından otomatik ağırlık okuma
- Fıstık satışı / sevkiyat modülü

### 1.3 Tanımlar
| Terim | Anlamı |
|---|---|
| **Brüt / Dara / Net** | Dolu araç ağırlığı / boş araç ağırlığı / fıstığın ağırlığı (brüt − dara) |
| **Çatlak oranı** | Kabuğu doğal olarak açılmış fıstık yüzdesi; yüksek olması kaliteyi artırır |
| **Randıman** | İç fıstık oranı (%) |
| **Iskarta (ISK)** | En düşük kalite sınıfı |
| **Fiş / Lot** | Her teslimatın benzersiz kantar fişi numarası / stokta izlenen parti numarası |
| **Hakediş** | Bir teslimat için üreticiye ödenecek tutarın hesabı |
| **Avans mahsubu** | Önceden verilen avansın hakedişten düşülmesi |
| **Cari hesap** | Üreticinin tüm alacak/borç hareketlerinin tutulduğu hesap |
| **Fiyat kilidi (snapshot)** | Tartım anındaki onaylı fiyatın teslimata kopyalanıp sabitlenmesi |
| **FIFO** | İlk giren ilk çıkar: en eski avans önce mahsup edilir |

### 1.4 Paydaşlar ve kullanıcılar
| Aktör | Görevi | Sistemdeki rolü (`UserRole`) |
|---|---|---|
| Kantar memuru | Tartım ve kalite değerlerini girer, fiş keser | `SCALE_OPERATOR` |
| Kalite / laboratuvar | Numune analizini yapar | `QUALITY` |
| Fiyat yöneticisi | Günlük taban fiyatı belirler | `PRICE_MANAGER` |
| Muhasebe | Avans verir, ödeme yapar, ekstre alır | `ACCOUNTING` |
| Depo sorumlusu | Silo ve stok durumunu izler | `WAREHOUSE` |
| Yönetici | Raporları izler, tüm işlemleri yapabilir | `ADMIN` |
| Üretici | Sistemi kullanmaz; fiş ve ekstre alır | — |

> Sürüm 2.0'dan itibaren her kullanıcı şifreyle giriş yapar; işlemler role göre yetkilendirilir (bkz. 3.8 ve FOG-12).

---

## 2. Genel tanım

### 2.1 Ürün bakış açısı
Bağımsız bir web uygulamasıdır. Katmanlı mimaride Java 21 / Spring Boot 3 sunucusu, JPA (Hibernate) ve
PostgreSQL veritabanı kullanır. Panel, sunucunun REST API'sini kullanan tek sayfalık bir uygulamadır.
Sistem önce Python (FastAPI) ile prototiplendi; iki sürüm aynı REST sözleşmesini ve aynı testleri paylaşır.

### 2.2 Varsayımlar ve kısıtlar
- **K-1** Türkiye saati (UTC+3) esas alınır; zaman damgaları veritabanında UTC saklanır.
- **K-2** Para birimi Türk lirasıdır (TRY); tutarlar kuruş hassasiyetindedir.
- **K-3** Kesinti oranları (stopaj, borsa tescil, komisyon) **örnek değerlerdir**; güncel mevzuata göre güncellenmelidir.
- **K-4** Sistem internet bağlantısı olmadan çalışabilmelidir (sınıf / alım merkezi ortamı).
- **K-5** Sürüm 1.0 tek alım merkezi içindir (çoklu şube yok).

---

## 3. Fonksiyonel gereksinimler

**Durum:** ✅ karşılandı · 🟡 kısmen (iş kuralı var, API/arayüz yok) · ⏳ planlandı

### 3.1 Üretici yönetimi (URT)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-URT-01 | Sistem gerçek kişi (TC Kimlik No) ve tüzel kişi (Vergi No) üretici kaydedebilmelidir. | Yüksek | ✅ |
| FG-URT-02 | TC Kimlik No 11 hane ve resmi kontrol basamağı algoritmasıyla doğrulanmalıdır. | Yüksek | ✅ |
| FG-URT-03 | IBAN, TR biçiminde ve mod-97 kontrol basamağıyla doğrulanmalıdır. | Yüksek | ✅ |
| FG-URT-04 | Telefon farklı biçimlerde girilebilmeli ve `05XXXXXXXXX` biçimine çevrilmelidir. | Orta | ✅ |
| FG-URT-05 | Aynı TC/VKN ile ikinci kayıt engellenmelidir. | Yüksek | ✅ |
| FG-URT-06 | Üreticiler ad, TC, VKN veya telefonla aranabilmeli ve sayfalı listelenebilmelidir. | Orta | ✅ |
| FG-URT-07 | Bir üreticinin yalnızca bir varsayılan banka hesabı olabilmelidir. | Orta | ✅ |

### 3.2 Kantar ve kalite (KNT)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-KNT-01 | Brüt, dara ve araç plakası girilmeli; net kg = brüt − dara olarak hesaplanmalıdır. | Yüksek | ✅ |
| FG-KNT-02 | Negatif ağırlık, brütten büyük veya brüte eşit dara ve geçersiz plaka reddedilmelidir. | Yüksek | ✅ |
| FG-KNT-03 | **Bir üretici aynı gün birden fazla teslimat yapabilmeli; her teslimat benzersiz fiş ve lot numarası almalıdır.** | Yüksek | ✅ |
| FG-KNT-04 | Çatlak, randıman, boş, çürük ve nem oranları girilmeli; her oran 0–100 aralığında olmalıdır. | Yüksek | ✅ |
| FG-KNT-05 | Kalite sınıfı, tanımlı eşiklere göre otomatik belirlenmelidir (kuru-kavlak: A / B / Iskarta; yaş: YA / YB / Yaş Iskarta). | Yüksek | ✅ |
| FG-KNT-06 | Kalite sınıfı gerekçe yazılarak elle atanabilmelidir. | Düşük | 🟡 API'de var, panelde yok |
| FG-KNT-07 | Kayıt yapılmadan, girilen değerlerle tahmini hakediş anında gösterilmelidir. | Orta | ✅ |
| FG-KNT-08 | Kesilen fiş yazdırılabilmelidir. | Orta | ✅ |
| FG-KNT-09 | Hakedişi oluşmamış bir teslimat gerekçe yazılarak iptal edilebilmelidir. | Düşük | 🟡 |
| FG-KNT-10 | Brüt ve dara, kantar indikatöründen seri portla (Web Serial) okunabilmeli; yalnızca kararlı değer alınmalı, okunan değer kilitli yazılmalıdır. Tarayıcı desteklemiyorsa veya cihaz yoksa elle giriş mümkün olmalıdır. | Yüksek | ✅ |
| FG-KNT-11 | Ağırlığın kaynağı (kantar / elle) kaydedilmeli ve fişte gösterilmelidir. | Orta | ✅ |
| FG-KNT-12 | Çuval sayısı girilmeli; net = brüt − araç darası − çuval sayısı × çuval darası olmalıdır. Çuval darası ayarlanabilir olmalıdır. | Yüksek | ✅ |
| FG-KNT-13 | Ürün türü (kuru-kavlak / yaş) seçilmeli; kalite sınıfları, fiyatlar ve silolar türe göre ayrı olmalıdır. | Yüksek | ✅ |
| FG-KNT-14 | Tartım formu fare olmadan doldurulabilmeli (Enter ile sonraki alan; F9 / Ctrl+Enter ile kaydet ve fiş bas). | Orta | ✅ |
| FG-KNT-15 | Fiş 80 mm ve 58 mm termal rulo kâğıda sığmalı; müstahsil ve kantar görevlisi imza alanları bulunmalıdır. | Orta | ✅ |

### 3.3 Fiyat yönetimi (FYT)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-FYT-01 | Her kalite sınıfı için TL/kg taban fiyat, geçerlilik başlangıcıyla girilebilmelidir. | Yüksek | ✅ |
| FG-FYT-02 | **Teslimatın fiyatı, tartım anında geçerli ve o anda onaylı fiyattır; bu fiyat teslimata kopyalanır (kilitlenir).** | Yüksek | ✅ |
| FG-FYT-03 | Kilitli fiyat, sonradan fiyat değişse veya iptal edilse de değişmemelidir. | Yüksek | ✅ |
| FG-FYT-04 | Onaylı fiyat düzenlenemez; yeni fiyat girildiğinde eskisinin geçerliliği kapanmalıdır. | Yüksek | ✅ |
| FG-FYT-05 | Geçmiş tarihli fiyat girilememelidir. | Yüksek | ✅ |
| FG-FYT-06 | Aynı kalite sınıfı için onaylı fiyat aralıkları çakışmamalıdır. | Yüksek | ✅ |
| FG-FYT-07 | Şu an geçerli fiyatlar her sınıf için listelenebilmelidir. | Orta | ✅ |
| FG-FYT-08 | Hatalı fiyat iptal edilebilmelidir. | Düşük | 🟡 |

### 3.4 Avans, kesinti ve hakediş (HKD)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-HKD-01 | Net hakediş = (net kg × kilitli fiyat) − yasal kesintiler − avans mahsubu olarak hesaplanmalıdır. | Yüksek | ✅ |
| FG-HKD-02 | Kesintiler yüzde, kg başına veya sabit tutar olarak tanımlanabilmeli; uygulanan oran hakedişe kopyalanmalıdır. | Yüksek | ✅ |
| FG-HKD-03 | Üreticiye avans verilebilmeli; avans cari hesaba borç olarak yazılmalıdır. | Yüksek | ✅ |
| FG-HKD-04 | **Avans teslimat tutarından büyükse üretici eksi bakiyeye düşmeli; kalan avans sonraki teslimatlardan otomatik mahsup edilmelidir.** | Yüksek | ✅ |
| FG-HKD-05 | Avanslar en eskiden başlayarak (FIFO) mahsup edilmeli; hangi avansın hangi hakedişten düşüldüğü izlenebilmelidir. | Orta | ✅ |
| FG-HKD-06 | Hakediş oluştuktan sonra verilen avans, ödeme sırasında kalan tutardan mahsup edilmelidir. | Orta | ✅ |
| FG-HKD-07 | Kesintiler toplamı brüt tutarı aşmamalıdır. | Orta | ✅ |

### 3.5 Ödeme (ODM)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-ODM-01 | Hakediş kısmi veya tam ödenebilmelidir; durum Ödenmedi → Kısmi ödendi → Ödendi olarak izlenmelidir. | Yüksek | ✅ |
| FG-ODM-02 | Ödeme, hakedişin kalan tutarını ve üreticinin cari bakiyesini aşamamalıdır. | Yüksek | ✅ |
| FG-ODM-03 | Havale ödemelerinde üreticinin kayıtlı banka hesabı kullanılmalıdır. | Orta | ✅ |
| FG-ODM-04 | Üreticinin cari hesap ekstresi (yürüyen bakiyeyle), teslimatları ve açık avansları görüntülenebilmelidir. | Yüksek | ✅ |

### 3.6 Depo ve stok (DEP)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-DEP-01 | Her teslimat bir lot olarak kaliteye uygun siloya otomatik veya seçilerek yerleştirilmelidir. | Yüksek | ✅ |
| FG-DEP-02 | Bir siloya yalnızca kendi kalite sınıfı girebilmeli; farklı kaliteler karışmamalıdır. | Yüksek | ✅ |
| FG-DEP-03 | Silo kapasitesi aşılamamalıdır. | Orta | ✅ |
| FG-DEP-04 | Stok silo veya kalite sınıfına göre doluluk oranıyla görüntülenebilmelidir. | Orta | ✅ |
| FG-DEP-05 | Lotlar silolar arası transfer edilebilmelidir. | Düşük | 🟡 |
| FG-DEP-06 | Satış/sevk çıkışı kaydedilebilmelidir: alıcı firma, plaka, şoför, çıkış silosu, brüt / dara (kantar indikatörü desteğiyle), satış birim fiyatı, toplam tutar ve tahsilat durumu (nakit / havale / vadeli). Yalnızca Yönetici ve Depo sorumlusu yapabilir. | Yüksek | ✅ |
| FG-DEP-07 | Sevkiyatta silo stoğu net kg kadar, lotlardan ilk giren ilk çıkar sırasıyla düşmeli; silodaki stoktan fazla çıkış reddedilmelidir. Veritabanı hiçbir lotun eksi stoğa düşmesine izin vermemelidir. | Yüksek | ✅ |
| FG-DEP-08 | Sevkiyat için 80 / 58 mm termal veya A5 "Kantar Çıkış ve Sevk İrsaliyesi" basılabilmeli; alıcı, plaka, şoför, silo / ürün / sınıf, tarih-saat, kilolar, çıkan lotlar ve imza alanları bulunmalıdır. | Orta | ✅ |
| FG-DEP-09 | Alıcı firmalar müşteri kartı olarak saklanmalı (aynı ad Türkçe büyük/küçük harf farkıyla tekrar açılmamalı); vadeli satışların açık hesap toplamı görülebilmelidir. | Orta | ✅ |

### 3.8 Kimlik doğrulama ve yetkilendirme (GUV)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-GUV-01 | Kullanıcılar kullanıcı adı ve şifreyle giriş yapmalı; şifreler geri döndürülemez biçimde (BCrypt) saklanmalıdır. | Yüksek | ✅ |
| FG-GUV-02 | Giriş yapılmadan API kullanılamamalıdır (401). | Yüksek | ✅ |
| FG-GUV-03 | Tartım yalnızca kantar memuru ve yönetici; fiyat girişi yalnızca fiyat yöneticisi ve yönetici; üretici, avans ve ödeme yalnızca muhasebe ve yönetici tarafından yapılabilmelidir (403). | Yüksek | ✅ |
| FG-GUV-04 | Her kayıt, işlemi yapan kullanıcıyla ilişkilendirilmelidir. | Yüksek | ✅ |
| FG-GUV-05 | Hatalı girişte hangi kullanıcı adlarının var olduğu sızdırılmamalıdır. | Orta | ✅ |
| FG-GUV-06 | Sunum için her rolde tek tıkla demo girişi olmalı ve gerçek kullanımda kapatılabilmelidir. | Orta | ✅ |
| FG-GUV-07 | Yönetici panelden kullanıcı ekleyebilmeli, rolünü değiştirebilmeli, hesabı pasifleştirebilmeli ve şifresini sıfırlayabilmelidir. Rol değişikliği ve pasifleştirme açık oturumlarda bir sonraki istekte geçerli olmalıdır. | Orta | ✅ |
| FG-GUV-08 | Hesabı olmayan çalışan giriş ekranından hesap talebi gönderebilmeli; talep yönetici onaylayana kadar giriş yapamamalıdır. Yönetici rolü talep edilememeli, yönetici onaylarken rolü değiştirebilmelidir. | Orta | ✅ |
| FG-GUV-09 | Yöneticinin verdiği geçici şifre ilk girişte değiştirilmeli; her kullanıcı kendi şifresini değiştirebilmelidir. | Orta | ✅ |
| FG-GUV-10 | Yönetici kendi rolünü düşüremez, kendini pasifleştiremez; sistemde en az bir aktif yönetici kalmalıdır. Herkese açık demoda demo hesapları değiştirilemez. | Orta | ✅ |

### 3.7 Raporlama (RAP)
| No | Gereksinim | Öncelik | Durum |
|---|---|---|---|
| FG-RAP-01 | Günlük alınan toplam kg, teslimat ve üretici sayısı raporlanmalıdır. | Yüksek | ✅ |
| FG-RAP-02 | Günlük ödenen toplam tutar ve verilen avans raporlanmalıdır. | Yüksek | ✅ |
| FG-RAP-03 | Kg ağırlıklı ortalama randıman ve ortalama fiyat raporlanmalıdır. | Orta | ✅ |
| FG-RAP-04 | Tüm üreticilerin bakiye, açık avans ve ödenebilir tutarları listelenebilmelidir. | Orta | ✅ |
| FG-RAP-05 | Raporlar PDF/Excel olarak dışa aktarılabilmelidir. | Orta | ✅ Döküm: yazdır / PDF; gün sonu: Excel (CSV) |
| FG-RAP-06 | Seçilen üretici için, isteğe bağlı tarih aralığında, teslimatları (tarih, fiş no, ürün türü, net kg, tutar), aldığı avansları, yapılan ödemeleri, yürüyen bakiyeli hareketleri ve net bakiyeyi gösteren yazdırılabilir (A4 / PDF) cari hesap dökümü alınabilmelidir. | Yüksek | ✅ |
| FG-RAP-07 | Seçilen gün için teslimatları, nakit / havale / çek ödemeleri ve avansları, kasadan çıkan nakdi, kalan hakedişi, ürün türü ve silo bazlı giren stoğu özetleyen gün sonu raporu Excel'de açılan CSV olarak indirilebilmelidir. | Yüksek | ✅ |

---

## 4. Fonksiyonel olmayan gereksinimler

| No | Kategori | Gereksinim | Doğrulama | Durum |
|---|---|---|---|---|
| FOG-01 | Doğruluk | Para hesaplarında kayan nokta (`float`/`double`) kullanılmamalı; `NUMERIC`/`BigDecimal`, kuruşa yuvarlama (HALF_UP) kullanılmalıdır. | Kod incelemesi, JUnit | ✅ |
| FOG-02 | Veri bütünlüğü | İş kuralları uygulama dışında (doğrudan SQL ile) da korunmalıdır: CHECK, UNIQUE, EXCLUDE kısıtları ve trigger'lar. | `tests/test_postgres.py` | ✅ |
| FOG-03 | Denetlenebilirlik | Cari hesap kayıtları silinemez ve güncellenemez; düzeltme ters kayıtla yapılır. | `tests/test_postgres.py` | ✅ |
| FOG-04 | Tutarlılık | Tartım → kalite → fiyat kilidi → hakediş → stok tek transaction'da yapılmalı (hepsi ya da hiçbiri). | Kod incelemesi | ✅ |
| FOG-05 | Eşzamanlılık | Aynı üreticiye eşzamanlı işlemler avansı iki kez mahsup edememeli; belge numaraları çakışmamalıdır (satır kilidi). | Kod incelemesi | ✅ (PostgreSQL) |
| FOG-06 | Kullanılabilirlik | Arayüz ve tüm hata mesajları Türkçe olmalı; doğrulama hataları ilgili alanın altında gösterilmelidir. | Arayüz testi | ✅ |
| FOG-07 | Çevrimdışı çalışma | Panel ve API dokümantasyonu internet bağlantısı olmadan çalışmalıdır. | `test_cevrimdisi_calisir_dis_kaynak_yok` | ✅ |
| FOG-08 | Taşınabilirlik | REST sözleşmesi uygulama dilinden bağımsız olmalıdır: aynı testler Java ve Python sürümlerinde geçmelidir. | `pytest --java` ve `pytest --pg` | ✅ |
| FOG-09 | Dokümantasyon | API, OpenAPI standardında otomatik belgelenmelidir (Swagger, ReDoc). | `/docs`, `/redoc` | ✅ |
| FOG-10 | Test edilebilirlik | İş kuralları HTTP'den bağımsız bir katmanda olmalı ve otomatik testlerle doğrulanmalıdır. | 27 sözleşme testi + 6 JUnit testi | ✅ |
| FOG-11 | Kurulum | Sistem tek komutla (`baslat.bat`) derlenip çalıştırılabilmelidir. | Elle deneme | ✅ |
| FOG-12 | Güvenlik | Kullanıcılar kimlik doğrulamasıyla girmeli; işlemler role göre yetkilendirilmelidir (Spring Security, JWT). | `tests/test_guvenlik.py` | ✅ |
| FOG-13 | Gizlilik | Veritabanı şifreleri kaynak koda veya Git'e girmemelidir (`.env`). | `.gitignore` | ✅ |

---

## 5. İş kuralları

| No | Kural | Nerede uygulanıyor |
|---|---|---|
| İK-01 | Teslimat fiyatı = tartım anında geçerli (`valid_from ≤ t < valid_to`) **ve** o anda onaylı (`approved_at ≤ t`) fiyat. | `rules.find_effective_price` |
| İK-02 | İptal edilen fiyat, iptal anından önceki tartımlar için geçerli kalır (`revoked_at > t`). | `rules.find_effective_price` |
| İK-03 | Kalite sınıfı, öncelik sırasıyla tüm eşikleri sağlayan ilk sınıftır; son sınıf eşiksizdir. | `rules.determine_grade` |
| İK-04 | Avans mahsubu, kesinti sonrası tutarla sınırlıdır → hakediş net tutarı hiçbir zaman eksi olmaz; eksi bakiye cari hesapta tutulur. | `rules.create_settlement`, CHECK `net_payable >= 0` |
| İK-05 | Ödenebilir tutar = min(ödenmemiş hakedişler, cari bakiye). | `rules.record_payment` |
| İK-06 | Hakedişin brüt tutarı ve kesintileri değiştirilemez; yalnızca avans mahsubu artırılabilir (net ödenecek aynı miktarda azalır). | Trigger `trg_settlements_freeze` |
| İK-07 | Fiş/lot numarası yıl bazlıdır ve boşluksuz artar: `KF-2026-000001`, `LOT-2026-000001`. | `rules.next_document_no` |
| İK-08 | Gün sınırı Türkiye saatine göredir (00:00–24:00, UTC+3). | `core/timeutil.day_bounds_utc` |

---

## 6. Kullanım durumları

Kullanım durumu diyagramı: [UML.md § 1](UML.md#1-kullanım-durumu-diyagramı)

| No | Kullanım durumu | Birincil aktör | İlgili gereksinimler |
|---|---|---|---|
| KD-01 | Üretici kaydet | Muhasebe | FG-URT-01…07 |
| KD-02 | Günlük fiyat belirle | Fiyat yöneticisi | FG-FYT-01…07 |
| KD-03 | **Fıstık alımı (tartım ve fiş kesme)** | Kantar memuru | FG-KNT-*, FG-FYT-02, FG-HKD-01…05, FG-DEP-01…03 |
| KD-04 | Avans ver | Muhasebe | FG-HKD-03…05 |
| KD-05 | Hakediş ödemesi yap | Muhasebe | FG-ODM-01…03, FG-HKD-06 |
| KD-06 | Cari hesap ekstresi görüntüle | Muhasebe | FG-ODM-04 |
| KD-07 | Stok durumunu izle | Depo sorumlusu | FG-DEP-04 |
| KD-08 | Günlük raporu görüntüle | Yönetici | FG-RAP-01…04 |

### KD-03 Fıstık alımı (tartım ve fiş kesme)
| Alan | Açıklama |
|---|---|
| **Aktör** | Kantar memuru |
| **Ön koşul** | Üretici kayıtlı ve aktif; ilgili kalite sınıfı için onaylı günlük fiyat var; uygun silo var. |
| **Tetikleyici** | Üretici fıstık yüklü araçla alım merkezine gelir. |
| **Ana akış** | 1. Memur üreticiyi seçer, plakayı girer.<br>2. Brüt ve dara ağırlığını girer; sistem net kg'ı gösterir.<br>3. Kalite oranlarını girer; sistem sınıfı, kilitlenecek fiyatı, kesintileri, avans mahsubunu ve tahmini net ödemeyi anında gösterir.<br>4. Memur "Fişi Kes"e basar.<br>5. Sistem tek işlemde: fiş ve lot numarası üretir, fiyatı kilitler, hakedişi oluşturur, açık avansı mahsup eder, cari hesaba yazar, lotu siloya yerleştirir.<br>6. Sistem yazdırılabilir kantar fişini gösterir. |
| **Alternatif akışlar** | **3a.** O sınıf için onaylı fiyat yok → uyarı gösterilir, fiş kesilemez (400).<br>**3b.** Üreticinin açık avansı var → mahsup tutarı önizlemede gösterilir.<br>**4a.** Geçersiz değer (negatif kg, %100'ü aşan oran, brüte eşit dara, geçersiz plaka) → alan altında Türkçe hata (422).<br>**5a.** Uygun kalitede ve kapasitede silo yok → işlem tamamen geri alınır (400). |
| **Son koşul** | Teslimat, hakediş, cari hesap hareketleri ve stok kaydı oluşmuştur; fiyat kilitlidir. |
| **İş kuralları** | İK-01, İK-03, İK-04, İK-07 |

### KD-05 Hakediş ödemesi yap
| Alan | Açıklama |
|---|---|
| **Aktör** | Muhasebe |
| **Ön koşul** | Hakedişin kalan tutarı > 0. |
| **Ana akış** | 1. Muhasebe üreticiyi seçer, ekstrede hakedişi bulur ve "Ödeme Yap"a basar.<br>2. Tutarı (boşsa kalanın tamamı), yöntemi ve dekont numarasını girer.<br>3. Sistem önce hakedişten sonra verilmiş açık avansları kalan tutardan mahsup eder.<br>4. Sistem ödemeyi kaydeder, hakediş durumunu ve cari hesabı günceller. |
| **Alternatif akışlar** | **3a.** Kalan tutarın tamamı avansa mahsup edildi → ödeme kaydı oluşmaz (200).<br>**4a.** Tutar kalan tutarı veya cari bakiyeyi aşıyor → 400.<br>**4b.** Havale seçildi ama banka hesabı yok → 400. |
| **Son koşul** | Hakediş durumu Kısmi ödendi / Ödendi; cari bakiye azalmıştır. |
| **İş kuralları** | İK-05, İK-06 |

### KD-04 Avans ver
| Alan | Açıklama |
|---|---|
| **Aktör** | Muhasebe |
| **Ana akış** | 1. Üreticiyi seçer, "Avans Ver"e basar. 2. Tutar ve yöntemi girer. 3. Sistem avansı kaydeder ve cari hesaba borç yazar. |
| **Son koşul** | Açık avans artar; bakiye eksiye düşebilir. Avans sonraki hakedişlerden FIFO ile mahsup edilir. |

---

## 7. İzlenebilirlik matrisi (gereksinim → kod → test)

> Kod sütunu Python prototipindeki adları gösterir; Java karşılıkları için README'deki *Python prototipinden Java'ya* tablosuna bakın (ör. `rules.find_effective_price` → `Kurallar.gecerliFiyat`, `rules.create_settlement` → `Kurallar.hakedisOlustur`). Testler iki sürümde de aynıdır.

| Gereksinim | Uygulama | Test |
|---|---|---|
| FG-URT-02/03/04 | `schemas/uretici.py`, `rules.validate_tc_kimlik`, `validate_iban`, `normalize_phone` | `test_uretici_dogrulama_hatalari_422`, `test_uretici_kaydi_normalize_eder` |
| FG-URT-05 | `uretici_service.create_producer`, UNIQUE kısıtı | `test_ayni_tc_ikinci_kayit_409` |
| FG-KNT-02, 04 | `schemas/tartim.py` | `test_tartim_validasyonlari_422` |
| FG-KNT-03 | `rules.next_document_no`, UNIQUE `ticket_no`/`lot_no` | `test_tartim_fiyat_kilidi_hakedis_ve_stok` |
| FG-KNT-07 | `tartim_service.preview_delivery` | `test_onizleme_kayit_yapmadan_hesaplar` |
| FG-FYT-02/03 | `rules.find_effective_price`, `record_quality_inspection` | `test_tartim_fiyat_kilidi_hakedis_ve_stok`, `test_teslimatin_kilitli_fiyati_ve_tartimi_degistirilemez` |
| FG-FYT-04/05 | `rules.approve_price`, trigger `trg_daily_prices_guard` | `test_fiyat_hatalari`, `test_onayli_fiyat_degistirilemez_silinemez` |
| FG-FYT-06 | EXCLUDE `ex_daily_prices_no_overlap` | `test_ayni_sinif_icin_cakisan_onayli_fiyat_reddedilir` |
| FG-HKD-01/02/07 | `rules.create_settlement`, CHECK `net_formula` | `test_tartim_fiyat_kilidi_hakedis_ve_stok`, `test_hakedis_formulu_veritabaninda_denetlenir` |
| FG-HKD-04/05 | `rules._plan_advance_offsets`, `advance_offsets` | `test_avans_teslimattan_buyukse_eksi_bakiye_ve_mahsup` |
| FG-HKD-06 | `rules.offset_advances_into_settlement` | `test_hakedisten_sonra_verilen_avans_odemede_mahsup_edilir` |
| FG-ODM-01/02 | `rules.record_payment`, `finans_service.pay_settlement` | `test_parcali_ve_tam_odeme` |
| FG-ODM-04 | `finans_service.producer_statement` | `test_avans_teslimattan_buyukse_eksi_bakiye_ve_mahsup` |
| FG-DEP-01/02/03 | `rules.check_silo_accepts`, trigger `trg_stock_grade_check` | `test_tartim_fiyat_kilidi_hakedis_ve_stok`, `test_farkli_kalite_yanlis_siloya_giremez` |
| FG-DEP-04, FG-RAP-01…03 | `rapor_service.stock_status`, `daily_summary` | `test_stok_durumu_ve_gunluk_ozet` |
| FG-RAP-04 | `rapor_service.producer_balances`, view `v_producer_balance` | `test_tartim_listesi_ve_uretici_bakiyeleri`, `test_rapor_viewlari_api_ile_ayni_sonucu_verir` |
| FOG-03 | trigger `trg_ledger_append_only` | `test_cari_hesap_defteri_degistirilemez_ve_silinemez` |
| FOG-07 | `app/static/vendor/`, `app/web.py` | `test_cevrimdisi_calisir_dis_kaynak_yok` |

---

## 8. Gelecek sürüm gereksinimleri (GDG)

| No | Gereksinim |
|---|---|
| GDG-01 | Müşteri cari hesabı: vadeli satışların tahsilatı, müşteri ekstresi ve resmî e-irsaliye entegrasyonu |
| GDG-02 | Silolar arası transfer ve fiyat iptali için API ve arayüz (FG-DEP-05, FG-FYT-08) |
| GDG-03 | Sezonluk raporlar ve gerçek .xlsx (çok sayfalı) dışa aktarım |
| GDG-04 | TCP/IP indikatör desteği ve indikatör verisini sunucuya imzalı gönderen kantar servisi (tarayıcıdan bağımsız kanıt) |
| GDG-05 | E-postayla şifre sıfırlama bağlantısı ve art arda hatalı girişte geçici hesap kilidi |
| GDG-06 | Alembic ile veritabanı sürüm yönetimi |
