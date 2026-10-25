package tr.edu.siirt.fistik.kural;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import tr.edu.siirt.fistik.alan.*;
import tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static tr.edu.siirt.fistik.kural.Dogrulama.para;

/**
 * Temel iş kuralları — HTTP'den bağımsız alan katmanı.
 *
 * <p>Her yöntem açık transaction içinde çağrılır (servis katmanı {@code @Transactional}); değişiklikleri
 * {@code flush} eder ama commit etmez. Para hesapları {@link BigDecimal} ve kuruşa yuvarlamayla yapılır.
 *
 * <p>Eşzamanlılık: hakediş ve ödeme, üretici satırını {@code SELECT ... FOR UPDATE} ile kilitler; aynı
 * üreticiye paralel iki işlem avansı iki kez mahsup edemez veya bakiyeyi aşan ödeme yapamaz.
 */
@Component
public class Kurallar {

    private static final DateTimeFormatter TARIH_SAAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final EntityManager em;

    public Kurallar(EntityManager em) {
        this.em = em;
    }

    // ───────────────────────────── Belge numarası ─────────────────────────────

    /** KF-2026-000001 biçiminde yıl bazlı, kilitli sayaçtan benzersiz ve boşluksuz numara. */
    public String sonrakiBelgeNo(String tur, OffsetDateTime an) {
        int yil = an.getYear();
        DocumentSequence sayac = em.find(DocumentSequence.class, new DocumentSequence.Anahtar(tur, yil),
                LockModeType.PESSIMISTIC_WRITE);
        if (sayac == null) {
            sayac = new DocumentSequence();
            sayac.setDocType(tur);
            sayac.setYear(yil);
            sayac.setLastValue(0);
            em.persist(sayac);
        }
        sayac.setLastValue(sayac.getLastValue() + 1);
        em.flush();
        return "%s-%d-%06d".formatted(tur, yil, sayac.getLastValue());
    }

    public Producer ureticiKilitle(Long ureticiId) {
        Producer p = em.find(Producer.class, ureticiId, LockModeType.PESSIMISTIC_WRITE);
        if (p == null) throw new IsKuraliHatasi("Üretici bulunamadı.");
        return p;
    }

    private ProducerLedgerEntry cariHareket(Long ureticiId, LedgerEntryType tur, BigDecimal tutar, OffsetDateTime an,
                                            Long kullaniciId, String aciklama) {
        ProducerLedgerEntry e = new ProducerLedgerEntry();
        e.setProducerId(ureticiId);
        e.setEntryType(tur);
        e.setAmount(para(tutar));
        e.setEntryAt(an);
        e.setCreatedById(kullaniciId);
        e.setDescription(aciklama);
        return e;
    }

    // ───────────────────────────── Fiyat ─────────────────────────────

