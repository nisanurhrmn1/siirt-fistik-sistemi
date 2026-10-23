package tr.edu.siirt.fistik.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tr.edu.siirt.fistik.alan.User;
import tr.edu.siirt.fistik.alan.UserRole;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.CakismaHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.DogrulamaHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Kullanıcı yönetimi. Herkese açık bir "kaydol" yoktur: rolü kullanıcı değil yönetici belirler.
 * <ul>
 *   <li>Giriş ekranından <b>hesap talebi</b> gönderilir; hesap yönetici onaylayana kadar giriş yapamaz.</li>
 *   <li>Yönetici talebi onaylar (rolü değiştirebilir) veya reddeder; ayrıca doğrudan kullanıcı ekleyebilir.</li>
 *   <li>Yöneticinin verdiği geçici şifre ilk girişte değiştirilir; her kullanıcı kendi şifresini değiştirebilir.</li>
 * </ul>
 */
public final class Kullanicilar {

    private Kullanicilar() {
    }

    static final String ZORUNLU = "Bu alan zorunludur.";
    static final String KULLANICI_ADI = "^[a-z0-9._-]{3,30}$";
    static final String KULLANICI_ADI_MESAJ = "3–30 karakter; yalnızca küçük harf, rakam, nokta, tire ve alt çizgi.";
    static final String SIFRE_MESAJ = "Şifre en az 8 karakter olmalıdır.";
    /** Herkese açık talep formunun kötüye kullanımına karşı: bekleyen talep sayısı sınırı. */
    static final int MAKS_BEKLEYEN_TALEP = 50;

    static String kucukHarf(String s) {
        return s == null ? null : s.strip().toLowerCase(Locale.ROOT);
    }

