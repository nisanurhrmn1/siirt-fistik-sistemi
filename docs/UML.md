# UML Diyagramları

**Proje:** Siirt Fıstığı Alım ve Üretici Ödeme Sistemi · **Sürüm:** 2.0 (Java / Spring Boot)

Diyagramlar [Mermaid](https://mermaid.js.org) ile yazılmıştır; GitHub'da doğrudan çizilir.
Gereksinimler: [GEREKSINIMLER.md](GEREKSINIMLER.md) · Veritabanı ER diyagramı: [ER.md](ER.md)

| # | Diyagram | UML türü | Gösterdiği |
|---|---|---|---|
| 1 | [Kullanım durumu](#1-kullanım-durumu-diyagramı) | Davranışsal | Aktörler ve sistemle yaptıkları işler |
| 2 | [Sınıf (alan modeli)](#2-sınıf-diyagramı--alan-modeli) | Yapısal | Varlıklar, nitelikler ve ilişkiler |
| 3 | [Sınıf (servis katmanı)](#3-sınıf-diyagramı--katmanlar-ve-servisler) | Yapısal | Katmanlar ve işlemler |
| 4 | [Sıralama: fıstık alımı](#4-sıralama-diyagramı--fıstık-alımı-kd-03) | Etkileşim | Tartımdan fişe kadar nesnelerin mesajlaşması |
| 5 | [Sıralama: hakediş ödemesi](#5-sıralama-diyagramı--hakediş-ödemesi-kd-05) | Etkileşim | Avans mahsubu ve parçalı ödeme |
| 6 | [Etkinlik: hakediş hesabı](#6-etkinlik-diyagramı--hakediş-hesabı-ve-avans-mahsubu) | Davranışsal | Net hakediş ve FIFO avans algoritması |
| 7 | [Durum diyagramları](#7-durum-diyagramları) | Davranışsal | Teslimat, hakediş, fiyat ve avans yaşam döngüleri |
| 8 | [Bileşen ve dağıtım](#8-bileşen-ve-dağıtım-diyagramı) | Yapısal | Çalışma zamanı mimarisi |

---

## 1. Kullanım durumu diyagramı

```mermaid
flowchart LR
    KM(["👤 Kantar Memuru"])
    FY(["👤 Fiyat Yöneticisi"])
    MU(["👤 Muhasebe"])
    DS(["👤 Depo Sorumlusu"])
    YO(["👤 Yönetici"])

    UC3(["KD-03 Fıstık alımı / fiş kes"])
    UC2(["KD-02 Günlük fiyat belirle"])
    UC1(["KD-01 Üretici kaydet"])
    UC4(["KD-04 Avans ver"])
    UC5(["KD-05 Hakediş ödemesi yap"])
    UC6(["KD-06 Cari ekstre görüntüle"])
    UC7(["KD-07 Stok durumunu izle"])
    UC8(["KD-08 Günlük raporu görüntüle"])

    UC3a(["Kalite sınıfını belirle"])
    UC3b(["Fiyatı kilitle"])
    UC3c(["Hakediş hesapla"])
    UC3d(["Lotu siloya yerleştir"])
    UC3e(["Tahmini hakedişi göster"])
    UC5a(["Açık avansı mahsup et"])

    KM --- UC3
    FY --- UC2
    MU --- UC1
    MU --- UC4
    MU --- UC5
    MU --- UC6
    DS --- UC7
    YO --- UC7
    YO --- UC8

    UC3 -. "«include»" .-> UC3a
    UC3 -. "«include»" .-> UC3b
    UC3 -. "«include»" .-> UC3c
    UC3 -. "«include»" .-> UC3d
    UC3e -. "«extend»" .-> UC3
    UC3c -. "«include»" .-> UC5a
    UC5 -. "«include»" .-> UC5a

    classDef aktor fill:#f4f8ea,stroke:#627d23,color:#34411b
    classDef ana fill:#ffffff,stroke:#627d23,stroke-width:2px
    classDef alt fill:#fbf7f1,stroke:#b98b52
    class KM,FY,MU,DS,YO aktor
    class UC1,UC2,UC3,UC4,UC5,UC6,UC7,UC8 ana
    class UC3a,UC3b,UC3c,UC3d,UC3e,UC5a alt
```

> Sol sütun aktörler (yeşil), orta sütun sistemin kullanım durumları (kalın çerçeve), sağ sütun bunların içerdiği alt işlevlerdir (bej). Tüm kullanım durumları *Siirt Fıstığı Alım ve Üretici Ödeme Sistemi* sınırı içindedir.
> **Yönetici** tüm aktörlerin işlerini yapabilir (genelleme); okları sade tutmak için yalnızca raporlama ilişkileri çizildi.

---

## 2. Sınıf diyagramı — alan modeli

Kalıcı varlıklar (`java-backend/.../alan/*.java`, JPA). Tüm para alanları `BigDecimal` (`NUMERIC`), zamanlar `OffsetDateTime` (`timestamptz`).

```mermaid
classDiagram
    direction LR

    class Producer {
        +int id
        +ProducerType producerType
        +str nationalId «TC, unique»
        +str taxNumber «VKN, unique»
        +str fullName
        +str phone
        +str city
        +bool isActive
    }
    class ProducerBankAccount {
        +int id
        +str bankName
        +str iban «TR + 24 hane»
        +bool isDefault
    }
    class QualityGrade {
        +int id
        +str code «A, B, ISK»
        +int priority
        +Decimal minSplitRatio
        +Decimal minYieldRatio
        +Decimal maxDefectRatio
        +Decimal maxMoistureRatio
    }
    class DailyPrice {
        +int id
        +Decimal pricePerKg
        +datetime validFrom
        +datetime validTo
        +PriceStatus status
        +datetime approvedAt
        +datetime revokedAt
    }
    class WeighTicket {
        +int id
        +str ticketNo «KF-2026-000001»
        +str vehiclePlate
        +Decimal grossKg
        +Decimal tareKg
        +Decimal netKg «hesaplanan»
        +datetime grossWeighedAt «fiyat referans anı»
        +Decimal unitPrice «kilitli fiyat»
        +TicketStatus status
    }
    class QualityInspection {
        +int id
        +Decimal splitRatio
        +Decimal yieldRatio
        +Decimal emptyRatio
        +Decimal rottenRatio
        +Decimal moistureRatio
        +bool isManualOverride
    }
    class Settlement {
        +int id
        +str settlementNo
        +Decimal grossAmount
        +Decimal deductionsTotal
        +Decimal advanceOffsetTotal
        +Decimal netPayable «≥ 0»
        +Decimal paidAmount
        +SettlementStatus status
    }
    class DeductionType {
        +int id
        +str code «STOPAJ, BORSA…»
        +DeductionCalcMethod calcMethod
        +Decimal rate
    }
    class SettlementDeduction {
        +Decimal rate «snapshot»
        +Decimal baseAmount
        +Decimal amount
    }
    class Advance {
        +int id
        +str advanceNo
        +Decimal amount
        +Decimal offsetAmount
        +AdvanceStatus status
    }
    class AdvanceOffset {
        +Decimal amount
    }
    class Payment {
        +int id
        +str paymentNo
        +Decimal amount
        +PaymentMethod method
        +str referenceNo
    }
    class PaymentAllocation {
        +Decimal amount
    }
    class ProducerLedgerEntry {
        +int id
        +LedgerEntryType entryType
        +Decimal amount «± işaretli»
        +datetime entryAt
    }
    class Silo {
        +int id
        +str code
        +Decimal capacityKg
    }
    class Lot {
        +int id
        +str lotNo «LOT-2026-000001»
        +Decimal initialKg
    }
    class StockMovement {
        +int id
        +StockMovementType movementType
        +Decimal quantityKg «± işaretli»
    }

    Producer "1" *-- "0..*" ProducerBankAccount : sahip
    Producer "1" --> "0..*" WeighTicket : teslim eder
    Producer "1" --> "0..*" Advance : alır
    Producer "1" --> "0..*" Payment : alır
    Producer "1" *-- "0..*" ProducerLedgerEntry : cari hesap

    QualityGrade "1" --> "0..*" DailyPrice : fiyatlanır
    DailyPrice "1" <-- "0..*" WeighTicket : kilitli fiyat
    WeighTicket "1" *-- "0..1" QualityInspection : analiz
    QualityInspection "0..*" --> "1" QualityGrade : sınıfı
    WeighTicket "1" -- "0..1" Settlement : hakediş
    WeighTicket "1" -- "0..1" Lot : stok partisi

    Settlement "1" *-- "0..*" SettlementDeduction
    SettlementDeduction "0..*" --> "1" DeductionType
    Settlement "1" -- "0..*" AdvanceOffset
    Advance "1" -- "0..*" AdvanceOffset : FIFO mahsup
    Payment "1" *-- "1..*" PaymentAllocation
    PaymentAllocation "0..*" --> "1" Settlement

    Lot "0..*" --> "1" QualityGrade
    Silo "0..*" --> "0..1" QualityGrade : adanmış sınıf
    Lot "1" -- "0..*" StockMovement
    Silo "1" -- "0..*" StockMovement
```

**Notlar**
- `Settlement` ↔ `Advance` arasındaki çoka-çok ilişki, mahsup tutarını taşıyan `AdvanceOffset` ilişki sınıfıyla kurulur. `Payment` ↔ `Settlement` arasındaki ilişki de aynı şekilde `PaymentAllocation` ile kurulur.
- `ProducerLedgerEntry` yalnızca eklenebilir. Üretici bakiyesi = Σ `amount`.
- Kullanıcı (`User`) ve belge numarası sayacı (`DocumentSequence`) diyagramı sade tutmak için gösterilmedi.

---

## 3. Sınıf diyagramı — katmanlar ve servisler

```mermaid
classDiagram
    direction TB

    class Tartimlar {
        <<@RestController · api/Kontrolculer>>
        +kaydet(TartimGirisi, kullaniciId) TartimYaniti
        +onizle(OnizlemeGirisi) TartimOnizleme
        +listele(tarih, ureticiId) List~TartimOzeti~
    }
    class Finans {
        <<@RestController · api/Kontrolculer>>
        +avansVer(AvansGirisi, kullaniciId) AvansYaniti
        +odemeYap(id, OdemeGirisi, kullaniciId) OdemeSonucu
    }
    class TartimGirisi {
        <<record · api/Istekler>>
        +Long ureticiId
        +String plaka «@Gecerli PLAKA»
        +BigDecimal brutKg «@DecimalMin»
        +BigDecimal daraKg
        +KaliteAnalizi kalite «@Valid»
        +modelHatalari() dara < brüt
    }
    class TartimServisi {
        <<@Service @Transactional>>
        +kaydet(giris, kullaniciId) TartimYaniti
        +onizle(giris) TartimOnizleme
        -siloSec(lot) Silo
    }
    class FinansServisi {
        <<@Service @Transactional>>
        +avansVer(giris, kullaniciId) AvansYaniti
        +odemeYap(id, giris, kullaniciId) OdemeIslemi
        +ekstre(id, baslangic, bitis) UreticiEkstre
    }
    class Kurallar {
        <<@Component · kural/Kurallar>>
        +gecerliFiyat(sinifId, an) DailyPrice
        +sinifBelirle(oranlar) QualityGrade
        +tartimOlustur(...) WeighTicket
        +kaliteAnaliziKaydet(...) QualityInspection
        +hakedisOlustur(tartim, ...) Settlement
        +hakediseSonradanAvansMahsupEt(s) BigDecimal
        +odemeKaydet(...) Payment
        +siloKabulEderMi(silo, lot, kg)
        +sonrakiBelgeNo(tur, an) String
        -avansMahsupPlani(ureticiId, limit) List
    }
    class EntityManager {
        <<JPA / Hibernate>>
        +find(..., PESSIMISTIC_WRITE)
        +persist()
        +flush()
    }

    Tartimlar ..> TartimGirisi : @Valid doğrular
    Tartimlar --> TartimServisi
    Finans --> FinansServisi
    TartimServisi --> Kurallar
    TartimServisi --> FinansServisi : hakediş yanıtı
    FinansServisi --> Kurallar
    Kurallar --> EntityManager
```

**Bağımlılık kuralı:** Oklar yalnızca aşağı yönlüdür (API → servis → alan → veri). İş kuralları (`Kurallar`) HTTP'yi bilmez; bu sayede doğrudan test edilebilir. Bu ayrım sayesinde Python prototipinden Java'ya geçişte mimari değişmeden kalmıştır.

---

## 4. Sıralama diyagramı — Fıstık alımı (KD-03)

```mermaid
sequenceDiagram
    autonumber
    actor KM as Kantar Memuru
    participant UI as Web Paneli
    participant API as API (tartimlar)
    participant SV as TartimServisi
    participant R as Kurallar
    participant DB as Veritabanı

    KM->>UI: Üretici, plaka, brüt/dara, kalite oranları
    loop Her alan değiştiğinde (300 ms gecikmeli)
        UI->>API: POST /tartimlar/onizleme
        API->>R: sinifBelirle, gecerliFiyat(şimdi)
        R->>DB: SELECT (yazma yok)
        API-->>UI: sınıf, fiyat, kesintiler, avans mahsubu, tahmini net
    end
    KM->>UI: Fişi Kes
    UI->>API: POST /tartimlar
    API->>API: Bean Validation (aksi halde 422)
    API->>SV: kaydet(giriş, kullanıcı)
    activate SV
    SV->>R: tartimOlustur(an = şimdi)
    R->>DB: document_sequences FOR UPDATE → KF-2026-00000N
    SV->>R: daraKaydet
    SV->>R: kaliteAnaliziKaydet
    R->>R: sinifBelirle(oranlar)
    R->>DB: gecerliFiyat(sınıf, tartım anı)
    alt Onaylı fiyat yok
        R-->>API: IsKuraliHatasi
        API-->>UI: 400 hata mesajı (işlem geri alınır)
    else Fiyat bulundu
        R->>DB: weigh_tickets.unit_price = fiyat (KİLİT), lots INSERT
        SV->>R: hakedisOlustur
        R->>DB: producers FOR UPDATE
        R->>R: brüt = net × fiyat, kesintiler
        R->>DB: açık avanslar (FIFO) FOR UPDATE
        R->>DB: settlements, advance_offsets, ledger INSERT
        SV->>R: siloKabulEderMi + lotuSiloyaAl
        R->>DB: stock_movements INSERT
        SV->>DB: COMMIT
        deactivate SV
        API-->>UI: 201 fiş, lot, kilitli fiyat, hakediş, silo
        UI-->>KM: Yazdırılabilir kantar fişi
    end
```

---

## 5. Sıralama diyagramı — Hakediş ödemesi (KD-05)

```mermaid
sequenceDiagram
    autonumber
    actor MU as Muhasebe
    participant UI as Web Paneli
    participant API as API (hakedisler)
    participant FS as FinansServisi
    participant R as Kurallar
    participant DB as Veritabanı

    MU->>UI: Üretici seç → Ödeme Yap (tutar, yöntem, dekont)
    UI->>API: POST /hakedisler/{id}/odeme-yap
    API->>FS: odemeYap
    FS->>DB: hakediş getir (yoksa 404)
    FS->>R: ureticiKilitle
    R->>DB: producers FOR UPDATE
    alt Kalan tutar 0
        FS-->>UI: 400 ödenecek tutar kalmadı
    end
    FS->>R: hakediseSonradanAvansMahsupEt
    R->>DB: hakedişten sonra verilen açık avanslar (FIFO)
    R->>DB: advance_offsets INSERT, net_payable azalır
    alt Kalanın tamamı avansa mahsup edildi
        FS->>DB: COMMIT
        API-->>UI: 200 ödeme gerekmedi
    else Kalan > 0
        FS->>R: odemeKaydet(tutar, hakedisId)
        R->>R: tutar ≤ min(kalan, cari bakiye)?
        alt Aşıyor
            R-->>UI: 400 tutar aşamaz
        else Uygun
            R->>DB: payments, payment_allocations, ledger(−tutar)
            R->>R: durum = Kısmi ödendi / Ödendi
            FS->>DB: COMMIT
            API-->>UI: 201 ödeme, hakediş, güncel bakiye
        end
    end
```

---

## 6. Etkinlik diyagramı — Hakediş hesabı ve avans mahsubu

`Kurallar.hakedisOlustur` algoritması.

```mermaid
flowchart TD
    A([Başla: teslimat GRADED]) --> B[Net kg ve kilitli fiyatı al]
    B --> C["Brüt = ROUND(net kg × fiyat, 2)"]
    C --> D{Aktif kesinti türü kaldı mı?}
    D -- Evet --> E{Hesaplama yöntemi}
    E -- PERCENT --> E1[brüt × oran / 100]
    E -- PER_KG --> E2[net kg × oran]
    E -- FIXED --> E3[sabit tutar]
    E1 & E2 & E3 --> F[Kesintiyi oranıyla birlikte kaydet]
    F --> D
    D -- Hayır --> G{Kesinti toplamı > brüt?}
    G -- Evet --> X([Hata: 400, işlem geri alınır])
    G -- Hayır --> H["Kullanılabilir = brüt − kesintiler"]
    H --> I{"Açık avans var mı ve kullanılabilir > 0?<br/>(en eski avans önce)"}
    I -- Evet --> J["Mahsup = min(avansın kalanı, kullanılabilir)"]
    J --> K["Avansın mahsup edilen tutarı += mahsup<br/>kullanılabilir −= mahsup"]
    K --> I
    I -- Hayır --> L["Net ödenecek = kullanılabilir (≥ 0)"]
    L --> M{Net ödenecek = 0?}
    M -- Evet --> N[Durum: Avansa mahsup]
    M -- Hayır --> O[Durum: Ödenmedi]
    N & O --> P["Cari hesap: + brüt, − her kesinti<br/>(avans verilirken zaten − yazıldı)"]
    P --> Q([Bitir: teslimat SETTLED])
```

> Kapanmayan avans açık kalır; cari bakiye eksi görünür ve bir sonraki hakedişte aynı döngü kalan avansı düşer.

---

## 7. Durum diyagramları

### 7.1 Teslimat (`WeighTicket.status`)
```mermaid
stateDiagram-v2
    [*] --> GROSS_WEIGHED : brüt tartım
    GROSS_WEIGHED --> NET_WEIGHED : dara tartımı
    NET_WEIGHED --> GRADED : kalite analizi + fiyat kilidi + lot
    GRADED --> SETTLED : hakediş oluşturuldu
    GROSS_WEIGHED --> CANCELLED : iptal (gerekçe zorunlu)
    NET_WEIGHED --> CANCELLED : iptal
    GRADED --> CANCELLED : iptal
    SETTLED --> [*]
    CANCELLED --> [*]
    note right of GRADED
        unit_price bu andan itibaren
        değiştirilemez (trigger)
    end note
```
> Panelde "Fişi Kes", ilk dört durumu tek transaction'da geçer. Ara durumlar kalite analizinin sonradan yapıldığı akışlar için alan katmanında mevcuttur.

### 7.2 Hakediş (`Settlement.status`)
```mermaid
stateDiagram-v2
    [*] --> UNPAID : net ödenecek > 0
    [*] --> OFFSET_ONLY : tamamı avansa mahsup
    UNPAID --> PARTIALLY_PAID : kısmi ödeme
    UNPAID --> PAID : tam ödeme
    PARTIALLY_PAID --> PAID : kalan ödendi
    UNPAID --> OFFSET_ONLY : sonradan verilen avans kalanı kapattı
    PARTIALLY_PAID --> PAID : sonradan verilen avans kalanı kapattı
    PAID --> [*]
    OFFSET_ONLY --> [*]
```

### 7.3 Günlük fiyat (`DailyPrice.status`)
```mermaid
stateDiagram-v2
    [*] --> DRAFT : fiyat girildi
    DRAFT --> APPROVED : onay (valid_from ≥ şimdi)
    state APPROVED {
        [*] --> Acik : valid_to boş
        Acik --> Kapali : aynı sınıfa yeni fiyat onaylandı
    }
    APPROVED --> REVOKED : iptal
    REVOKED --> [*]
    note right of APPROVED
        Tutar, sınıf ve başlangıç değiştirilemez.
        Silinemez (trigger).
    end note
```

### 7.4 Avans (`Advance.status`)
```mermaid
stateDiagram-v2
    [*] --> OPEN : avans verildi
    OPEN --> PARTIALLY_OFFSET : kısmen mahsup
    OPEN --> CLOSED : tamamı mahsup
    PARTIALLY_OFFSET --> CLOSED : kalan mahsup
    CLOSED --> [*]
```

---

## 8. Bileşen ve dağıtım diyagramı

```mermaid
flowchart LR
    subgraph PC["💻 Kullanıcı bilgisayarı · Tarayıcı"]
        UI["Web Paneli<br/>index.html + Alpine.js + Tailwind"]
        SW["Swagger UI / ReDoc"]
    end

    subgraph SRV["🖥️ Uygulama sunucusu · Java 21 / Spring Boot 3 (siirt-fistik.jar)"]
        direction TB
        WEB["static + web<br/>panel, /docs (springdoc), /redoc"]
        API["api<br/>@RestController'lar"]
        SCH["api/Istekler<br/>Bean Validation"]
        SVC["servis<br/>@Transactional iş akışları"]
        DOM["kural/Kurallar<br/>iş kuralları"]
        ORM["alan<br/>JPA varlıkları (Hibernate)"]
        VEN[("static/vendor<br/>çevrimdışı kütüphaneler")]
        WEB --- VEN
        API --> SCH --> SVC --> DOM --> ORM
    end

    subgraph DBS["🗄️ Veritabanı sunucusu"]
        PG[("PostgreSQL 18<br/>siirt_fistik<br/>Flyway: tablolar, trigger'lar, view'lar")]
    end

    UI -- "HTTP GET /" --> WEB
    UI -- "HTTP JSON /api/v1" --> API
    SW -- "/openapi.json" --> API
    ORM -- "JDBC (pgjdbc) · TCP 5432" --> PG
```

| Düğüm | Yazılım | Not |
|---|---|---|
| İstemci | Güncel bir tarayıcı (Chrome, Edge, Firefox) | Kurulum gerekmez; internet bağlantısı gerekmez |
| Uygulama sunucusu | Java 21, Spring Boot 3.3 (gömülü Tomcat) | `baslat.bat` veya `java -jar siirt-fistik.jar` |
| Veritabanı | PostgreSQL 18 | Bağlantı `.env` dosyasındaki `DATABASE_URL` ile; şema Flyway göçleriyle kurulur |
