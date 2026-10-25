# İnternette yayınlama (ücretsiz)

Sistem iki ücretsiz servisle yayınlanır:

| Servis | Görevi | Neden |
|---|---|---|
| **[Neon](https://neon.tech)** | PostgreSQL veritabanı | Ücretsiz planı süresizdir (Render'ın ücretsiz veritabanı 30 günde silinir); `btree_gist` desteklenir |
| **[Render](https://render.com)** | Java sunucusu (Docker) | GitHub'dan otomatik derler ve yayınlar; ücretsiz planı vardır |

Bu projenin canlı adresi: **https://siirt-fistik.onrender.com**

Toplam süre yaklaşık 15 dakika. Kod tarafında hazırlık tamam: depoda bir [`Dockerfile`](../Dockerfile) ve bir Render kurulum dosyası ([`render.yaml`](../render.yaml)) var.

```mermaid
flowchart LR
    U["👤 Hoca / jüri<br/>tarayıcı"] -- HTTPS --> R["Render<br/>Java 21 · Spring Boot<br/>(Docker, Ohio)"]
    R -- "JDBC + SSL" --> N[("Neon<br/>PostgreSQL")]
    G["GitHub<br/>nisanurhrmn1/siirt-fistik-sistemi"] -- "her push'ta<br/>otomatik derleme" --> R
```

---

## 1. Neon'da veritabanı oluşturma

1. https://neon.tech adresine gidin → **Sign up** → **Continue with GitHub**.
2. İlk girişte proje oluşturma ekranı açılır:
   - **Project name:** `siirt-fistik`
   - **Postgres version:** en yeni sürüm
   - **Region:** Veritabanı ile sunucu **aynı bölgede** olmalıdır, yoksa her sorgu okyanusu geçer ve site yavaşlar. Bu projede **AWS US East 2 (Ohio)** kullanıldı ve `render.yaml` da `region: ohio` olarak ayarlandı. Farklı bir bölge seçerseniz `render.yaml`'daki `region` değerini de eşleştirin (ör. Frankfurt → `frankfurt`).
   - **Create project**
3. Açılan panelde **Connect** düğmesine basın. **Connection string** kutusunda şuna benzer bir adres görünür:
   ```
   postgresql://neondb_owner:********@ep-xxxx-xxxx.eu-central-1.aws.neon.tech/neondb?sslmode=require
   ```
   Kopyalamak için yanındaki 📋 simgesine tıklayın. **Bu adres şifre içerir; kimseyle paylaşmayın ve GitHub'a koymayın.**

> Tabloları elle oluşturmanıza gerek yok. Sunucu ilk açılışta Flyway ile tabloları, trigger'ları ve rapor view'larını kendisi kurar.

## 2. Render'da sunucuyu yayınlama

1. https://render.com adresine gidin → **Get Started** → **GitHub** ile giriş yapın.
2. Sağ üstten **New → Blueprint** seçin.
3. **Connect a repository** listesinde `siirt-fistik-sistemi` deposunu seçin. Görünmüyorsa **Configure GitHub App** ile Render'a depo erişimi verin.
4. Render, depodaki `render.yaml` dosyasını okur ve **siirt-fistik** adlı bir web servisi önerir.
   - **Blueprint Name:** `siirt-fistik`
   - **DATABASE_URL** kutusuna 1. adımda kopyaladığınız Neon adresini yapıştırın.
   - Diğer ayarlar (JWT anahtarı, demo modu, gece sıfırlaması) dosyadan gelir; dokunmanıza gerek yok.
5. **Apply / Deploy Blueprint** düğmesine basın.
6. İlk derleme **5–10 dakika** sürer (Maven bağımlılıkları indirilir). **Logs** sekmesinde şu satırları görünce sistem hazırdır:
   ```
   Successfully applied 7 migrations ... now at version v7
   Demo verisi: 4 üretici, 6 teslimat, 1 avans, 1 ödeme ve 2 sevkiyat eklendi.
   Started SiirtFistikApplication in ... seconds
   ```
7. Sayfanın üstündeki adres sistemin internet adresidir, örneğin `https://siirt-fistik.onrender.com`.

## 3. Kontrol

| Adres | Beklenen |
|---|---|
| `https://<adres>/saglik` | `{"durum":"ok"}` |
| `https://<adres>/` | Giriş ekranı ve demo hesap düğmeleri |
| `https://<adres>/docs` | Swagger UI |

## 4. Hocaya / jüriye gönderilecek metin

> Siirt Fıstığı Alım ve Üretici Ödeme Sistemi'nin canlı sürümü: **https://siirt-fistik.onrender.com**
> Giriş ekranındaki demo düğmeleriyle (Kantar memuru, Muhasebe, Fiyat yöneticisi…) her rolü tek tıkla deneyebilirsiniz.
> Demo verisi her gece 03:00'te sıfırlanır. Kaynak kod: https://github.com/nisanurhrmn1/siirt-fistik-sistemi

---

## Bilinmesi gerekenler

- **İlk açılış yavaş olabilir.** Render'ın ücretsiz planında sunucu 15 dakika kullanılmazsa uyur; ilk ziyarette 30–60 saniyede uyanır. Sunumdan birkaç dakika önce adresi bir kez açın.
- **Herkese açık demo:**
  - Giriş ekranındaki demo hesaplarıyla herkes veri girebilir. Bu yüzden `GECE_SIFIRLAMA=true` ayarı açık: her gece 03:00'te işlem verisi silinip örnek veri yeniden yüklenir.
  - Ücretsiz planda sunucu uykudayken 03:00 zamanlayıcısı çalışmaz. Bu yüzden sunucu uyandığında o güne ait hiç tartım yoksa sıfırlama açılışta yapılır; demo her zaman bugün tarihli veriyle açılır.
  - Demo hesapları, kalite sınıfları ve silolar korunur. Ziyaretçilerin eklediği kullanıcılar ve hesap talepleri silinir.
  - Demo hesapları kilitlidir: ziyaretçiler onların rolünü veya şifresini değiştiremez.
- **Yönetici şifresi:** Render, `BASLANGIC_SIFRESI` değişkenini rastgele üretir. Şifreyle giriş yapmak isterseniz değeri Render panelinde **Environment** sekmesinde görebilirsiniz.
- **Güncelleme:** GitHub'a her `git push` sonrası Render otomatik olarak yeniden derleyip yayınlar (`autoDeploy: true`).
- **Demoyu kapatmak (gerçek kullanım):** Render → **Environment** → `DEMO_HESAPLARI`, `DEMO_VERISI` ve `GECE_SIFIRLAMA` değerlerini `false` yapın. Swagger'ı da kapatmak için `API_DOKUMANI=false` ekleyin.
- **Gerçek SMS:** Render → **Environment** → `SMS_SAGLAYICI=netgsm`, `SMS_KULLANICI`, `SMS_SIFRE` ve `SMS_BASLIK` (Netgsm'de onaylı gönderici adı) ekleyin. Bunlar yoksa SMS simülasyon modunda çalışır; gerçek SMS gitmez.
- **Ücretsiz plan sınırları:**
  - Render: aylık 750 saat çalışma ve 512 MB bellek. Dockerfile'daki JVM ayarları bu belleğe göre yapıldı.
  - Neon: 0,5 GB depolama.