    static String kirp(String s) {
        if (s == null) return null;
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    // ─── İstekler ───

    public record HesapTalebi(
            @NotBlank(message = ZORUNLU) @Pattern(regexp = KULLANICI_ADI, message = KULLANICI_ADI_MESAJ)
            @Schema(example = "ayse.kaya") String kullaniciAdi,
            @NotBlank(message = ZORUNLU) @Size(min = 3, max = 120, message = "3–120 karakter olmalıdır.")
            @Schema(example = "Ayşe Kaya") String adSoyad,
            @NotNull(message = ZORUNLU) @Schema(example = "SCALE_OPERATOR") UserRole istenenRol,
            @NotBlank(message = ZORUNLU) @Size(min = 8, max = 100, message = SIFRE_MESAJ) String sifre,
            @Size(max = 300, message = "En fazla 300 karakter olabilir.")
            @Schema(example = "Kurtalan kantarında çalışıyorum.") String aciklama) {
        public HesapTalebi {
            kullaniciAdi = kucukHarf(kullaniciAdi);
            adSoyad = kirp(adSoyad);
            aciklama = kirp(aciklama);
        }
    }

    public record KullaniciGirisi(
            @NotBlank(message = ZORUNLU) @Pattern(regexp = KULLANICI_ADI, message = KULLANICI_ADI_MESAJ) String kullaniciAdi,
            @NotBlank(message = ZORUNLU) @Size(min = 3, max = 120, message = "3–120 karakter olmalıdır.") String adSoyad,
            @NotNull(message = ZORUNLU) UserRole rol,
            @NotBlank(message = ZORUNLU) @Size(min = 8, max = 100, message = SIFRE_MESAJ) String geciciSifre) {
        public KullaniciGirisi {
            kullaniciAdi = kucukHarf(kullaniciAdi);
            adSoyad = kirp(adSoyad);
        }
    }

    /** Yalnızca gönderilen alanlar değişir. */
    public record KullaniciGuncelleme(
            @Size(min = 3, max = 120, message = "3–120 karakter olmalıdır.") String adSoyad,
            UserRole rol,
            Boolean aktif) {
        public KullaniciGuncelleme {
            adSoyad = kirp(adSoyad);
        }
    }

    /** Onaylarken rol değiştirilebilir; boşsa talep edilen rol verilir. */
    public record OnayIstegi(UserRole rol) {
    }

    public record SifreSifirlama(
            @NotBlank(message = ZORUNLU) @Size(min = 8, max = 100, message = SIFRE_MESAJ) String yeniSifre) {
    }

    public record SifreDegistirme(
            @NotBlank(message = ZORUNLU) @Size(max = 100) String mevcutSifre,
            @NotBlank(message = ZORUNLU) @Size(min = 8, max = 100, message = SIFRE_MESAJ) String yeniSifre) {
    }

    // ─── Yanıtlar ───

    public record KullaniciYaniti(Long id, String kullaniciAdi, String adSoyad, UserRole rol, String rolAdi,
                                  String durum, String durumAdi, String aciklama, boolean sifreDegistirmeli,
                                  boolean demoHesabi, OffsetDateTime olusturulma) {
        static KullaniciYaniti from(User u, boolean demo) {
            String durum = u.isPendingApproval() ? "BEKLIYOR" : u.isActive() ? "AKTIF" : "PASIF";
            String durumAdi = u.isPendingApproval() ? "Onay bekliyor" : u.isActive() ? "Aktif" : "Pasif";
            return new KullaniciYaniti(u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.getRole().getAd(),
                    durum, durumAdi, u.getRequestNote(), u.isMustChangePassword(), demo, u.getCreatedAt());
        }
    }

    public record MesajYaniti(String mesaj) {
    }

    // ─── Servis ───

    @Service
    public static class KullaniciServisi {
        private final EntityManager em;
        private final PasswordEncoder sifreleyici;
        private final boolean demoModu;

        public KullaniciServisi(EntityManager em, PasswordEncoder sifreleyici,
                                @Value("${fistik.demo-hesaplari:true}") boolean demoModu) {
            this.em = em;
            this.sifreleyici = sifreleyici;
            this.demoModu = demoModu;
        }

        private boolean demo(User u) {
            return demoModu && Oturum.demoHesabi(u.getUsername());
        }

        private KullaniciYaniti yanit(User u) {
            return KullaniciYaniti.from(u, demo(u));
        }

        private User bul(Long id) {
            User u = em.find(User.class, id);
            if (u == null) throw new BulunamadiHatasi("Kullanıcı bulunamadı.");
            return u;
        }

        /** Herkese açık demoda demo hesapları kilitlidir; yoksa ziyaretçiler birbirinin girişini bozabilir. */
        private void demoKorumasi(User u) {
            if (demo(u)) throw new IsKuraliHatasi("Demo hesapları herkese açık sunumda değiştirilemez; yeni bir kullanıcı ekleyip onu deneyin.");
        }

        private void adBosMu(String kullaniciAdi) {
            Long n = em.createQuery("select count(u) from User u where lower(u.username) = :a", Long.class)
                    .setParameter("a", kullaniciAdi).getSingleResult();
            if (n > 0) throw new CakismaHatasi("Bu kullanıcı adı alınmış; başka bir ad seçin.");
        }

        private long aktifYoneticiSayisi() {
            return em.createQuery("select count(u) from User u where u.role = :r and u.active = true", Long.class)
                    .setParameter("r", UserRole.ADMIN).getSingleResult();
        }

        @Transactional
        public MesajYaniti talepOlustur(HesapTalebi t) {
            if (t.istenenRol() == UserRole.ADMIN)
                throw new DogrulamaHatasi("istenen_rol", "Yönetici rolü talep edilemez; yönetici hesabını mevcut bir yönetici verir.");
            Long bekleyen = em.createQuery("select count(u) from User u where u.pendingApproval = true", Long.class).getSingleResult();
            if (bekleyen >= MAKS_BEKLEYEN_TALEP)
                throw new IsKuraliHatasi("Şu an çok fazla bekleyen talep var; lütfen daha sonra tekrar deneyin.");
            adBosMu(t.kullaniciAdi());
            User u = new User();
            u.setUsername(t.kullaniciAdi());
            u.setFullName(t.adSoyad());
            u.setRole(t.istenenRol());
            u.setActive(false);
            u.setPendingApproval(true);
            u.setRequestNote(t.aciklama());
            u.setPasswordHash(sifreleyici.encode(t.sifre()));
            em.persist(u);
            return new MesajYaniti("Talebiniz alındı. Yönetici onayladığında bu kullanıcı adı ve şifreyle giriş yapabilirsiniz.");
        }

        @Transactional(readOnly = true)
        public List<KullaniciYaniti> listele() {
            return em.createQuery("select u from User u order by u.pendingApproval desc, u.active desc, u.id", User.class)
                    .getResultList().stream().map(this::yanit).toList();
        }

        @Transactional
        public KullaniciYaniti olustur(KullaniciGirisi g) {
            adBosMu(g.kullaniciAdi());
            User u = new User();
            u.setUsername(g.kullaniciAdi());
            u.setFullName(g.adSoyad());
            u.setRole(g.rol());
            u.setActive(true);
            u.setMustChangePassword(true);
            u.setPasswordHash(sifreleyici.encode(g.geciciSifre()));
            em.persist(u);
            em.flush();
            return yanit(u);
        }

        @Transactional
        public KullaniciYaniti onayla(Long id, UserRole rol) {
            User u = bul(id);
            if (!u.isPendingApproval()) throw new IsKuraliHatasi("Bu kullanıcının bekleyen bir talebi yok.");
            if (rol != null) u.setRole(rol);
            u.setPendingApproval(false);
            u.setActive(true);
            return yanit(u);
        }

        @Transactional
        public void reddet(Long id) {
            User u = bul(id);
            if (!u.isPendingApproval()) throw new IsKuraliHatasi("Yalnızca bekleyen talepler reddedilebilir; mevcut kullanıcıyı pasifleştirin.");
            em.remove(u);   // talep hiçbir kayda bağlı değil; kullanıcı adı yeniden kullanılabilir
        }

        @Transactional
        public KullaniciYaniti guncelle(Long id, KullaniciGuncelleme g, Long yapanId) {
            User u = bul(id);
            if (u.isPendingApproval()) throw new IsKuraliHatasi("Bu bir hesap talebi; önce onaylayın veya reddedin.");
            boolean rolDegisir = g.rol() != null && g.rol() != u.getRole();
            boolean pasiflesir = Boolean.FALSE.equals(g.aktif()) && u.isActive();
            if (rolDegisir || pasiflesir) demoKorumasi(u);
            if (u.getId().equals(yapanId) && (rolDegisir || pasiflesir))
                throw new IsKuraliHatasi("Kendi rolünüzü değiştiremez veya kendinizi pasifleştiremezsiniz.");
            if (u.getRole() == UserRole.ADMIN && u.isActive() && (rolDegisir || pasiflesir) && aktifYoneticiSayisi() <= 1)
                throw new IsKuraliHatasi("Sistemde en az bir aktif yönetici kalmalıdır.");
            if (g.adSoyad() != null) u.setFullName(g.adSoyad());
            if (g.rol() != null) u.setRole(g.rol());
            if (g.aktif() != null) u.setActive(g.aktif());
            return yanit(u);
        }

        @Transactional
        public KullaniciYaniti sifreSifirla(Long id, SifreSifirlama s) {
            User u = bul(id);
            demoKorumasi(u);
            u.setPasswordHash(sifreleyici.encode(s.yeniSifre()));
            u.setMustChangePassword(true);
            return yanit(u);
        }

        @Transactional
        public MesajYaniti sifreDegistir(Long yapanId, SifreDegistirme s) {
            User u = bul(yapanId);
            demoKorumasi(u);
            if (u.getPasswordHash() == null || !sifreleyici.matches(s.mevcutSifre(), u.getPasswordHash()))
                throw new DogrulamaHatasi("mevcut_sifre", "Mevcut şifre hatalı.");
            if (s.mevcutSifre().equals(s.yeniSifre()))
                throw new DogrulamaHatasi("yeni_sifre", "Yeni şifre mevcut şifreden farklı olmalıdır.");
            u.setPasswordHash(sifreleyici.encode(s.yeniSifre()));
            u.setMustChangePassword(false);
            return new MesajYaniti("Şifreniz değiştirildi.");
        }
    }

    // ─── Uç noktalar ───

    @RestController
    @RequestMapping("/api/v1/kullanicilar")
    @PreAuthorize(Yetki.KULLANICI)
    @Tag(name = "Kullanıcılar")
    public static class KullaniciKontrolcusu {
        private final KullaniciServisi servis;
        private final Kontrolculer.KullaniciCozucu kullanici;

        public KullaniciKontrolcusu(KullaniciServisi servis, Kontrolculer.KullaniciCozucu kullanici) {
            this.servis = servis;
            this.kullanici = kullanici;
        }

        @GetMapping
        @Operation(summary = "Tüm kullanıcılar (önce onay bekleyen talepler)")
        public List<KullaniciYaniti> listele() {
            return servis.listele();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Yeni kullanıcı ekle", description = "Hesap hemen aktif olur; geçici şifre ilk girişte değiştirilir.")
        public KullaniciYaniti olustur(@Valid @RequestBody KullaniciGirisi g) {
            return servis.olustur(g);
        }

        @PostMapping("/{id}/onayla")
        @Operation(summary = "Hesap talebini onayla", description = "Gövdede `rol` verilirse talep edilen rol yerine o atanır.")
        public KullaniciYaniti onayla(@PathVariable Long id, @RequestBody(required = false) OnayIstegi g) {
            return servis.onayla(id, g == null ? null : g.rol());
        }

        @DeleteMapping("/{id}/talep")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @Operation(summary = "Hesap talebini reddet (talep silinir)")
        public void reddet(@PathVariable Long id) {
            servis.reddet(id);
        }

        @PatchMapping("/{id}")
        @Operation(summary = "Ad, rol veya aktiflik değiştir",
                description = "Kendi rolünüzü değiştiremez, kendinizi pasifleştiremezsiniz; son aktif yönetici korunur.")
        public KullaniciYaniti guncelle(@PathVariable Long id, @Valid @RequestBody KullaniciGuncelleme g) {
            return servis.guncelle(id, g, kullanici.mevcut());
        }

        @PostMapping("/{id}/sifre-sifirla")
        @Operation(summary = "Şifreyi sıfırla (geçici şifre; ilk girişte değiştirilir)")
        public KullaniciYaniti sifreSifirla(@PathVariable Long id, @Valid @RequestBody SifreSifirlama s) {
            return servis.sifreSifirla(id, s);
        }
    }

    @RestController
    @RequestMapping("/api/v1/oturum")
    @Tag(name = "Oturum")
    public static class HesapKontrolcusu {
        private final KullaniciServisi servis;
        private final Kontrolculer.KullaniciCozucu kullanici;

        public HesapKontrolcusu(KullaniciServisi servis, Kontrolculer.KullaniciCozucu kullanici) {
            this.servis = servis;
            this.kullanici = kullanici;
        }

        @PostMapping("/hesap-talebi")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Hesap talebi gönder (giriş gerektirmez)",
                description = "Hesap, yönetici onaylayana kadar giriş yapamaz. Yönetici rolü talep edilemez.")
        public MesajYaniti talep(@Valid @RequestBody HesapTalebi t) {
            return servis.talepOlustur(t);
        }

        @PostMapping("/sifre-degistir")
        @Operation(summary = "Kendi şifremi değiştir")
        public MesajYaniti sifreDegistir(@Valid @RequestBody SifreDegistirme s) {
            return servis.sifreDegistir(kullanici.mevcut(), s);
        }
    }
}
