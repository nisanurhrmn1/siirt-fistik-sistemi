package tr.edu.siirt.fistik.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tr.edu.siirt.fistik.alan.User;
import tr.edu.siirt.fistik.alan.UserRole;
import tr.edu.siirt.fistik.api.Istekler.*;
import tr.edu.siirt.fistik.api.Yanitlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.DogrulamaHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.YetkiHatasi;
import tr.edu.siirt.fistik.servis.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** REST uç noktaları — /api/v1 (Python/FastAPI sürümüyle aynı adresler ve JSON sözleşmesi). */
public final class Kontrolculer {

    private Kontrolculer() {
    }

    static final String KULLANICI = "X-Kullanici-Id";

    /** Alan kısıtları geçtikten sonra çok alanlı kuralları denetler (aksi halde 422). */
    static <T> T model(T istek) {
        if (istek instanceof ModelKontrollu m) {
            var h = m.modelHatalari();
            if (!h.isEmpty()) throw new DogrulamaHatasi(h);
        }
        return istek;
    }

    /** İşlemi yapan kullanıcı. TODO: kimlik doğrulama eklendiğinde token'dan okunacak. */
    @Component
    public static class KullaniciCozucu {
        private final EntityManager em;

        public KullaniciCozucu(EntityManager em) {
            this.em = em;
        }

        @Transactional(readOnly = true)
        public Long coz(Long baslikId) {
            if (baslikId != null) {
                User u = em.find(User.class, baslikId);
                if (u == null || !u.isActive()) throw new YetkiHatasi("Kullanıcı bulunamadı veya pasif (id=%d).".formatted(baslikId));
                return u.getId();
            }
            return em.createQuery("select u.id from User u where u.active = true and u.role = :r order by u.id", Long.class)
                    .setParameter("r", UserRole.ADMIN).setMaxResults(1).getResultStream().findFirst()
                    .orElseThrow(() -> new YetkiHatasi("Sistemde aktif kullanıcı yok; X-Kullanici-Id başlığı gönderin."));
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
        @Operation(summary = "Yeni üretici kaydı",
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

        public Tartimlar(TartimServisi servis, KullaniciCozucu kullanici) {
            this.servis = servis;
            this.kullanici = kullanici;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Tartım + kalite analizi ile fıstık alımı",
                description = "Tek işlemde: kantar fişi ve net kg, kalite sınıfı, tartım anındaki onaylı fiyatın kilitlenmesi, "
                        + "kesinti ve avans mahsubuyla hakediş, lot ve silo stok girişi. Aynı üretici aynı gün birden çok teslimat yapabilir.")
        public TartimYaniti kaydet(@Valid @RequestBody TartimGirisi g, @RequestHeader(value = KULLANICI, required = false) Long k) {
            return servis.kaydet(model(g), kullanici.coz(k));
        }

        @PostMapping("/onizleme")
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
        @Operation(summary = "Kalite sınıflarına göre taban fiyat girişi",
                description = "Fiyatlar onaylı kaydedilir; aynı sınıfın önceki fiyatı yeni fiyatın başladığı anda kapanır. Geçmişe dönük fiyat girilemez.")
        public List<FiyatYaniti> gir(@Valid @RequestBody FiyatGirisi g, @RequestHeader(value = KULLANICI, required = false) Long k) {
            return servis.gir(model(g), kullanici.coz(k));
        }

        @GetMapping("/guncel")
        @Operation(summary = "Şu an geçerli fiyatlar (her kalite sınıfı için)")
        public GuncelFiyatlar guncel() {
            return servis.guncel();
        }
    }

    // ─── Finans ───
    @RestController
    @RequestMapping("/api/v1")
    @Tag(name = "Finans: Avans, Hakediş, Ödeme", description = "Avans, avans mahsubu ve parçalı/tam ödeme")
    public static class Finans {
        private final FinansServisi servis;
        private final KullaniciCozucu kullanici;

        public Finans(FinansServisi servis, KullaniciCozucu kullanici) {
            this.servis = servis;
            this.kullanici = kullanici;
        }

        @PostMapping("/avanslar")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Üreticiye avans ver",
                description = "Avans cari hesaba borç yazılır; bakiye eksiye düşebilir. Sonraki hakedişlerden FIFO ile mahsup edilir.")
        public AvansYaniti avansVer(@Valid @RequestBody AvansGirisi g, @RequestHeader(value = KULLANICI, required = false) Long k) {
            return servis.avansVer(g, kullanici.coz(k));
        }

        @GetMapping("/hakedisler/{id}")
        @Operation(summary = "Hakediş detayı")
        public HakedisYaniti hakedis(@PathVariable Long id) {
            return servis.hakedisGetir(id);
        }

        @PostMapping("/hakedisler/{id}/odeme-yap")
        @Operation(summary = "Hakediş ödemesi (kısmi veya tam)",
                description = "Önce hakediş oluştuktan sonra verilmiş açık avansları kalan tutardan mahsup eder, ardından öder. "
                        + "201: ödeme kaydı oluştu · 200: kalanın tamamı avansa mahsup edildi · 400: kapalı hakediş veya tutar aşımı")
        public ResponseEntity<OdemeSonucu> odemeYap(@PathVariable Long id, @Valid @RequestBody OdemeGirisi g,
                                                    @RequestHeader(value = KULLANICI, required = false) Long k) {
            var islem = servis.odemeYap(id, g, kullanici.coz(k));
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
