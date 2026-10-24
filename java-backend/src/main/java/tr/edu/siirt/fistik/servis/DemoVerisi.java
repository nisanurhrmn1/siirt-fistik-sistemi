package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tr.edu.siirt.fistik.alan.PaymentMethod;
import tr.edu.siirt.fistik.alan.ProducerType;
import tr.edu.siirt.fistik.alan.SaleCollection;
import tr.edu.siirt.fistik.alan.WeightSource;
import tr.edu.siirt.fistik.api.Istekler.*;
import tr.edu.siirt.fistik.api.Sevkiyatlar.SevkiyatGirisi;
import tr.edu.siirt.fistik.api.Yanitlar.TartimYaniti;
import tr.edu.siirt.fistik.api.Yanitlar.UreticiYaniti;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Sunum için örnek veri: 4 üretici, günlük fiyatlar, 1 avans, 6 teslimat, 1 ödeme ve 2 sevkiyat (satış).
 * Yalnızca {@code --fistik.demo-verisi=true} ile ve üretici tablosu boşsa çalışır.
 */
@Component
@Order(10)   // BaslangicVerisi'nden sonra
public class DemoVerisi implements ApplicationRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoVerisi.class);

    private final boolean etkin;
    private final EntityManager em;
    private final UreticiServisi ureticiler;
    private final FiyatServisi fiyatlar;
    private final FinansServisi finans;
    private final TartimServisi tartimlar;
    private final SevkiyatServisi sevkiyatlar;

    public DemoVerisi(@Value("${fistik.demo-verisi:false}") boolean etkin, EntityManager em, UreticiServisi ureticiler,
                      FiyatServisi fiyatlar, FinansServisi finans, TartimServisi tartimlar, SevkiyatServisi sevkiyatlar) {
        this.etkin = etkin;
        this.em = em;
        this.ureticiler = ureticiler;
        this.fiyatlar = fiyatlar;
        this.finans = finans;
        this.tartimlar = tartimlar;
        this.sevkiyatlar = sevkiyatlar;
    }

    private Long silo(String kod) {
        return em.createQuery("select s.id from Silo s where s.code = :k", Long.class).setParameter("k", kod).getSingleResult();
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!etkin) return;
        if (em.createQuery("select count(p) from Producer p", Long.class).getSingleResult() > 0) {
            log.info("Veritabanında zaten üretici var; demo verisi eklenmedi.");
            return;
        }
        try {
            yukle();
        } catch (RuntimeException e) {
            // Demo verisi isteğe bağlıdır: yüklenemezse site yine de açılmalı.
            log.error("Demo verisi yüklenemedi; sistem demo verisi olmadan çalışmaya devam ediyor.", e);
        }
    }

    /** Örnek veriyi yükler. Veritabanında üretici olmadığı varsayılır (bkz. GeceSifirlama). */
    public void yukle() {
        Long k = em.createQuery("select u.id from User u where u.role = :r order by u.id", Long.class)
                .setParameter("r", tr.edu.siirt.fistik.alan.UserRole.ADMIN).setMaxResults(1).getResultList().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo verisi için yönetici hesabı bulunamadı."));
        // TC'ler algoritmik olarak geçerli, gerçek kişilere ait olmayan test numaralarıdır.
        List<UreticiYaniti> u = new ArrayList<>();
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000000146", null, null, "Ahmet Yılmaz", "0532 111 22 33", null, null,
                "Kurtalan", null, new BankaHesabiGirisi("Ziraat Bankası", "TR330006100519786457841326", null), null)));
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000000078", null, null, "Fatma Demir", "0533 444 55 66", null, null,
                "Merkez", null, null, null)));
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000000214", null, null, "Mehmet Kaya", "0544 777 88 99", null, null,
                "Pervari", null, null, null)));
        u.add(ureticiler.olustur(new UreticiGirisi(ProducerType.COMPANY, null, "1234567890", "Siirt", "Botan Tarım Ltd. Şti.",
                "0484 223 45 67", null, null, "Merkez", null, null, null)));

        fiyatlar.gir(new FiyatGirisi(List.of(new FiyatKalemi("A", d("300")), new FiyatKalemi("B", d("260")),
                new FiyatKalemi("ISK", d("150")), // Yaş fıstık kurutma firesi nedeniyle kuru-kavlağın yaklaşık %45'i fiyatından alınır.
                new FiyatKalemi("YA", d("135")), new FiyatKalemi("YB", d("115")), new FiyatKalemi("YISK", d("65"))), null, null), k);
        finans.avansVer(new AvansGirisi(u.get(2).id(), d("250000"), PaymentMethod.CASH, null, "Gübre avansı"), k);

        Object[][] teslimatlar = {   // üretici, plaka, brüt, dara, çatlak, randıman, boş, çürük, nem
                {0, "56 ABC 123", "4800", "3800", "91", "58", "2", "1", "6"},
                {1, "56 DE 456", "5350", "3900", "88", "56", "3", "1", "7"},
                {2, "56 FG 789", "4200", "3600", "76", "50", "6", "2", "9"},
                {0, "56 ABC 123", "4550", "3800", "90", "57", "2", "1", "6"},
                {3, "56 BT 001", "9800", "7200", "86", "55", "4", "1", "7.5"},
                {2, "56 FG 789", "3900", "3600", "60", "40", "15", "5", "12"}};
        TartimYaniti ilk = null;
        for (Object[] t : teslimatlar) {
            TartimYaniti y = tartimlar.kaydet(new TartimGirisi(u.get((int) t[0]).id(), (String) t[1], null, d((String) t[2]),
                    d((String) t[3]), new KaliteAnalizi(null, d((String) t[4]), d((String) t[5]), d((String) t[6]),
                    d((String) t[7]), d((String) t[8])), null, null, null, null, null, null, null, null), k);
            if (ilk == null) ilk = y;
        }
        finans.odemeYap(ilk.hakedis().id(), new OdemeGirisi(d("150000"), null, null, "EFT-DEMO-1", null), k);

        // Satış: A sınıfı siloyu fabrikaya, B sınıfının bir kısmını toptancıya (vadeli) sevk et
        sevkiyatlar.kaydet(new SevkiyatGirisi("Antep Fıstıkçılık A.Ş.", null, "27 AF 270", "Hasan Demir", silo("S-A1"),
                d("11700"), d("8200"), d("420"), SaleCollection.BANK_TRANSFER, WeightSource.SCALE, WeightSource.SCALE,
                "Kırma tesisi siparişi"), k);
        sevkiyatlar.kaydet(new SevkiyatGirisi("Gaziantep Kuruyemiş Toptan", null, "27 GK 404", "Ali Kaya", silo("S-B1"),
                d("3500"), d("3100"), d("360"), SaleCollection.ON_ACCOUNT, WeightSource.SCALE, WeightSource.SCALE,
                "30 gün vadeli"), k);
        log.info("Demo verisi: 4 üretici, 6 teslimat, 1 avans, 1 ödeme ve 2 sevkiyat eklendi.");
    }
}