    /**
     * Taslak fiyatı onaylar. Aynı sınıfın açık uçlu önceki fiyatı, yeni fiyatın başlangıcında kapatılır.
     * Geçmişe dönük (onay anından önce başlayan) fiyat onaylanamaz → geçmiş tartımların fiyatı değişmez.
     */
    public DailyPrice fiyatOnayla(DailyPrice fiyat, Long onaylayanId, OffsetDateTime an) {
        if (fiyat.getStatus() != PriceStatus.DRAFT) throw new IsKuraliHatasi("Yalnızca taslak fiyat onaylanabilir.");
        if (fiyat.getValidFrom().isBefore(an))
            throw new IsKuraliHatasi("Fiyat geçerlilik başlangıcı onay anından önce olamaz.");
        boolean dahaIleri = !em.createQuery("""
                        select p.id from DailyPrice p where p.qualityGradeId = :g and p.status = :onayli
                        and p.validFrom >= :bas""", Long.class)
                .setParameter("g", fiyat.getQualityGradeId()).setParameter("onayli", PriceStatus.APPROVED)
                .setParameter("bas", fiyat.getValidFrom()).setMaxResults(1).getResultList().isEmpty();
        if (dahaIleri)
            throw new IsKuraliHatasi("Bu kalite sınıfı için aynı veya daha ileri tarihli onaylı bir fiyat zaten var.");

        List<DailyPrice> onceki = em.createQuery("""
                        select p from DailyPrice p where p.qualityGradeId = :g and p.status = :onayli
                        and p.validFrom < :bas and (p.validTo is null or p.validTo > :bas)""", DailyPrice.class)
                .setParameter("g", fiyat.getQualityGradeId()).setParameter("onayli", PriceStatus.APPROVED)
                .setParameter("bas", fiyat.getValidFrom()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (DailyPrice p : onceki) p.setValidTo(fiyat.getValidFrom());   // [from, to) — yeni fiyat başlayınca eski biter
        // Önce eski fiyatı kapat, SONRA yenisini onayla: aksi halde iki onaylı fiyat bir an çakışır ve
        // PostgreSQL'in EXCLUDE kısıtı (ex_daily_prices_no_overlap) güncellemeyi reddeder.
        em.flush();

        fiyat.setStatus(PriceStatus.APPROVED);
        fiyat.setApprovedById(onaylayanId);
        fiyat.setApprovedAt(an);
        em.flush();
        return fiyat;
    }

    /**
     * {@code an} anında GEÇERLİ ve O ANDA ONAYLI fiyat.
     * <ul>
     *   <li>valid_from ≤ an &lt; valid_to</li>
     *   <li>approved_at ≤ an → sonradan onaylanan fiyat geçmiş tartıma uygulanmaz</li>
     *   <li>revoked_at boş veya &gt; an → sonradan iptal edilen fiyat, iptalden önceki tartımlar için geçerli kalır</li>
     * </ul>
     */
    public DailyPrice gecerliFiyat(Long sinifId, OffsetDateTime an) {
        return em.createQuery("""
                        select p from DailyPrice p where p.qualityGradeId = :g and p.status in :durumlar
                        and p.validFrom <= :an and (p.validTo is null or p.validTo > :an) and p.approvedAt <= :an
                        and (p.revokedAt is null or p.revokedAt > :an) order by p.validFrom desc, p.id desc""",
                        DailyPrice.class)
                .setParameter("g", sinifId).setParameter("durumlar", List.of(PriceStatus.APPROVED, PriceStatus.REVOKED))
                .setParameter("an", an).setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    // ───────────────────────────── Kantar ─────────────────────────────

    /** Dolu (giriş) tartım. Aynı üretici aynı gün birden çok kez gelebilir; her seferinde yeni fiş. */
    public WeighTicket tartimOlustur(Long ureticiId, String plaka, BigDecimal brutKg, Long tartanId,
                                     OffsetDateTime an, String soforAdi) {
        Producer p = em.find(Producer.class, ureticiId);
        if (p == null || !p.isActive()) throw new IsKuraliHatasi("Üretici bulunamadı veya pasif.");
        if (brutKg.signum() <= 0) throw new IsKuraliHatasi("Brüt ağırlık pozitif olmalı.");
        WeighTicket t = new WeighTicket();
        t.setTicketNo(sonrakiBelgeNo("KF", an));
        t.setProducerId(ureticiId);
        t.setVehiclePlate(Dogrulama.plakaNormalize(plaka));
        t.setDriverName(soforAdi);
        t.setGrossKg(brutKg);
        t.setGrossWeighedAt(an);
        t.setWeighedById(tartanId);
        t.setStatus(TicketStatus.GROSS_WEIGHED);
        em.persist(t);
        em.flush();
        return t;
    }

    /** Boş (dara) tartım → net kg = brüt − dara (veritabanında hesaplanan sütun). */
    public void daraKaydet(WeighTicket t, BigDecimal daraKg, OffsetDateTime an) {
        if (t.getStatus() != TicketStatus.GROSS_WEIGHED)
            throw new IsKuraliHatasi("Dara yalnızca brüt tartımı yapılmış fişe girilebilir.");
        if (daraKg.signum() < 0 || daraKg.compareTo(t.getGrossKg()) >= 0)
            throw new IsKuraliHatasi("Dara 0 ile brüt ağırlık arasında olmalı.");
        t.setTareKg(daraKg);
        t.setTareWeighedAt(an);
        t.setStatus(TicketStatus.NET_WEIGHED);
        em.flush();
    }

    public static BigDecimal netKg(WeighTicket t) {
        return t.getGrossKg().subtract(t.getTareKg());
    }

    // ───────────────────────────── Kalite & fiyat snapshot ─────────────────────────────

    /** Ürün türünün sınıfları öncelik sırasıyla denenir; eşiklerin hepsini sağlayan ilk sınıf seçilir. */
    public QualityGrade sinifBelirle(ProductType tur, BigDecimal catlak, BigDecimal randiman, BigDecimal bos,
                                     BigDecimal curuk, BigDecimal nem) {
        BigDecimal kusur = bos.add(curuk);
        List<QualityGrade> siniflar = em.createQuery(
                        "select g from QualityGrade g where g.active = true and g.productType = :t order by g.priority", QualityGrade.class)
                .setParameter("t", tur).getResultList();
        for (QualityGrade g : siniflar) {
            if (g.getMinSplitRatio() != null && catlak.compareTo(g.getMinSplitRatio()) < 0) continue;
            if (g.getMinYieldRatio() != null && randiman.compareTo(g.getMinYieldRatio()) < 0) continue;
            if (g.getMaxDefectRatio() != null && kusur.compareTo(g.getMaxDefectRatio()) > 0) continue;
            if (g.getMaxMoistureRatio() != null && nem.compareTo(g.getMaxMoistureRatio()) > 0) continue;
            return g;
        }
        throw new IsKuraliHatasi("%s için hiçbir kalite sınıfına uymuyor; eşiksiz bir son sınıf tanımlayın.".formatted(tur.getAd()));
    }

    /**
     * Çatlak primi (eksi ise iskonto), ₺/kg: (çatlak % − baz %) × puan başı ₺, en fazla ± azami fark.
     * Kural kapalıysa 0.
     */
    public BigDecimal catlakPrimi(ProductType tur, BigDecimal catlak) {
        SplitPremiumRule k = em.find(SplitPremiumRule.class, tur);
        if (k == null || !k.isActive() || catlak == null) return BigDecimal.ZERO.setScale(2);
        BigDecimal prim = catlak.subtract(k.getBaseSplitRatio()).multiply(k.getPremiumPerPoint());
        if (k.getMaxAdjustment() != null) prim = prim.max(k.getMaxAdjustment().negate()).min(k.getMaxAdjustment());
        return para(prim);
    }

    /**
     * Fiyat kilidi (SNAPSHOT): fiyatAni'nda geçerli onaylı taban fiyat + çatlak primi. Bir kez yazılır;
     * veritabanı tetikleyicisi sonradan değiştirilmesine izin vermez.
     */
    public void fiyatKilitle(WeighTicket t, QualityGrade sinif, BigDecimal catlak, OffsetDateTime fiyatAni,
                             OffsetDateTime an, String neZaman) {
        DailyPrice fiyat = gecerliFiyat(sinif.getId(), fiyatAni);
        if (fiyat == null) {
            throw new IsKuraliHatasi("%s sınıfı için %s (%s) onaylı fiyat yok."
                    .formatted(sinif.getCode(), neZaman, fiyatAni.withOffsetSameInstant(Dogrulama.TR).format(TARIH_SAAT)));
        }
        BigDecimal prim = catlakPrimi(t.getProductType(), catlak);
        BigDecimal birim = fiyat.getPricePerKg().add(prim);
        if (birim.signum() <= 0) throw new IsKuraliHatasi("Çatlak iskontosu sonrası birim fiyat 0 veya altına düşüyor.");
        t.setDailyPriceId(fiyat.getId());        // ← SNAPSHOT (fiyat kilidi)
        t.setBaseUnitPrice(fiyat.getPricePerKg());
        t.setSplitPremium(prim);
        t.setUnitPrice(birim);
        t.setPricedAt(an);
    }

    /**
     * Emaneti satışa çevir: o günün (an) fiyatı ve çatlak primiyle fiyat kilitlenir, hakediş normal akışla oluşur
     * (kesintiler, açık avans mahsubu, cari hesap). Fiş yeniden tartılmaz; lot ve stok hareketleri değişmez.
     */
    public Settlement emanetiSatisaCevir(WeighTicket t, Long kullaniciId, OffsetDateTime an) {
        if (t.getStatus() != TicketStatus.DEPOSITED)
            throw new IsKuraliHatasi(t.getSaleMode() == SaleMode.DEPOSIT
                    ? "%s zaten satışa çevrilmiş.".formatted(t.getTicketNo())
                    : "%s emanet fişi değil.".formatted(t.getTicketNo()));
        ureticiKilitle(t.getProducerId());
        QualityInspection q = em.createQuery("select q from QualityInspection q where q.ticketId = :t", QualityInspection.class)
                .setParameter("t", t.getId()).getSingleResult();
        fiyatKilitle(t, em.find(QualityGrade.class, q.getQualityGradeId()), q.getSplitRatio(), an, an, "bugün");
        t.setDepositConvertedAt(an);
        t.setStatus(TicketStatus.GRADED);
        em.flush();
        return hakedisOlustur(t, kullaniciId, an);
    }

    /** Kalite analizini kaydeder, sınıfı belirler, TARTIM ANINDAKİ fiyatı dondurur ve lotu açar. */
    public QualityInspection kaliteAnaliziKaydet(WeighTicket t, BigDecimal numuneGram, BigDecimal catlak,
                                                 BigDecimal randiman, BigDecimal bos, BigDecimal curuk, BigDecimal nem,
                                                 Long analizEdenId, OffsetDateTime an, Long manuelSinifId,
                                                 String manuelGerekce) {
        return kaliteAnaliziKaydet(t, numuneGram, catlak, randiman, bos, curuk, nem, analizEdenId, an, manuelSinifId,
                manuelGerekce, false);
    }

    /**
     * Emanet ise fiyat kilitlenmez: fiş DEPOSITED olur, lot açılır ve siloya girer; fiyat ve hakediş
     * {@link #emanetiSatisaCevir} ile o günün fiyatından oluşur.
     */
    public QualityInspection kaliteAnaliziKaydet(WeighTicket t, BigDecimal numuneGram, BigDecimal catlak,
                                                 BigDecimal randiman, BigDecimal bos, BigDecimal curuk, BigDecimal nem,
                                                 Long analizEdenId, OffsetDateTime an, Long manuelSinifId,
                                                 String manuelGerekce, boolean emanet) {
        if (t.getStatus() != TicketStatus.NET_WEIGHED)
            throw new IsKuraliHatasi("Kalite analizi için önce dara tartımı tamamlanmalı.");
        QualityGrade sinif;
        if (manuelSinifId != null) {
            if (manuelGerekce == null || manuelGerekce.isBlank())
                throw new IsKuraliHatasi("Manuel sınıf ataması gerekçe gerektirir.");
            sinif = em.find(QualityGrade.class, manuelSinifId);
            if (sinif.getProductType() != t.getProductType())
                throw new IsKuraliHatasi("%s sınıfı %s içindir; bu teslimat %s.".formatted(
                        sinif.getCode(), sinif.getProductType().getAd(), t.getProductType().getAd()));
        } else {
            sinif = sinifBelirle(t.getProductType(), catlak, randiman, bos, curuk, nem);
        }

        // Hemen satışta referans an: tartım anı. Analiz saatler sonra yapılsa da fiyat tartım anına aittir.
        if (!emanet) fiyatKilitle(t, sinif, catlak, t.getGrossWeighedAt(), an, "tartım anında");

        QualityInspection q = new QualityInspection();
        q.setTicketId(t.getId());
        q.setSampleWeightG(numuneGram);
        q.setSplitRatio(catlak);
        q.setYieldRatio(randiman);
        q.setEmptyRatio(bos);
        q.setRottenRatio(curuk);
        q.setMoistureRatio(nem);
        q.setQualityGradeId(sinif.getId());
        q.setManualOverride(manuelSinifId != null);
        q.setOverrideReason(manuelGerekce);
        q.setInspectedById(analizEdenId);
        q.setInspectedAt(an);
        em.persist(q);

        t.setSaleMode(emanet ? SaleMode.DEPOSIT : SaleMode.SALE);
        t.setStatus(emanet ? TicketStatus.DEPOSITED : TicketStatus.GRADED);

        Lot lot = new Lot();
        lot.setLotNo(sonrakiBelgeNo("LOT", t.getGrossWeighedAt()));
        lot.setTicketId(t.getId());
        lot.setQualityGradeId(sinif.getId());
        lot.setInitialKg(netKg(t));
        em.persist(lot);
        em.flush();
        return q;
    }

    // ───────────────────────────── Avans ─────────────────────────────

    public Advance avansVer(Long ureticiId, BigDecimal tutar, PaymentMethod yontem, Long kullaniciId,
                            OffsetDateTime an, Long bankaHesapId, String aciklama) {
        ureticiKilitle(ureticiId);
        tutar = para(tutar);
        if (tutar.signum() <= 0) throw new IsKuraliHatasi("Avans tutarı pozitif olmalı.");
        if (yontem == PaymentMethod.BANK_TRANSFER) bankaHesapId = bankaHesabiBul(ureticiId, bankaHesapId);
        Advance a = new Advance();
        a.setAdvanceNo(sonrakiBelgeNo("AV", an));
        a.setProducerId(ureticiId);
        a.setAmount(tutar);
        a.setOffsetAmount(BigDecimal.ZERO);
        a.setStatus(AdvanceStatus.OPEN);
        a.setGivenAt(an);
        a.setMethod(yontem);
        a.setBankAccountId(bankaHesapId);
        a.setDescription(aciklama);
        a.setCreatedById(kullaniciId);
        em.persist(a);
        em.flush();
        // Avans verildiği anda üretici borçlanır → bakiye eksiye düşebilir.
        ProducerLedgerEntry e = cariHareket(ureticiId, LedgerEntryType.ADVANCE, tutar.negate(), an, kullaniciId,
                "Avans " + a.getAdvanceNo());
        e.setAdvanceId(a.getId());
        em.persist(e);
        em.flush();
        return a;
    }

    private static void avansDurumuGuncelle(Advance a) {
        if (a.getOffsetAmount().signum() == 0) a.setStatus(AdvanceStatus.OPEN);
        else if (a.getOffsetAmount().compareTo(a.getAmount()) < 0) a.setStatus(AdvanceStatus.PARTIALLY_OFFSET);
        else a.setStatus(AdvanceStatus.CLOSED);
    }

    // ───────────────────────────── Hakediş ─────────────────────────────

    /** Kesinti tutarı: [matrah, tutar]. */
    public static BigDecimal[] kesintiTutari(DeductionType dt, BigDecimal brut, BigDecimal netKg) {
        return switch (dt.getCalcMethod()) {
            case PERCENT -> new BigDecimal[]{brut, para(brut.multiply(dt.getRate()).divide(BigDecimal.valueOf(100)))};
            case PER_KG -> new BigDecimal[]{netKg, para(netKg.multiply(dt.getRate()))};
            case FIXED -> new BigDecimal[]{brut, para(dt.getRate())};
        };
    }

    record Mahsup(Advance avans, BigDecimal tutar) {
    }

    /** Açık avanslardan en eskiden başlayarak (FIFO) en fazla {@code limit} kadar mahsup planı. */
    private List<Mahsup> avansMahsupPlani(Long ureticiId, BigDecimal limit) {
        List<Advance> acik = em.createQuery("""
                        select a from Advance a where a.producerId = :u and a.offsetAmount < a.amount
                        order by a.givenAt, a.id""", Advance.class)
                .setParameter("u", ureticiId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        List<Mahsup> plan = new ArrayList<>();
        BigDecimal kalan = limit;
        for (Advance a : acik) {
            if (kalan.signum() <= 0) break;
            BigDecimal al = a.getAmount().subtract(a.getOffsetAmount()).min(kalan);
            plan.add(new Mahsup(a, al));
            kalan = kalan.subtract(al);
        }
        return plan;
    }

    private void mahsupUygula(Settlement s, List<Mahsup> plan) {
        for (Mahsup m : plan) {
            AdvanceOffset mevcut = em.createQuery(
                            "select o from AdvanceOffset o where o.advanceId = :a and o.settlementId = :s", AdvanceOffset.class)
                    .setParameter("a", m.avans().getId()).setParameter("s", s.getId())
                    .getResultStream().findFirst().orElse(null);
            if (mevcut == null) {
                AdvanceOffset o = new AdvanceOffset();
                o.setAdvanceId(m.avans().getId());
                o.setSettlementId(s.getId());
                o.setAmount(m.tutar());
                em.persist(o);
            } else {
                mevcut.setAmount(mevcut.getAmount().add(m.tutar()));
            }
            m.avans().setOffsetAmount(m.avans().getOffsetAmount().add(m.tutar()));
            avansDurumuGuncelle(m.avans());
        }
    }

    private static BigDecimal toplam(List<Mahsup> plan) {
        return plan.stream().map(Mahsup::tutar).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Hakediş oluştuktan SONRA verilmiş avansları, hakedişin ödenmemiş kısmından mahsup eder.
     * Brüt ve kesintiler değişmez; yalnızca avans mahsubu artar, net ödenecek aynı miktarda azalır.
     */
    public BigDecimal hakediseSonradanAvansMahsupEt(Settlement s) {
        BigDecimal odenmemis = s.getNetPayable().subtract(s.getPaidAmount());
        if (odenmemis.signum() <= 0) return BigDecimal.ZERO;
        List<Mahsup> plan = avansMahsupPlani(s.getProducerId(), odenmemis);
        BigDecimal t = toplam(plan);
        if (t.signum() > 0) {
            mahsupUygula(s, plan);
            s.setAdvanceOffsetTotal(s.getAdvanceOffsetTotal().add(t));
            s.setNetPayable(s.getNetPayable().subtract(t));
            hakedisDurumuGuncelle(s);
            em.flush();
        }
        return t;
    }

    /**
     * Net hakediş = (Net kg × Dondurulmuş fiyat) − Kesintiler − Avans mahsubu.
     * Avans mahsubu FIFO ve kesinti sonrası tutarla sınırlıdır; kapanmayan avans açık kalır, üretici bakiyesi
     * eksidedir ve sonraki hakedişlerde otomatik mahsup edilir.
     */
    public Settlement hakedisOlustur(WeighTicket t, Long kullaniciId, OffsetDateTime an) {
        if (t.getStatus() != TicketStatus.GRADED)
            throw new IsKuraliHatasi("Hakediş için teslimatın kalite/fiyat aşaması tamamlanmalı.");
        ureticiKilitle(t.getProducerId());

        BigDecimal netKg = netKg(t);
        BigDecimal brut = para(netKg.multiply(t.getUnitPrice()));

        // 1) Kesintiler (oran snapshot'ı ile)
        List<SettlementDeduction> kesintiler = new ArrayList<>();
        for (DeductionType dt : em.createQuery(
                "select d from DeductionType d where d.active = true order by d.sortOrder", DeductionType.class).getResultList()) {
            BigDecimal[] mt = kesintiTutari(dt, brut, netKg);
            SettlementDeduction sd = new SettlementDeduction();
            sd.setDeductionTypeId(dt.getId());
            sd.setCalcMethod(dt.getCalcMethod());
            sd.setRate(dt.getRate());
            sd.setBaseAmount(mt[0]);
            sd.setAmount(mt[1]);
            kesintiler.add(sd);
        }
        BigDecimal kesintiToplami = kesintiler.stream().map(SettlementDeduction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (kesintiToplami.compareTo(brut) > 0)
            throw new IsKuraliHatasi("Kesintiler toplamı brüt tutarı aşıyor; kesinti tanımlarını kontrol edin.");

        // 2) Açık avansların FIFO mahsubu
        BigDecimal kullanilabilir = brut.subtract(kesintiToplami);
        List<Mahsup> plan = avansMahsupPlani(t.getProducerId(), kullanilabilir);
        BigDecimal mahsup = toplam(plan);
        BigDecimal netOdenecek = kullanilabilir.subtract(mahsup);

        Settlement s = new Settlement();
        s.setSettlementNo(sonrakiBelgeNo("HK", an));
        s.setTicketId(t.getId());
        s.setProducerId(t.getProducerId());
        s.setNetKg(netKg);
        s.setUnitPrice(t.getUnitPrice());
        s.setGrossAmount(brut);
        s.setDeductionsTotal(kesintiToplami);
        s.setAdvanceOffsetTotal(mahsup);
        s.setNetPayable(netOdenecek);
        s.setPaidAmount(BigDecimal.ZERO);
        s.setStatus(netOdenecek.signum() == 0 ? SettlementStatus.OFFSET_ONLY : SettlementStatus.UNPAID);
        s.setCreatedById(kullaniciId);
        em.persist(s);
        em.flush();
        for (SettlementDeduction sd : kesintiler) {
            sd.setSettlementId(s.getId());
            em.persist(sd);
        }
        em.flush();

        mahsupUygula(s, plan);

        // 3) Cari hesap: + brüt, − her kesinti. (Avans verildiği anda zaten borç yazıldı; tekrar yazılmaz.)
        ProducerLedgerEntry teslimat = cariHareket(t.getProducerId(), LedgerEntryType.DELIVERY, brut, an, kullaniciId,
                "Teslimat " + t.getTicketNo());
        teslimat.setTicketId(t.getId());
        teslimat.setSettlementId(s.getId());
        em.persist(teslimat);
        for (SettlementDeduction sd : kesintiler) {
            if (sd.getAmount().signum() > 0) {
                ProducerLedgerEntry k = cariHareket(t.getProducerId(), LedgerEntryType.DEDUCTION, sd.getAmount().negate(),
                        an, kullaniciId, "Kesinti " + t.getTicketNo());
                k.setSettlementId(s.getId());
                k.setSettlementDeductionId(sd.getId());
                em.persist(k);
            }
        }
        t.setStatus(TicketStatus.SETTLED);
        em.flush();
        return s;
    }

    // ───────────────────────────── Ödeme ─────────────────────────────

    /** Cari bakiye: (+) şirket üreticiye borçlu, (−) üretici şirkete borçlu (kapanmamış avans). */
    public BigDecimal ureticiBakiyesi(Long ureticiId) {
        BigDecimal t = em.createQuery("select coalesce(sum(e.amount), 0) from ProducerLedgerEntry e where e.producerId = :u",
                BigDecimal.class).setParameter("u", ureticiId).getSingleResult();
        return para(t);
    }

    private Long bankaHesabiBul(Long ureticiId, Long hesapId) {
        String jpql = "select b from ProducerBankAccount b where b.producerId = :u and b.active = true and "
                + (hesapId != null ? "b.id = :h" : "b.defaultAccount = true");
        var q = em.createQuery(jpql, ProducerBankAccount.class).setParameter("u", ureticiId);
        if (hesapId != null) q.setParameter("h", hesapId);
        return q.getResultStream().findFirst().map(ProducerBankAccount::getId)
                .orElseThrow(() -> new IsKuraliHatasi("Üreticiye ait aktif banka hesabı bulunamadı."));
    }

    public static void hakedisDurumuGuncelle(Settlement s) {
        if (s.getNetPayable().signum() == 0) s.setStatus(SettlementStatus.OFFSET_ONLY);
        else if (s.getPaidAmount().signum() == 0) s.setStatus(SettlementStatus.UNPAID);
        else if (s.getPaidAmount().compareTo(s.getNetPayable()) < 0) s.setStatus(SettlementStatus.PARTIALLY_PAID);
        else s.setStatus(SettlementStatus.PAID);
    }

    /**
     * Ödemeyi en eski ödenmemiş hakedişten başlayarak dağıtır (parçalı/tam). {@code hakedisId} verilirse
     * yalnızca o hakedişe uygulanır. Ödeme ne cari bakiyeyi ne de ödenmemiş hakediş toplamını aşabilir.
     */
    public Payment odemeKaydet(Long ureticiId, BigDecimal tutar, PaymentMethod yontem, Long kullaniciId,
                               OffsetDateTime an, Long bankaHesapId, String referansNo, String aciklama, Long hakedisId) {
        ureticiKilitle(ureticiId);
        tutar = para(tutar);
        if (tutar.signum() <= 0) throw new IsKuraliHatasi("Ödeme tutarı pozitif olmalı.");

        var q = em.createQuery("select s from Settlement s where s.producerId = :u and s.paidAmount < s.netPayable"
                        + (hakedisId != null ? " and s.id = :h" : "") + " order by s.createdAt, s.id", Settlement.class)
                .setParameter("u", ureticiId).setLockMode(LockModeType.PESSIMISTIC_WRITE);
        if (hakedisId != null) q.setParameter("h", hakedisId);
        List<Settlement> odenmemis = q.getResultList();
        BigDecimal odenmemisToplam = odenmemis.stream().map(s -> s.getNetPayable().subtract(s.getPaidAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal odenebilir = odenmemisToplam.min(ureticiBakiyesi(ureticiId).max(BigDecimal.ZERO));
        if (tutar.compareTo(odenebilir) > 0)
            throw new IsKuraliHatasi("Ödenebilir tutar %s; %s ödenemez.".formatted(Dogrulama.tl(odenebilir), Dogrulama.tl(tutar)));

        if (yontem == PaymentMethod.BANK_TRANSFER) bankaHesapId = bankaHesabiBul(ureticiId, bankaHesapId);

        Payment p = new Payment();
        p.setPaymentNo(sonrakiBelgeNo("OD", an));
        p.setProducerId(ureticiId);
        p.setBankAccountId(bankaHesapId);
        p.setAmount(tutar);
        p.setMethod(yontem);
        p.setPaidAt(an);
        p.setReferenceNo(referansNo);
        p.setDescription(aciklama);
        p.setCreatedById(kullaniciId);
        em.persist(p);
        em.flush();

        BigDecimal kalan = tutar;
        for (Settlement s : odenmemis) {
            if (kalan.signum() <= 0) break;
            BigDecimal al = s.getNetPayable().subtract(s.getPaidAmount()).min(kalan);
            PaymentAllocation pa = new PaymentAllocation();
            pa.setPaymentId(p.getId());
            pa.setSettlementId(s.getId());
            pa.setAmount(al);
            em.persist(pa);
            s.setPaidAmount(s.getPaidAmount().add(al));
            hakedisDurumuGuncelle(s);
            kalan = kalan.subtract(al);
        }
        ProducerLedgerEntry e = cariHareket(ureticiId, LedgerEntryType.PAYMENT, tutar.negate(), an, kullaniciId,
                "Ödeme " + p.getPaymentNo());
        e.setPaymentId(p.getId());
        em.persist(e);
        em.flush();
        return p;
    }

    // ───────────────────────────── Depo / Stok ─────────────────────────────

    public BigDecimal siloStogu(Long siloId) {
        return em.createQuery("select coalesce(sum(m.quantityKg), 0) from StockMovement m where m.siloId = :s",
                BigDecimal.class).setParameter("s", siloId).getSingleResult();
    }

    public BigDecimal siloLotStogu(Long lotId, Long siloId) {
        return em.createQuery("select coalesce(sum(m.quantityKg), 0) from StockMovement m where m.lotId = :l and m.siloId = :s",
                BigDecimal.class).setParameter("l", lotId).setParameter("s", siloId).getSingleResult();
    }

    /** Kaliteye göre ayrıştırma + kapasite kontrolü. */
    public void siloKabulEderMi(Silo silo, Lot lot, BigDecimal miktar) {
        if (!silo.isActive()) throw new IsKuraliHatasi(silo.getCode() + " pasif.");
        if (silo.getDedicatedGradeId() != null && !silo.getDedicatedGradeId().equals(lot.getQualityGradeId()))
            throw new IsKuraliHatasi(silo.getCode() + " yalnızca kendi kalite sınıfını kabul eder.");
        // Sınıfa adanmamış silo da karışık kalite içeremez: içinde stok varsa aynı sınıf olmalı.
        boolean baskaSinif = !em.createQuery("""
                        select l.qualityGradeId from StockMovement m, Lot l where m.lotId = l.id and m.siloId = :s
                        and l.qualityGradeId <> :g group by l.qualityGradeId having sum(m.quantityKg) > 0""", Long.class)
                .setParameter("s", silo.getId()).setParameter("g", lot.getQualityGradeId())
                .setMaxResults(1).getResultList().isEmpty();
        if (baskaSinif)
            throw new IsKuraliHatasi(silo.getCode() + " içinde farklı kalite sınıfı stok var; karıştırılamaz.");
        if (siloStogu(silo.getId()).add(miktar).compareTo(silo.getCapacityKg()) > 0)
            throw new IsKuraliHatasi(silo.getCode() + " kapasitesi aşılıyor.");
    }

    /** Lotun (tamamının veya bir kısmının) siloya ilk girişi. */
    public StockMovement lotuSiloyaAl(Lot lot, Silo silo, Long kullaniciId, BigDecimal miktar, OffsetDateTime an) {
        BigDecimal yerlesen = em.createQuery("""
                        select coalesce(sum(m.quantityKg), 0) from StockMovement m where m.lotId = :l and m.movementType = :t""",
                        BigDecimal.class)
                .setParameter("l", lot.getId()).setParameter("t", StockMovementType.RECEIPT).getSingleResult();
        BigDecimal kg = miktar != null ? miktar : lot.getInitialKg().subtract(yerlesen);
        if (kg.signum() <= 0 || yerlesen.add(kg).compareTo(lot.getInitialKg()) > 0)
            throw new IsKuraliHatasi("Lotun yerleştirilmemiş miktarı aşılıyor.");
        siloKabulEderMi(silo, lot, kg);
        StockMovement m = new StockMovement();
        m.setLotId(lot.getId());
        m.setSiloId(silo.getId());
        m.setMovementType(StockMovementType.RECEIPT);
        m.setQuantityKg(kg);
        m.setMovedAt(an);
        m.setCreatedById(kullaniciId);
        em.persist(m);
        em.flush();
        return m;
    }

    /** Silolar arası transfer: TRANSFER_OUT ve TRANSFER_IN çifti, ortak transfer grubu ile. */
    public String lotTransferEt(Lot lot, Silo kaynak, Silo hedef, BigDecimal miktar, Long kullaniciId, OffsetDateTime an) {
        if (miktar.signum() <= 0 || siloLotStogu(lot.getId(), kaynak.getId()).compareTo(miktar) < 0)
            throw new IsKuraliHatasi("Kaynak siloda bu lottan yeterli stok yok.");
        siloKabulEderMi(hedef, lot, miktar);
        String grup = UUID.randomUUID().toString();
        for (boolean cikis : new boolean[]{true, false}) {
            StockMovement m = new StockMovement();
            m.setLotId(lot.getId());
            m.setSiloId(cikis ? kaynak.getId() : hedef.getId());
            m.setMovementType(cikis ? StockMovementType.TRANSFER_OUT : StockMovementType.TRANSFER_IN);
            m.setQuantityKg(cikis ? miktar.negate() : miktar);
            m.setMovedAt(an);
            m.setTransferGroup(grup);
            m.setCreatedById(kullaniciId);
            em.persist(m);
        }
        em.flush();
        return grup;
    }

    /** Sevkiyat için silodaki tek kalite sınıfı; silo boşsa veya (beklenmedik biçimde) karışıksa hata. */
    public QualityGrade siloSinifi(Silo silo) {
        List<Long> siniflar = em.createQuery("""
                        select l.qualityGradeId from StockMovement m, Lot l where m.lotId = l.id and m.siloId = :s
                        group by l.qualityGradeId having sum(m.quantityKg) > 0""", Long.class)
                .setParameter("s", silo.getId()).getResultList();
        if (siniflar.isEmpty()) throw new IsKuraliHatasi(silo.getCode() + " silosu boş; çıkış yapılamaz.");
        if (siniflar.size() > 1) throw new IsKuraliHatasi(silo.getCode() + " içinde birden çok kalite sınıfı var.");
        return em.find(QualityGrade.class, siniflar.get(0));
    }

    /**
     * Silodan sevkiyat çıkışı: net kg, silodaki lotlardan ilk giren ilk çıkar (FIFO) sırasıyla düşülür; her lot için
     * bir DISPATCH hareketi yazılır. Böylece hangi üreticinin fıstığının hangi alıcıya gittiği izlenebilir.
     * Çağıran, silo satırını kilitlemiş olmalıdır (eşzamanlı iki sevkiyat aynı stoğu kullanamasın).
     */
    public List<StockMovement> silodanSevkEt(Silo silo, BigDecimal netKg, Long sevkiyatId, Long kullaniciId,
                                             OffsetDateTime an) {
        if (netKg.signum() <= 0) throw new IsKuraliHatasi("Çıkış miktarı pozitif olmalı.");
        BigDecimal stok = siloStogu(silo.getId());
        if (stok.compareTo(netKg) < 0)
            throw new IsKuraliHatasi("%s silosunda %s stok var; %s çıkış yapılamaz."
                    .formatted(silo.getCode(), Dogrulama.kg(stok), Dogrulama.kg(netKg)));
        // Önce merkezin satın aldığı lotlar (ilk giren ilk çıkar), emanet lotlar en son
        List<Object[]> lotlar = em.createQuery("""
                        select m.lotId, sum(m.quantityKg), min(m.movedAt),
                        max(case when t.status = :emanet then 1 else 0 end)
                        from StockMovement m, Lot l, WeighTicket t where m.lotId = l.id and l.ticketId = t.id and m.siloId = :s
                        group by m.lotId having sum(m.quantityKg) > 0
                        order by max(case when t.status = :emanet then 1 else 0 end), min(m.movedAt), m.lotId""", Object[].class)
                .setParameter("s", silo.getId()).setParameter("emanet", TicketStatus.DEPOSITED).getResultList();
        List<StockMovement> hareketler = new ArrayList<>();
        BigDecimal kalan = netKg;
        for (Object[] r : lotlar) {
            if (kalan.signum() == 0) break;
            BigDecimal dus = kalan.min((BigDecimal) r[1]);
            StockMovement m = new StockMovement();
            m.setLotId((Long) r[0]);
            m.setSiloId(silo.getId());
            m.setMovementType(StockMovementType.DISPATCH);
            m.setQuantityKg(dus.negate());
            m.setMovedAt(an);
            m.setShipmentId(sevkiyatId);
            m.setCreatedById(kullaniciId);
            em.persist(m);
            hareketler.add(m);
            kalan = kalan.subtract(dus);
        }
        em.flush();
        return hareketler;
    }
}
