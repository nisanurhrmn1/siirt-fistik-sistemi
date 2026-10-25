package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * İlk açılışta başlangıç verisi: kullanıcı hesapları, kalite sınıfları, kesinti türleri ve silolar.
 * Yalnızca ilgili tablo boşsa ekler; tekrar çalışması güvenlidir. Fiyatlar panelden / API'den girilir.
 */
@Component
@Order(1)   // Her zaman ilk: hesaplar ve tanımlar, demo verisinden (Order 10) önce oluşmalı
public class BaslangicVerisi implements ApplicationRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BaslangicVerisi.class);

    private final EntityManager em;
    private final boolean etkin;
    private final boolean demoHesaplari;
    private final String baslangicSifresi;
    private final PasswordEncoder sifreleyici;
    private final String yoneticiKullanici, yoneticiAd, yoneticiSifre;
    private final boolean yoneticiSifreSifirla;

    public BaslangicVerisi(EntityManager em, PasswordEncoder sifreleyici,
                           @Value("${fistik.seed-demo-data:true}") boolean etkin,
                           @Value("${fistik.demo-hesaplari:true}") boolean demoHesaplari,
                           @Value("${fistik.baslangic-sifresi}") String baslangicSifresi,
                           @Value("${fistik.yonetici.kullanici:}") String yoneticiKullanici,
                           @Value("${fistik.yonetici.ad:}") String yoneticiAd,
                           @Value("${fistik.yonetici.sifre:}") String yoneticiSifre,
                           @Value("${fistik.yonetici.sifre-sifirla:false}") boolean yoneticiSifreSifirla) {
        this.em = em;
        this.sifreleyici = sifreleyici;
        this.etkin = etkin;
        this.demoHesaplari = demoHesaplari;
        this.baslangicSifresi = baslangicSifresi;
        this.yoneticiKullanici = yoneticiKullanici.strip().toLowerCase(java.util.Locale.ROOT);
        this.yoneticiAd = yoneticiAd.strip();
        this.yoneticiSifre = yoneticiSifre;
        this.yoneticiSifreSifirla = yoneticiSifreSifirla;
    }

    private boolean bos(Class<?> varlik) {
        return em.createQuery("select count(x) from " + varlik.getSimpleName() + " x", Long.class).getSingleResult() == 0;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!etkin) return;
        hesaplariHazirla();
        kaliciYoneticiHazirla();
        // Sınıflar ve silolar koda göre tek tek eklenir: sonradan eklenen yaş fıstık sınıfları mevcut veritabanlarına da gelir.
        // ÖRNEK eşikler — merkezin kendi kalite yönergesine göre güncelleyin. Yaş fıstıkta nem sınırı yoktur.
        sinifEkle("A", "1. Sınıf", ProductType.DRY, 1, "85", "55", "5", "8");
        sinifEkle("B", "2. Sınıf", ProductType.DRY, 2, "70", "48", "10", "10");
        sinifEkle("ISK", "Iskarta", ProductType.DRY, 9, null, null, null, null);
        sinifEkle("YA", "Yaş 1. Sınıf", ProductType.FRESH, 11, "80", "45", "8", null);
        sinifEkle("YB", "Yaş 2. Sınıf", ProductType.FRESH, 12, "65", "38", "15", null);
        sinifEkle("YISK", "Yaş Iskarta", ProductType.FRESH, 19, null, null, null, null);
        em.flush();
        if (bos(DeductionType.class)) {
            // ÖRNEK oranlar — güncel mevzuat ve borsa tarifesine göre güncelleyin.
            em.persist(kesinti("STOPAJ", "Gelir vergisi stopajı", "2", 1));
            em.persist(kesinti("BORSA", "Borsa tescil ücreti", "0.2", 2));
            em.persist(kesinti("KOMISYON", "Komisyon", "1", 3));
        }
        Map<String, Long> s = em.createQuery("select g from QualityGrade g", QualityGrade.class).getResultStream()
                .collect(Collectors.toMap(QualityGrade::getCode, QualityGrade::getId));
        siloEkle("S-A1", "A Sınıfı Silo 1", "12000", s.get("A"));
        siloEkle("S-A2", "A Sınıfı Silo 2", "12000", s.get("A"));
        siloEkle("S-B1", "B Sınıfı Silo", "10000", s.get("B"));
        siloEkle("S-ISK", "Iskarta Deposu", "4000", s.get("ISK"));
        siloEkle("S-YA", "Yaş Fıstık 1. Sınıf Sergisi", "8000", s.get("YA"));
        siloEkle("S-YB", "Yaş Fıstık 2. Sınıf Sergisi", "6000", s.get("YB"));
        siloEkle("S-YISK", "Yaş Iskarta Deposu", "3000", s.get("YISK"));
    }

    private boolean kodVar(Class<?> varlik, String kod) {
        return em.createQuery("select count(x) from " + varlik.getSimpleName() + " x where x.code = :k", Long.class)
                .setParameter("k", kod).getSingleResult() > 0;
    }

    private void sinifEkle(String kod, String ad, ProductType tur, int oncelik, String catlak, String randiman, String kusur,
                           String nem) {
        if (kodVar(QualityGrade.class, kod)) return;
        QualityGrade g = sinif(kod, ad, oncelik, catlak, randiman, kusur, nem);
        g.setProductType(tur);
        em.persist(g);
    }

    private void siloEkle(String kod, String ad, String kapasite, Long sinifId) {
        if (sinifId != null && !kodVar(Silo.class, kod)) em.persist(silo(kod, ad, kapasite, sinifId));
    }

    /**
     * Yönetici hesabı her zaman; demo modunda her rol için birer hesap. Şifresi olmayan mevcut hesaplara
     * (ör. Python sürümünün oluşturduğu "admin") şifre atanır. Şifreler BCrypt özeti olarak saklanır.
     */
    private void hesaplariHazirla() {
        for (Object[] h : tr.edu.siirt.fistik.api.Oturum.DEMO_HESAPLARI) {
            String ad = (String) h[0];
            UserRole rol = (UserRole) h[2];
            if (!demoHesaplari && rol != UserRole.ADMIN) continue;
            User u = em.createQuery("select u from User u where u.username = :a", User.class).setParameter("a", ad)
                    .getResultStream().findFirst().orElse(null);
            if (u == null) {
                u = new User();
                u.setUsername(ad);
                u.setFullName((String) h[1]);
                u.setRole(rol);
                u.setActive(true);
                em.persist(u);
            }
            if (u.getPasswordHash() == null) u.setPasswordHash(sifreleyici.encode(baslangicSifresi));
        }
        em.flush();
    }

    /**
     * Ortam değişkenleriyle tanımlanan kalıcı yönetici (YONETICI_KULLANICI, YONETICI_SIFRESI, isteğe bağlı YONETICI_AD).
     * Şifre koda veya depoya girmez; yalnızca hesap ilk oluşturulurken (ya da YONETICI_SIFRE_SIFIRLA=true ise) atanır,
     * böylece panelden değiştirilen şifre yeniden başlatmada ezilmez. Gece sıfırlaması bu hesabı silmez.
     */
    private void kaliciYoneticiHazirla() {
        if (yoneticiKullanici.isEmpty() && yoneticiSifre.isEmpty()) return;
        if (!yoneticiKullanici.matches("[a-z0-9._-]{3,30}") || yoneticiSifre.length() < 8) {
            log.warn("YONETICI_KULLANICI (3–30 karakter: küçük harf, rakam, . _ -) veya YONETICI_SIFRESI (en az 8 karakter) "
                    + "geçersiz; kalıcı yönetici oluşturulmadı.");
            return;
        }
        User u = em.createQuery("select u from User u where lower(u.username) = :a", User.class)
                .setParameter("a", yoneticiKullanici).getResultStream().findFirst().orElse(null);
        boolean yeni = u == null;
        if (yeni) {
            u = new User();
            u.setUsername(yoneticiKullanici);
            u.setFullName(yoneticiAd.isEmpty() ? yoneticiKullanici : yoneticiAd);
        } else if (!yoneticiAd.isEmpty()) {
            u.setFullName(yoneticiAd);
        }
        u.setRole(UserRole.ADMIN);
        u.setActive(true);
        u.setPendingApproval(false);
        if (yeni || yoneticiSifreSifirla || u.getPasswordHash() == null) {
            u.setPasswordHash(sifreleyici.encode(yoneticiSifre));
            u.setMustChangePassword(false);
        }
        if (yeni) em.persist(u);   // tüm zorunlu alanlar dolduktan sonra (IDENTITY: persist hemen INSERT eder)
        em.flush();
        log.info("Kalıcı yönetici hazır: {}{}", yoneticiKullanici, yeni ? " (yeni oluşturuldu)" : "");
    }

    private static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static QualityGrade sinif(String kod, String ad, int oncelik, String catlak, String randiman, String kusur, String nem) {
        QualityGrade g = new QualityGrade();
        g.setCode(kod);
        g.setName(ad);
        g.setPriority(oncelik);
        g.setMinSplitRatio(d(catlak));
        g.setMinYieldRatio(d(randiman));
        g.setMaxDefectRatio(d(kusur));
        g.setMaxMoistureRatio(d(nem));
        g.setActive(true);
        return g;
    }

    private static DeductionType kesinti(String kod, String ad, String oran, int sira) {
        DeductionType t = new DeductionType();
        t.setCode(kod);
        t.setName(ad);
        t.setCalcMethod(DeductionCalcMethod.PERCENT);
        t.setRate(new BigDecimal(oran));
        t.setSortOrder(sira);
        t.setActive(true);
        return t;
    }

    private static Silo silo(String kod, String ad, String kapasite, Long sinifId) {
        Silo s = new Silo();
        s.setCode(kod);
        s.setName(ad);
        s.setCapacityKg(new BigDecimal(kapasite));
        s.setDedicatedGradeId(sinifId);
        s.setActive(true);
        return s;
    }
}
