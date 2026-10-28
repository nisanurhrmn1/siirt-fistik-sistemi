package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tr.edu.siirt.fistik.alan.DailyPrice;
import tr.edu.siirt.fistik.alan.PaymentMethod;
import tr.edu.siirt.fistik.alan.PriceStatus;
import tr.edu.siirt.fistik.alan.ProducerType;
import tr.edu.siirt.fistik.alan.SaleCollection;
import tr.edu.siirt.fistik.alan.ProductType;
import tr.edu.siirt.fistik.alan.WeightSource;
import tr.edu.siirt.fistik.api.Istekler.*;
import tr.edu.siirt.fistik.api.Sevkiyatlar.SevkiyatGirisi;
import tr.edu.siirt.fistik.api.Yanitlar.TartimYaniti;
import tr.edu.siirt.fistik.api.Yanitlar.UreticiYaniti;
import tr.edu.siirt.fistik.kural.Dogrulama;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sunum için örnek veri: 8 üretici, son 5 haftanın sezonu (grafikli raporlar için: değişen günlük fiyatlar, teslimatlar,
 * ödemeler, sevkiyatlar) ve bugün için 6 teslimat, 1 emanet, 1 avans, 1 ödeme ve 2 sevkiyat.
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
    private final TransactionTemplate tx;

    public DemoVerisi(@Value("${fistik.demo-verisi:false}") boolean etkin, EntityManager em, UreticiServisi ureticiler,
                      FiyatServisi fiyatlar, FinansServisi finans, TartimServisi tartimlar, SevkiyatServisi sevkiyatlar,
                      TransactionTemplate tx) {
        this.etkin = etkin;
        this.em = em;
        this.ureticiler = ureticiler;
        this.fiyatlar = fiyatlar;
        this.finans = finans;
        this.tartimlar = tartimlar;
        this.sevkiyatlar = sevkiyatlar;
        this.tx = tx;
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
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000003048", null, null, "Hüseyin Aydın", "0535 210 30 40", null, null,
                "Baykan", null, null, null)));
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000004038", null, null, "Zeynep Arslan", "0536 320 40 50", null, null,
                "Eruh", null, null, null)));
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000005028", null, null, "Ramazan Çelik", "0537 430 50 60", null, null,
                "Şirvan", null, null, null)));
        u.add(ureticiler.olustur(new UreticiGirisi(null, "10000006018", null, null, "Emine Koç", "0538 540 60 70", null, null,
                "Kurtalan", null, null, null)));

        // Kuru-kavlak: çatlak primi %85 baz, her puan 2 ₺/kg (en fazla ±20); boş tane %3 üzeri her puan −5 ₺/kg
        fiyatlar.catlakPrimiGuncelle(ProductType.DRY, new CatlakPrimiGirisi(d("85"), d("2"), d("20"), true, d("3"), d("5"), true), k);
        gecmisSezon(u, k);
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
                    d((String) t[7]), d((String) t[8])), null, null, null, null, null, null, null, null, null), k);
            if (ilk == null) ilk = y;
        }
        // Emanet: Fatma Demir 1.200 kg'ı satmadan emanete bırakıyor (numune: 100 g'da 89 g çatlak, 2 g boş)
        tartimlar.kaydet(new TartimGirisi(u.get(1).id(), "56 DE 456", null, d("4400"), d("3200"),
                new KaliteAnalizi(d("100"), null, d("57"), null, d("1"), d("6"), d("89"), d("2")), null, null, null,
                null, null, null, null, null, true), k);
        finans.odemeYap(ilk.hakedis().id(), new OdemeGirisi(d("150000"), null, null, "EFT-DEMO-1", null), k);

        // Satış: A sınıfı siloyu fabrikaya, B sınıfının bir kısmını toptancıya (vadeli) sevk et
        sevkiyatlar.kaydet(new SevkiyatGirisi("Antep Fıstıkçılık A.Ş.", null, "27 AF 270", "Hasan Demir", silo("S-A1"),
                d("11700"), d("8200"), d("420"), SaleCollection.BANK_TRANSFER, WeightSource.SCALE, WeightSource.SCALE,
                "Kırma tesisi siparişi"), k);
        sevkiyatlar.kaydet(new SevkiyatGirisi("Gaziantep Kuruyemiş Toptan", null, "27 GK 404", "Ali Kaya", silo("S-B1"),
                d("3500"), d("3100"), d("360"), SaleCollection.ON_ACCOUNT, WeightSource.SCALE, WeightSource.SCALE,
                "30 gün vadeli"), k);
        log.info("Demo verisi: 8 üretici, geçmiş sezon ve bugün için 6 teslimat, 1 emanet, 1 avans, 1 ödeme ve 2 sevkiyat eklendi.");
    }

    // ─── Geçmiş sezon (grafikli raporlar için) ───

    private static final int GECMIS_GUN = 35;
    /** Kalite profilleri: çatlak, randıman, boş, çürük, nem (ortalama) ve yayılma. */
    private static final double[][] PROFIL = {
            {90, 57.5, 2, 1, 6.2}, {87, 55.5, 3, 1, 6.8}, {80, 52, 5, 1.5, 7.5}, {86, 55, 3.5, 1, 7}, {72, 47, 8, 3, 9}};
    private static final int[] URETICI_PROFILI = {0, 1, 4, 3, 1, 0, 2, 3};
    private static final String[] PLAKA = {"56 ABC 123", "56 DE 456", "56 FG 789", "56 BT 001", "56 HA 304", "56 ZA 403",
            "56 RC 502", "56 EK 601"};

    /** Geçmiş günde fiyat dönemi: [başlangıç günü, A fiyatı]. Diğer sınıflar A'ya göre hesaplanır. */
    private static final int[][] FIYAT_DONEMLERI = {{35, 268}, {30, 272}, {25, 278}, {20, 285}, {15, 282}, {10, 290}, {5, 295}};

    private static BigDecimal yuvarla(double x) {
        return BigDecimal.valueOf(x).setScale(0, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime an(LocalDate gun, int saat, int dakika) {
        return gun.atTime(saat, dakika).atOffset(ZoneOffset.ofHours(3));
    }

    /**
     * Son 5 haftayı "o günün saatiyle" gerçek servislerden geçirerek üretir: fiyat kilidi, kalite sınıfı, silo ve kesinti
     * kuralları aynen uygulanır. Fiyat dönemleri kapalı aralıklar olarak eklenir (onaylı fiyatın bitişi sonradan geçmişe
     * çekilemediği için). Sezon sonunda silolar sevk edilerek boşaltılır; bugünkü demo verisi bundan etkilenmez.
     */
    private void gecmisSezon(List<UreticiYaniti> u, Long k) {
        LocalDate bugun = Dogrulama.bugunTr();
        Map<String, Long> sinif = new LinkedHashMap<>();
        em.createQuery("select g.code, g.id from QualityGrade g", Object[].class).getResultList().stream()
                .forEach(r -> sinif.put((String) r[0], (Long) r[1]));
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < FIYAT_DONEMLERI.length; i++) {
                LocalDate bas = bugun.minusDays(FIYAT_DONEMLERI[i][0]);
                OffsetDateTime son = i + 1 < FIYAT_DONEMLERI.length ? an(bugun.minusDays(FIYAT_DONEMLERI[i + 1][0]), 8, 0) : an(bugun, 0, 0);
                double a = FIYAT_DONEMLERI[i][1];
                double yas = 0.45 * a;   // yaş fıstık, kurutma firesi nedeniyle kuru-kavlağın yaklaşık %45'i
                Map<String, Double> f = Map.of("A", a, "B", a - 40, "ISK", a / 2, "YA", yas, "YB", yas - 20, "YISK", yas / 2);
                f.forEach((kod, fiyat) -> {
                    if (!sinif.containsKey(kod)) return;
                    DailyPrice p = new DailyPrice();
                    p.setQualityGradeId(sinif.get(kod));
                    p.setPricePerKg(yuvarla(fiyat));
                    p.setCurrency("TRY");
                    p.setValidFrom(an(bas, 8, 0).withOffsetSameInstant(ZoneOffset.UTC));
                    p.setValidTo(son.withOffsetSameInstant(ZoneOffset.UTC));
                    p.setStatus(PriceStatus.APPROVED);
                    p.setCreatedById(k);
                    p.setApprovedById(k);
                    p.setApprovedAt(an(bas, 8, 0).withOffsetSameInstant(ZoneOffset.UTC));
                    p.setNote("Demo: geçmiş sezon fiyatı");
                    em.persist(p);
                });
            }
        });

        Random r = new Random(2026);
        List<Long> odenecek = new ArrayList<>();       // [hakediş id, ödeme günü (epochDay)]
        List<Long> odemeGunu = new ArrayList<>();
        int fis = 0;
        for (int g = GECMIS_GUN; g >= 1; g--) {
            LocalDate gun = bugun.minusDays(g);
            if (gun.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            boolean yasDonemi = g > 21;                 // sezon başı: yaş fıstık da alınır
            int adet = 2 + r.nextInt(gun.getDayOfWeek() == DayOfWeek.SATURDAY ? 2 : 4);
            for (int j = 0; j < adet; j++) {
                int ui = r.nextInt(u.size());
                double[] pr = PROFIL[URETICI_PROFILI[ui]];
                boolean yas = yasDonemi && r.nextInt(3) == 0;
                double net = (yas ? 600 : 500) + r.nextInt(ui == 3 ? 2600 : 1500);
                double dara = 3200 + r.nextInt(900);
                KaliteAnalizi kalite = new KaliteAnalizi(null,
                        BigDecimal.valueOf(Math.min(98, pr[0] + r.nextGaussian() * 3)).setScale(1, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(Math.min(62, pr[1] + r.nextGaussian() * 1.6)).setScale(1, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(Math.max(0.5, pr[2] + r.nextGaussian())).setScale(1, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(Math.max(0, pr[3] + r.nextGaussian() * .4)).setScale(1, RoundingMode.HALF_UP),
                        BigDecimal.valueOf((yas ? 28 : pr[4]) + r.nextGaussian() * .5).setScale(1, RoundingMode.HALF_UP));
                OffsetDateTime saat = an(gun, 9 + j * 2, r.nextInt(50));
                TartimGirisi giris = new TartimGirisi(u.get(ui).id(), PLAKA[ui], null, yuvarla(dara + net), yuvarla(dara), kalite,
                        null, null, null, yas ? ProductType.FRESH : ProductType.DRY, null, null,
                        WeightSource.SCALE, WeightSource.SCALE, null);
                TartimYaniti y;
                try {
                    y = Dogrulama.saatiSabitle(saat, () -> tartimlar.kaydet(giris, k));
                } catch (tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi e) {   // uygun silo dolu: önce sevk et
                    siloBosalt(gun, 0, k);
                    y = Dogrulama.saatiSabitle(saat, () -> tartimlar.kaydet(giris, k));
                }
                fis++;
                if (y.hakedis() != null && g > 3) {      // son günlerin bir kısmı henüz ödenmemiş kalır
                    odenecek.add(y.hakedis().id());
                    odemeGunu.add(gun.plusDays(g > 10 ? 2 : 3).toEpochDay());
                }
            }
            // Günün sonunda vadesi gelen hakedişler ödenir; dolan silolar sevk edilir
            OffsetDateTime aksam = an(gun, 18, 0);
            for (int i = 0; i < odenecek.size(); i++) {
                if (odemeGunu.get(i) > gun.toEpochDay()) continue;
                Long h = odenecek.get(i);
                Dogrulama.saatiSabitle(aksam, () -> finans.odemeYap(h, new OdemeGirisi(null, PaymentMethod.CASH, null, null,
                        "Demo: geçmiş sezon ödemesi"), k));
                odenecek.remove(i);
                odemeGunu.remove(i);
                i--;
            }
            siloBosalt(gun, g == 1 ? 0 : 40, k);
        }
        log.info("Demo verisi: geçmiş sezon ({} gün, {} teslimat) eklendi.", GECMIS_GUN, fis);
    }

    private static final String[][] ALICILAR = {{"Antep Fıstıkçılık A.Ş.", "27 AF 270"}, {"Gaziantep Kuruyemiş Toptan", "27 GK 404"},
            {"Siirt Kuruyemiş Ltd. Şti.", "56 SK 056"}};

    /** Doluluğu yüzde eşiği aşan siloları (eşik 0 ise stoğu olan hepsini) o günün akşamı alıcılara sevk eder. */
    private void siloBosalt(LocalDate gun, int esikYuzde, Long k) {
        List<Object[]> stoklar = tx.execute(s -> em.createQuery("""
                        select m.siloId, sum(m.quantityKg), si.code, si.capacityKg from StockMovement m, Silo si where m.siloId = si.id
                        group by m.siloId, si.code, si.capacityKg order by si.code""", Object[].class).getResultList());
        int i = 0;
        for (Object[] st : stoklar) {
            BigDecimal kg = ((BigDecimal) st[1]).setScale(0, RoundingMode.DOWN);
            BigDecimal esik = ((BigDecimal) st[3]).multiply(BigDecimal.valueOf(esikYuzde)).divide(BigDecimal.valueOf(100));
            if (kg.signum() <= 0 || kg.compareTo(esik) < 0) continue;
            String kod = (String) st[2];
            double alis = kod.startsWith("S-Y") ? 140 : kod.equals("S-ISK") ? 150 : kod.equals("S-B1") ? 255 : 290;
            String[] alici = ALICILAR[(int) ((gun.toEpochDay() + i++) % ALICILAR.length)];
            BigDecimal dara = BigDecimal.valueOf(8000);
            SevkiyatGirisi g = new SevkiyatGirisi(alici[0], null, alici[1], null, (Long) st[0], dara.add(kg), dara,
                    yuvarla(alis * 1.38), i % 3 == 0 ? SaleCollection.ON_ACCOUNT : SaleCollection.BANK_TRANSFER,
                    WeightSource.SCALE, WeightSource.SCALE, "Demo: geçmiş sezon sevkiyatı");
            Dogrulama.saatiSabitle(an(gun, 19, 0), () -> sevkiyatlar.kaydet(g, k));
        }
    }
}
