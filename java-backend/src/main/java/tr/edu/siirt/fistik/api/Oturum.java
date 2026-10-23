package tr.edu.siirt.fistik.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tr.edu.siirt.fistik.alan.User;
import tr.edu.siirt.fistik.alan.UserRole;
import tr.edu.siirt.fistik.kural.Hatalar.YetkiHatasi;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Oturum: giriş, demo girişi, oturumdaki kullanıcı. */
public final class Oturum {

    private Oturum() {
    }

    /** Demo hesapları (kullanıcı adı → rol). Başlangıç verisinde oluşturulur; demo modunda tek tıkla girilebilir. */
    public static final List<Object[]> DEMO_HESAPLARI = List.of(
            new Object[]{"admin", "Sistem Yöneticisi", UserRole.ADMIN},
            new Object[]{"kantar", "Kantar Memuru", UserRole.SCALE_OPERATOR},
            new Object[]{"muhasebe", "Muhasebe Sorumlusu", UserRole.ACCOUNTING},
            new Object[]{"fiyat", "Fiyat Yöneticisi", UserRole.PRICE_MANAGER},
            new Object[]{"depo", "Depo Sorumlusu", UserRole.WAREHOUSE});

    private static final Duration SURE = Duration.ofHours(8);   // bir vardiya

    public record GirisIstegi(@NotBlank(message = "Bu alan zorunludur.") @Size(max = 50) String kullaniciAdi,
                              @NotBlank(message = "Bu alan zorunludur.") @Size(max = 100) String sifre) {
    }

    public record DemoGirisIstegi(@NotBlank(message = "Bu alan zorunludur.") String kullaniciAdi) {
    }

    public record OturumKullanicisi(Long id, String kullaniciAdi, String adSoyad, UserRole rol, String rolAdi,
                                    List<String> yetkiler) {
        static OturumKullanicisi from(User u) {
            return new OturumKullanicisi(u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.getRole().getAd(),
                    Yetki.yetkiler(u.getRole()));
        }
    }

    public record GirisYaniti(String token, Instant gecerlilikSonu, OturumKullanicisi kullanici) {
    }

    public record DemoHesap(String kullaniciAdi, String adSoyad, UserRole rol, String rolAdi) {
    }

    @Service
    public static class OturumServisi {
        private final EntityManager em;
        private final PasswordEncoder sifreleyici;
        private final JwtEncoder jwt;
        private final boolean demoModu;

        public OturumServisi(EntityManager em, PasswordEncoder sifreleyici, JwtEncoder jwt,
                             @Value("${fistik.demo-hesaplari:true}") boolean demoModu) {
            this.em = em;
            this.sifreleyici = sifreleyici;
            this.jwt = jwt;
            this.demoModu = demoModu;
        }

        private User kullaniciAdiyla(String ad) {
            return em.createQuery("select u from User u where lower(u.username) = lower(:a)", User.class)
                    .setParameter("a", ad.strip()).getResultStream().findFirst().orElse(null);
        }

        private GirisYaniti tokenUret(User u) {
            Instant simdi = Instant.now(), son = simdi.plus(SURE);
            JwtClaimsSet iddialar = JwtClaimsSet.builder()
                    .issuer("siirt-fistik").subject(String.valueOf(u.getId())).issuedAt(simdi).expiresAt(son)
                    .claim("kullanici", u.getUsername()).claim("rol", u.getRole().name()).build();
            String token = jwt.encode(JwtEncoderParameters.from(
                    JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), iddialar)).getTokenValue();
            return new GirisYaniti(token, son, OturumKullanicisi.from(u));
        }

        @Transactional(readOnly = true)
        public GirisYaniti giris(GirisIstegi g) {
            User u = kullaniciAdiyla(g.kullaniciAdi());
            // Kullanıcı yoksa da aynı mesaj: hangi kullanıcı adlarının var olduğu sızdırılmaz.
            if (u == null || !u.isActive() || u.getPasswordHash() == null || !sifreleyici.matches(g.sifre(), u.getPasswordHash()))
                throw new YetkiHatasi("Kullanıcı adı veya şifre hatalı.");
            return tokenUret(u);
        }

        @Transactional(readOnly = true)
        public GirisYaniti demoGiris(String kullaniciAdi) {
            Set<String> demolar = demoModu ? DEMO_HESAPLARI.stream().map(h -> (String) h[0]).collect(java.util.stream.Collectors.toSet()) : Set.of();
            User u = kullaniciAdiyla(kullaniciAdi);
            if (u == null || !u.isActive() || !demolar.contains(u.getUsername()))
                throw new YetkiHatasi("Demo girişi kapalı veya bu hesap bir demo hesabı değil.");
            return tokenUret(u);
        }

        @Transactional(readOnly = true)
        public List<DemoHesap> demoHesaplar() {
            if (!demoModu) return List.of();
            return DEMO_HESAPLARI.stream().map(h -> kullaniciAdiyla((String) h[0])).filter(u -> u != null && u.isActive())
                    .map(u -> new DemoHesap(u.getUsername(), u.getFullName(), u.getRole(), u.getRole().getAd())).toList();
        }

        @Transactional(readOnly = true)
        public OturumKullanicisi ben(Long id) {
            User u = em.find(User.class, id);
            if (u == null || !u.isActive()) throw new YetkiHatasi("Kullanıcı bulunamadı veya pasif.");
            return OturumKullanicisi.from(u);
        }
    }

    @RestController
    @RequestMapping("/api/v1/oturum")
    @Tag(name = "Oturum", description = "Giriş, demo girişi ve oturumdaki kullanıcı")
    public static class OturumKontrolcusu {
        private final OturumServisi servis;

        public OturumKontrolcusu(OturumServisi servis) {
            this.servis = servis;
        }

        @PostMapping("/giris")
        @Operation(summary = "Kullanıcı adı ve şifreyle giriş",
                description = "Başarılıysa 8 saat geçerli bir JWT döner. Sonraki isteklerde `Authorization: Bearer <token>` gönderin.")
        public GirisYaniti giris(@Valid @RequestBody GirisIstegi g) {
            return servis.giris(g);
        }

        @PostMapping("/demo-giris")
        @Operation(summary = "Demo hesabıyla tek tıkla giriş (yalnızca demo modunda)")
        public GirisYaniti demoGiris(@Valid @RequestBody DemoGirisIstegi g) {
            return servis.demoGiris(g.kullaniciAdi());
        }

        @GetMapping("/demo-hesaplar")
        @Operation(summary = "Giriş ekranında gösterilecek demo hesapları (demo modu kapalıysa boş)")
        public List<DemoHesap> demoHesaplar() {
            return servis.demoHesaplar();
        }

        @GetMapping("/ben")
        @Operation(summary = "Oturumdaki kullanıcı, rolü ve yetkileri")
        public OturumKullanicisi ben(@AuthenticationPrincipal Jwt jwt) {
            return servis.ben(Long.valueOf(jwt.getSubject()));
        }
    }
}
