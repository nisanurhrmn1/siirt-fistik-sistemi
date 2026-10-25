package tr.edu.siirt.fistik.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import tr.edu.siirt.fistik.api.Istekler.*;
import tr.edu.siirt.fistik.api.Yanitlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.DogrulamaHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.YetkiHatasi;
import tr.edu.siirt.fistik.servis.*;
import tr.edu.siirt.fistik.sms.SmsServisi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** REST uç noktaları — /api/v1 (Python/FastAPI sürümüyle aynı adresler ve JSON sözleşmesi). */
public final class Kontrolculer {

    private Kontrolculer() {
    }

    /** Alan kısıtları geçtikten sonra çok alanlı kuralları denetler (aksi halde 422). */
    static <T> T model(T istek) {
        if (istek instanceof ModelKontrollu m) {
            var h = m.modelHatalari();
            if (!h.isEmpty()) throw new DogrulamaHatasi(h);
        }
        return istek;
    }

    /** İşlemi yapan kullanıcı: giriş token'ındaki "sub" (kullanıcı id'si). Her kayıt bu kullanıcıyla imzalanır. */
    @Component
    public static class KullaniciCozucu {
        public Long mevcut() {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a instanceof JwtAuthenticationToken jwt) return Long.valueOf(jwt.getToken().getSubject());
            throw new YetkiHatasi("Oturum açmanız gerekiyor.");
        }
    }

    // ─── Üreticiler ───
    @RestController
    @RequestMapping("/api/v1/ureticiler")
    @Tag(name = "Üreticiler", description = "Üretici kaydı, listeleme ve cari hesap ekstresi")
    public static class Ureticiler {
        private final UreticiServisi servis;
        private final FinansServisi finans;

        public Ureticiler(UreticiServisi servis, FinansServisi finans) {
            this.servis = servis;
            this.finans = finans;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize(Yetki.URETICI)
        @Operation(summary = "Yeni üretici kaydı (Yönetici, Muhasebe)",
                description = "TC Kimlik No (algoritma kontrolü), Vergi No, telefon ve IBAN (mod-97) doğrulanır. Aynı TC/VKN ile ikinci kayıt 409 döner.")
        public UreticiYaniti olustur(@Valid @RequestBody UreticiGirisi g) {
            return servis.olustur(model(g));
        }

        @GetMapping
        @Operation(summary = "Üretici listesi (sayfalı, aranabilir)")
        public Sayfa<UreticiYaniti> listele(
                @RequestParam(required = false) @Size(max = 100) @Parameter(description = "Ad, TC, VKN veya telefonda arar") String arama,
                @RequestParam(required = false) Boolean aktif,
                @RequestParam(defaultValue = "1") @Min(value = 1, message = "1 veya daha büyük olmalıdır.") int sayfa,
                @RequestParam(value = "sayfa_boyutu", defaultValue = "20") @Min(value = 1, message = "1 veya daha büyük olmalıdır.")
                @Max(value = 100, message = "En fazla 100 olabilir.") int sayfaBoyutu) {
            return servis.listele(arama, aktif, sayfa, sayfaBoyutu);
        }

        @GetMapping("/{id}")
        @Operation(summary = "Üretici detayı")
        public UreticiYaniti getir(@PathVariable Long id) {
            return servis.getir(id);
        }

        @GetMapping("/{id}/bakiye-ekstre")
        @Operation(summary = "Üretici cari hesap ekstresi",
                description = "Genel özet, dönemdeki teslimatlar, açık avanslar ve yürüyen bakiyeli cari hareketler (Türkiye saati).")
        public UreticiEkstre ekstre(@PathVariable Long id,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baslangic,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bitis) {
            return finans.ekstre(id, baslangic, bitis);
        }
    }

    // ─── Kantar ve alım ───
    @RestController
    @RequestMapping("/api/v1/tartimlar")
    @Tag(name = "Kantar ve Alım", description = "Tartım, kalite analizi, fiyat kilidi, hakediş ve stok girişi")
    public static class Tartimlar {
        private final TartimServisi servis;
        private final KullaniciCozucu kullanici;
        private final SmsServisi sms;

        public Tartimlar(TartimServisi servis, KullaniciCozucu kullanici, SmsServisi sms) {
            this.servis = servis;
            this.kullanici = kullanici;
            this.sms = sms;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize(Yetki.TARTIM)
        @Operation(summary = "Tartım + kalite analizi ile fıstık alımı (Yönetici, Kantar memuru)",
                description = "Tek işlemde: kantar fişi ve net kg, kalite sınıfı, tartım anındaki onaylı fiyatın kilitlenmesi, "
                        + "kesinti ve avans mahsubuyla hakediş, lot ve silo stok girişi. Aynı üretici aynı gün birden çok teslimat yapabilir.")
        public TartimYaniti kaydet(@Valid @RequestBody TartimGirisi g) {
            TartimYaniti t = servis.kaydet(model(g), kullanici.mevcut());
            // Kayıt tamamlandıktan sonra müstahsile SMS; SMS hatası tartımı geri almaz
            sms.teslimatBildir(t);
            return servis.getir(t.id());
        }

        @GetMapping("/{id}/satis-onizleme")
        @Operation(summary = "Emanet bugün satışa çevrilseydi: fiyat, çatlak primi, kesinti, avans mahsubu ve net ödenecek")
        public TartimOnizleme satisOnizleme(@PathVariable Long id,
                                            @RequestParam(value = "miktar_kg", required = false) @Positive(message = "0 değerinden büyük olmalıdır.")
                                            java.math.BigDecimal miktarKg) {
            return servis.satisOnizleme(id, miktarKg);
        }

        @PostMapping("/{id}/satisa-cevir")
        @PreAuthorize(Yetki.FINANS)
        @Operation(summary = "Emaneti (tamamını veya bir kısmını) satışa çevir (Yönetici, Muhasebe)",
                description = "Satılan miktar için bozdurma fişi (EB-…) açılır: o günün fiyatı, çatlak primi ve boş tane iskontosuyla "
                        + "fiyat kilitlenir; hakediş, kesintiler ve açık avans mahsubu oluşur. Kalan kg emanette kalır. "
                        + "Gövde boşsa kalan emanetin tamamı satılır.")
        public TartimYaniti satisaCevir(@PathVariable Long id, @Valid @RequestBody(required = false) SatisaCevirGirisi g) {
            TartimYaniti t = servis.emanetiSatisaCevir(id, g != null ? g.miktarKg() : null, kullanici.mevcut());
            sms.satisaCevirmeBildir(t);
            return servis.getir(t.id());
        }

        @PostMapping("/onizleme")
        @PreAuthorize(Yetki.TARTIM)
        @Operation(summary = "Kaydetmeden tahmini hakediş", description = "Kantar ekranındaki canlı hesaplama; veritabanına yazılmaz.")
        public TartimOnizleme onizle(@Valid @RequestBody OnizlemeGirisi g) {
            return servis.onizle(model(g));
        }

        @GetMapping
        @Operation(summary = "Günün tartımları (en yeni önce)")
        public List<TartimOzeti> listele(
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tarih,
                @RequestParam(value = "uretici_id", required = false) @Positive Long ureticiId) {
            return servis.listele(tarih != null ? tarih : Dogrulama.bugunTr(), ureticiId);
        }

        @GetMapping("/{id}")
        @Operation(summary = "Tartım (teslimat) detayı")
        public TartimYaniti getir(@PathVariable Long id) {
            return servis.getir(id);
        }
    }

    // ─── Fiyat ───
    @RestController
    @RequestMapping("/api/v1/fiyatlar")
    @Tag(name = "Fiyat Yönetimi", description = "Kalite sınıfı bazlı günlük taban fiyatlar")
    public static class Fiyatlar {
        private final FiyatServisi servis;
        private final KullaniciCozucu kullanici;

        public Fiyatlar(FiyatServisi servis, KullaniciCozucu kullanici) {
            this.servis = servis;
            this.kullanici = kullanici;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize(Yetki.FIYAT)
        @Operation(summary = "Kalite sınıflarına göre taban fiyat girişi (Yönetici, Fiyat yöneticisi)",
                description = "Fiyatlar onaylı kaydedilir; aynı sınıfın önceki fiyatı yeni fiyatın başladığı anda kapanır. Geçmişe dönük fiyat girilemez.")
        public List<FiyatYaniti> gir(@Valid @RequestBody FiyatGirisi g) {
            return servis.gir(model(g), kullanici.mevcut());
        }

        @GetMapping("/guncel")
        @Operation(summary = "Şu an geçerli fiyatlar (her kalite sınıfı için)")
        public GuncelFiyatlar guncel() {
            return servis.guncel();
        }

        @GetMapping("/catlak-primi")
        @Operation(summary = "Çatlak primi / iskontosu kuralları (ürün türü başına)")
        public List<CatlakPrimiKurali> catlakPrimi() {
            return servis.catlakPrimiKurallari();
        }

        @PutMapping("/catlak-primi/{urunTuru}")
        @PreAuthorize(Yetki.FIYAT)
        @Operation(summary = "Çatlak primi kuralını güncelle (Yönetici, Fiyat yöneticisi)",
                description = "Birim fiyat = baz fiyat + (çatlak % − baz çatlak %) × puan başı ₺, en fazla ± azami fark. "
                        + "Yalnızca bundan sonraki tartımlara uygulanır.")
        public CatlakPrimiKurali catlakPrimiGuncelle(@PathVariable tr.edu.siirt.fistik.alan.ProductType urunTuru,
                                                     @Valid @RequestBody CatlakPrimiGirisi g) {
            return servis.catlakPrimiGuncelle(urunTuru, g, kullanici.mevcut());
        }
    }

    // ─── Finans ───
    @RestController
    @RequestMapping("/api/v1")
    @Tag(name = "Finans: Avans, Hakediş, Ödeme", description = "Avans, avans mahsubu ve parçalı/tam ödeme")
    public static class Finans {
        private final FinansServisi servis;
        private final KullaniciCozucu kullanici;
        private final SmsServisi sms;

        public Finans(FinansServisi servis, KullaniciCozucu kullanici, SmsServisi sms) {
            this.servis = servis;
            this.kullanici = kullanici;
            this.sms = sms;
        }

        @PostMapping("/avanslar")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize(Yetki.FINANS)
        @Operation(summary = "Üreticiye avans ver (Yönetici, Muhasebe)",
                description = "Avans cari hesaba borç yazılır; bakiye eksiye düşebilir. Sonraki hakedişlerden FIFO ile mahsup edilir.")
        public AvansYaniti avansVer(@Valid @RequestBody AvansGirisi g) {
            AvansYaniti a = servis.avansVer(g, kullanici.mevcut());
            return a.smsIle(sms.avansBildir(a));
        }

        @GetMapping("/kesintiler")
        @Operation(summary = "Resmî / ticari kesinti tanımları (stopaj, borsa, komisyon, Bağ-Kur)")
        public List<KesintiTuru> kesintiler() {
            return servis.kesintiTurleri();
        }

        @PutMapping("/kesintiler/{kod}")
        @PreAuthorize(Yetki.FINANS)
        @Operation(summary = "Kesinti oranını / açık-kapalı durumunu güncelle (Yönetici, Muhasebe)",
                description = "Yalnızca bundan sonraki hakedişlere uygulanır; oluşmuş hakedişlerin kesinti oranları değişmez.")
        public KesintiTuru kesintiGuncelle(@PathVariable String kod, @Valid @RequestBody KesintiGirisi g) {
            return servis.kesintiGuncelle(kod, g);
        }

        @GetMapping("/sms")
        @PreAuthorize(Yetki.FINANS)
        @Operation(summary = "Gönderilen bilgilendirme SMS'leri (son 100; Yönetici, Muhasebe)")
        public List<SmsServisi.SmsBildirimi> smsler(@RequestParam(value = "uretici_id", required = false) Long ureticiId) {
            return sms.listele(ureticiId);
        }

        @GetMapping("/hakedisler/{id}")
        @Operation(summary = "Hakediş detayı")
        public HakedisYaniti hakedis(@PathVariable Long id) {
            return servis.hakedisGetir(id);
        }

        @PostMapping("/hakedisler/{id}/odeme-yap")
        @PreAuthorize(Yetki.FINANS)
        @Operation(summary = "Hakediş ödemesi, kısmi veya tam (Yönetici, Muhasebe)",
                description = "Önce hakediş oluştuktan sonra verilmiş açık avansları kalan tutardan mahsup eder, ardından öder. "
                        + "201: ödeme kaydı oluştu · 200: kalanın tamamı avansa mahsup edildi · 400: kapalı hakediş veya tutar aşımı")
        public ResponseEntity<OdemeSonucu> odemeYap(@PathVariable Long id, @Valid @RequestBody OdemeGirisi g) {
            var islem = servis.odemeYap(id, g, kullanici.mevcut());
            return ResponseEntity.status(islem.odemeOlustu() ? HttpStatus.CREATED : HttpStatus.OK).body(islem.sonuc());
        }
    }

    // ─── Depo ve raporlar ───
    @RestController
    @RequestMapping("/api/v1")
    public static class DepoRapor {
        private final RaporServisi servis;

        public DepoRapor(RaporServisi servis) {
            this.servis = servis;
        }

        @GetMapping("/depo/stok-durumu")
        @Tag(name = "Depo", description = "Silo ve kalite bazlı stok")
        @Operation(summary = "Silo veya kalite sınıfına göre stok")
        public StokDurumu stok(
                @RequestParam(defaultValue = "silo") @Pattern(regexp = "silo|kalite", message = "Şu değerlerden biri olmalıdır: 'silo', 'kalite'.") String gruplama,
                @RequestParam(value = "silo_id", required = false) Long siloId,
                @RequestParam(value = "kalite_kodu", required = false) @Size(max = 10) String kaliteKodu) {
            return servis.stokDurumu(gruplama, siloId, kaliteKodu);
        }

        @GetMapping("/raporlar/uretici-bakiyeleri")
        @Tag(name = "Raporlar", description = "Günlük alım ve finans özetleri")
        @Operation(summary = "Tüm üreticilerin cari bakiye, açık avans ve ödenebilir tutarı")
        public List<UreticiBakiye> bakiyeler() {
            return servis.ureticiBakiyeleri();
        }

        @GetMapping("/raporlar/gunluk-ozet")
        @Tag(name = "Raporlar")
        @Operation(summary = "Günlük alım ve ödeme özeti", description = "Gün sınırı Türkiye saatidir.")
        public GunlukOzet gunlukOzet(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tarih) {
            return servis.gunlukOzet(tarih != null ? tarih : Dogrulama.bugunTr());
        }

        @GetMapping("/raporlar/gun-sonu")
        @Tag(name = "Raporlar")
        @Operation(summary = "Gün sonu kasa ve kantar raporu",
                description = "Teslimatlar, nakit/havale/çek ödemeler ve avanslar, kalan hakediş, ürün türü ve silo bazlı giren stok.")
        public GunSonuRaporu gunSonu(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tarih) {
            return servis.gunSonu(tarih != null ? tarih : Dogrulama.bugunTr());
        }

        @GetMapping(value = "/raporlar/gun-sonu.csv", produces = "text/csv")
        @Tag(name = "Raporlar")
        @Operation(summary = "Gün sonu raporu (Excel'de açılan CSV)",
                description = "UTF-8 BOM, ';' ayraç, ondalık virgül: Türkçe Excel'de çift tıklayınca doğrudan açılır.")
        public ResponseEntity<byte[]> gunSonuCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tarih) {
            LocalDate gun = tarih != null ? tarih : Dogrulama.bugunTr();
            byte[] icerik = CsvRaporu.gunSonu(servis.gunSonu(gun)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"gun-sonu-%s.csv\"".formatted(gun))
                    .body(icerik);
        }
    }

    // ─── Sistem ───
    @RestController
    @Tag(name = "Sistem", description = "Sağlık kontrolü")
    public static class Saglik {
        @GetMapping("/saglik")
        @Operation(summary = "Sağlık kontrolü")
        public Map<String, String> saglik() {
            return Map.of("durum", "ok");
        }
    }
}
