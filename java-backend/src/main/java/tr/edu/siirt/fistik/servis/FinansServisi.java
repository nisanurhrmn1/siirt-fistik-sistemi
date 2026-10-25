package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.*;
import tr.edu.siirt.fistik.api.Istekler.AvansGirisi;
import tr.edu.siirt.fistik.api.Istekler.OdemeGirisi;
import tr.edu.siirt.fistik.api.Yanitlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi;
import tr.edu.siirt.fistik.kural.Kurallar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.math.BigDecimal.ZERO;
import static tr.edu.siirt.fistik.kural.Dogrulama.tl;

@Service
public class FinansServisi {

    private final EntityManager em;
    private final Kurallar kurallar;
    private final UreticiServisi ureticiler;

    public FinansServisi(EntityManager em, Kurallar kurallar, UreticiServisi ureticiler) {
        this.em = em;
        this.kurallar = kurallar;
        this.ureticiler = ureticiler;
    }

    // ─── Hakediş ───

    public Settlement hakedisBul(Long id) {
        Settlement s = em.find(Settlement.class, id);
        if (s == null) throw new BulunamadiHatasi("Hakediş bulunamadı (id=%d).".formatted(id));
        return s;
    }

    @Transactional(readOnly = true)
    public HakedisYaniti hakedisGetir(Long id) {
        return hakedisYaniti(hakedisBul(id));
    }

    HakedisYaniti hakedisYaniti(Settlement s) {
        Map<Long, DeductionType> turler = em.createQuery("select d from DeductionType d", DeductionType.class)
                .getResultStream().collect(Collectors.toMap(DeductionType::getId, Function.identity()));
        List<KesintiYaniti> kesintiler = em.createQuery(
                        "select d from SettlementDeduction d where d.settlementId = :s", SettlementDeduction.class)
                .setParameter("s", s.getId()).getResultStream()
                .sorted((a, b) -> Integer.compare(turler.get(a.getDeductionTypeId()).getSortOrder(),
                        turler.get(b.getDeductionTypeId()).getSortOrder()))
                .map(d -> {
                    DeductionType t = turler.get(d.getDeductionTypeId());
                    return new KesintiYaniti(t.getCode(), t.getName(), d.getCalcMethod(), d.getRate(), d.getBaseAmount(),
                            d.getAmount());
                }).toList();
        List<AvansMahsubuYaniti> mahsuplar = em.createQuery("""
                        select o.advanceId, a.advanceNo, o.amount from AdvanceOffset o, Advance a
                        where o.advanceId = a.id and o.settlementId = :s order by o.id""", Object[].class)
                .setParameter("s", s.getId()).getResultStream()
                .map(r -> new AvansMahsubuYaniti((Long) r[0], (String) r[1], (BigDecimal) r[2])).toList();
        String fisNo = em.find(WeighTicket.class, s.getTicketId()).getTicketNo();
        return new HakedisYaniti(s.getId(), s.getSettlementNo(), s.getTicketId(), fisNo, s.getProducerId(), s.getNetKg(),
                s.getUnitPrice(), s.getGrossAmount(), kesintiler, s.getDeductionsTotal(), mahsuplar,
                s.getAdvanceOffsetTotal(), s.getNetPayable(), s.getPaidAmount(),
                s.getNetPayable().subtract(s.getPaidAmount()), s.getStatus(), s.getCreatedAt());
    }

    // ─── Avans ───

    @Transactional
    public AvansYaniti avansVer(AvansGirisi g, Long kullaniciId) {
        ureticiler.bul(g.ureticiId());   // 404
        Advance a = kurallar.avansVer(g.ureticiId(), g.tutar(), g.odemeYontemi(), kullaniciId, Dogrulama.simdi(),
                g.bankaHesapId(), g.aciklama());
        return new AvansYaniti(a.getId(), a.getAdvanceNo(), a.getProducerId(), a.getAmount(), a.getOffsetAmount(),
                a.getAmount().subtract(a.getOffsetAmount()), a.getStatus(), a.getMethod(), a.getGivenAt(),
                a.getDescription(), kurallar.ureticiBakiyesi(a.getProducerId()), null);
    }

    // ─── Ödeme ───

    public record OdemeIslemi(OdemeSonucu sonuc, boolean odemeOlustu) {
    }

    /** Hakedişin kalanını öder; önce hakediş oluştuktan sonra verilmiş açık avansları mahsup eder. */
    @Transactional
    public OdemeIslemi odemeYap(Long hakedisId, OdemeGirisi g, Long kullaniciId) {
        Settlement s = hakedisBul(hakedisId);
        kurallar.ureticiKilitle(s.getProducerId());
        if (s.getNetPayable().subtract(s.getPaidAmount()).signum() <= 0)
            throw new IsKuraliHatasi("%s için ödenecek tutar kalmadı (durum: %s).".formatted(s.getSettlementNo(), s.getStatus()));

        BigDecimal simdiMahsup = kurallar.hakediseSonradanAvansMahsupEt(s);
        BigDecimal kalan = s.getNetPayable().subtract(s.getPaidAmount());

        Payment p = null;
        if (kalan.signum() > 0) {
            BigDecimal tutar = g.tutar() != null ? g.tutar() : kalan;
            if (tutar.compareTo(kalan) > 0)
                throw new IsKuraliHatasi("Ödeme tutarı (%s) hakedişin kalan tutarını (%s) aşamaz.".formatted(tl(tutar), tl(kalan)));
            p = kurallar.odemeKaydet(s.getProducerId(), tutar, g.odemeYontemi(), kullaniciId, Dogrulama.simdi(),
                    g.bankaHesapId(), g.referansNo(), g.aciklama(), s.getId());
        }

        String mesaj;
        if (p == null) mesaj = "Hakedişin kalan tutarının tamamı açık avanslardan mahsup edildi; ödeme yapılmadı.";
        else if (s.getNetPayable().compareTo(s.getPaidAmount()) == 0) mesaj = tl(p.getAmount()) + " ödendi; hakediş tamamen kapandı.";
        else mesaj = "%s kısmi ödeme yapıldı; kalan %s.".formatted(tl(p.getAmount()), tl(s.getNetPayable().subtract(s.getPaidAmount())));
        if (simdiMahsup.signum() > 0) mesaj = tl(simdiMahsup) + " açık avans mahsup edildi. " + mesaj;

        OdemeYaniti odeme = null;
        if (p != null) {
            String iban = p.getBankAccountId() == null ? null : em.find(ProducerBankAccount.class, p.getBankAccountId()).getIban();
            odeme = new OdemeYaniti(p.getId(), p.getPaymentNo(), p.getAmount(), p.getMethod(), p.getBankAccountId(), iban,
                    p.getPaidAt(), p.getReferenceNo());
        }
        OdemeSonucu sonuc = new OdemeSonucu(mesaj, simdiMahsup, odeme, hakedisYaniti(s), kurallar.ureticiBakiyesi(s.getProducerId()));
        return new OdemeIslemi(sonuc, p != null);
    }

    // ─── Ekstre ───

    @Transactional(readOnly = true)
    public UreticiEkstre ekstre(Long ureticiId, LocalDate baslangic, LocalDate bitis) {
        Producer u = ureticiler.bul(ureticiId);
        if (baslangic != null && bitis != null && baslangic.isAfter(bitis))
            throw new IsKuraliHatasi("baslangic tarihi bitis tarihinden sonra olamaz.");
        OffsetDateTime bas = baslangic != null ? Dogrulama.gunAraligi(baslangic)[0] : null;
        OffsetDateTime son = bitis != null ? Dogrulama.gunAraligi(bitis)[1] : null;
        java.util.function.Predicate<OffsetDateTime> donemde =
                t -> (bas == null || !t.isBefore(bas)) && (son == null || t.isBefore(son));

        List<ProducerLedgerEntry> defter = em.createQuery(
                        "select e from ProducerLedgerEntry e where e.producerId = :u order by e.entryAt, e.id", ProducerLedgerEntry.class)
                .setParameter("u", ureticiId).getResultList();
        List<Advance> avanslar = em.createQuery("select a from Advance a where a.producerId = :u order by a.givenAt, a.id",
                Advance.class).setParameter("u", ureticiId).getResultList();
        List<Settlement> hakedisler = em.createQuery("select s from Settlement s where s.producerId = :u", Settlement.class)
                .setParameter("u", ureticiId).getResultList();

        Function<LedgerEntryType, BigDecimal> toplam = tur -> defter.stream().filter(e -> e.getEntryType() == tur)
                .map(ProducerLedgerEntry::getAmount).reduce(ZERO, BigDecimal::add);
        BigDecimal bakiye = defter.stream().map(ProducerLedgerEntry::getAmount).reduce(ZERO, BigDecimal::add);
        BigDecimal odenmemis = hakedisler.stream().map(s -> s.getNetPayable().subtract(s.getPaidAmount())).reduce(ZERO, BigDecimal::add);
        EkstreOzeti ozet = new EkstreOzeti(bakiye,
                bakiye.signum() > 0 ? "ALACAKLI" : bakiye.signum() < 0 ? "BORCLU" : "KAPALI",
                hakedisler.stream().map(Settlement::getNetKg).reduce(ZERO, BigDecimal::add),
                toplam.apply(LedgerEntryType.DELIVERY), toplam.apply(LedgerEntryType.DEDUCTION).negate(),
                toplam.apply(LedgerEntryType.ADVANCE).negate(), toplam.apply(LedgerEntryType.PAYMENT).negate(),
                avanslar.stream().map(a -> a.getAmount().subtract(a.getOffsetAmount())).reduce(ZERO, BigDecimal::add),
                odenmemis, odenmemis.min(bakiye).max(ZERO),
                em.createQuery("select coalesce(sum(t.netKg - t.depositSoldKg), 0) from WeighTicket t where t.producerId = :u and t.status = :e",
                        BigDecimal.class).setParameter("u", ureticiId).setParameter("e", TicketStatus.DEPOSITED).getSingleResult());

        BigDecimal devir = defter.stream().filter(e -> bas != null && e.getEntryAt().isBefore(bas))
                .map(ProducerLedgerEntry::getAmount).reduce(ZERO, BigDecimal::add);
        BigDecimal yuruyen = devir;
        List<EkstreHareket> hareketler = new ArrayList<>();
        for (ProducerLedgerEntry e : defter) {
            if (!donemde.test(e.getEntryAt())) continue;
            yuruyen = yuruyen.add(e.getAmount());
            // Dökümde belge, ürün/sınıf, miktar ve birim fiyat sütunları için bağlı kaydı bul
            String belge = null, urunSinif = null;
            BigDecimal miktar = null, birimFiyat = null;
            PaymentMethod yontem = null;
            Long tartimId = e.getTicketId();
            if (tartimId == null && e.getSettlementId() != null) tartimId = em.find(Settlement.class, e.getSettlementId()).getTicketId();
            if (tartimId != null) {
                WeighTicket t = em.find(WeighTicket.class, tartimId);
                belge = t.getTicketNo();
                if (e.getEntryType() == LedgerEntryType.DELIVERY) {
                    QualityInspection q = TartimServisi.analiz(em, tartimId);
                    urunSinif = t.getProductType().getAd()
                            + (q != null ? " · " + em.find(QualityGrade.class, q.getQualityGradeId()).getCode() : "");
                    miktar = t.getNetKg();
                    birimFiyat = t.getUnitPrice();
                }
            } else if (e.getAdvanceId() != null) {
                Advance a = em.find(Advance.class, e.getAdvanceId());
                belge = a.getAdvanceNo();
                yontem = a.getMethod();
            } else if (e.getPaymentId() != null) {
                Payment p = em.find(Payment.class, e.getPaymentId());
                belge = p.getPaymentNo();
                yontem = p.getMethod();
            }
            String aciklama = e.getDescription();
            if (e.getSettlementDeductionId() != null) {   // "Gelir vergisi stopajı %2"
                SettlementDeduction sd = em.find(SettlementDeduction.class, e.getSettlementDeductionId());
                aciklama = em.find(DeductionType.class, sd.getDeductionTypeId()).getName()
                        + (sd.getCalcMethod() == DeductionCalcMethod.PERCENT ? " %" + sd.getRate().stripTrailingZeros().toPlainString().replace('.', ',') : "");
            }
            hareketler.add(new EkstreHareket(e.getId(), e.getEntryAt(), e.getEntryType().name(), aciklama,
                    e.getAmount().signum() > 0 ? e.getAmount() : ZERO, e.getAmount().signum() < 0 ? e.getAmount().negate() : ZERO,
                    yuruyen, belge, urunSinif, miktar, birimFiyat, yontem));
        }

        Map<Long, Settlement> hakedisByTartim = hakedisler.stream().collect(Collectors.toMap(Settlement::getTicketId, s -> s));
        List<EkstreTeslimat> teslimatlar = new ArrayList<>();
        for (WeighTicket t : em.createQuery("""
                        select t from WeighTicket t where t.producerId = :u and t.status <> :iptal
                        order by t.grossWeighedAt, t.id""", WeighTicket.class)
                .setParameter("u", ureticiId).setParameter("iptal", TicketStatus.CANCELLED).getResultList()) {
            if (!donemde.test(t.getGrossWeighedAt())) continue;
            Settlement s = hakedisByTartim.get(t.getId());
            QualityInspection q = TartimServisi.analiz(em, t.getId());
            Lot lot = TartimServisi.lot(em, t.getId());
            teslimatlar.add(new EkstreTeslimat(t.getId(), t.getTicketNo(), lot != null ? lot.getLotNo() : null,
                    t.getGrossWeighedAt(), t.getNetKg() != null ? t.getNetKg() : ZERO,
                    q != null ? em.find(QualityGrade.class, q.getQualityGradeId()).getCode() : null,
                    q != null ? q.getYieldRatio() : null, t.getUnitPrice(), s != null ? s.getId() : null,
                    s != null ? s.getGrossAmount() : null, s != null ? s.getDeductionsTotal() : null,
                    s != null ? s.getAdvanceOffsetTotal() : null, s != null ? s.getNetPayable() : null,
                    s != null ? s.getPaidAmount() : null, s != null ? s.getNetPayable().subtract(s.getPaidAmount()) : null,
                    s != null ? s.getStatus() : null, t.getProductType(), t.getProductType().getAd(),
                    t.getStatus() == TicketStatus.DEPOSITED, Kurallar.emanetKalan(t),
                    t.getParentTicketId() != null ? em.find(WeighTicket.class, t.getParentTicketId()).getTicketNo() : null));
        }

        Function<Advance, EkstreAvans> avansSatiri = a -> new EkstreAvans(a.getId(), a.getAdvanceNo(),
                a.getGivenAt(), a.getAmount(), a.getOffsetAmount(), a.getAmount().subtract(a.getOffsetAmount()), a.getStatus());
        List<EkstreOdeme> odemeler = em.createQuery("select p from Payment p where p.producerId = :u order by p.paidAt, p.id",
                        Payment.class).setParameter("u", ureticiId).getResultStream()
                .filter(p -> donemde.test(p.getPaidAt()))
                .map(p -> new EkstreOdeme(p.getId(), p.getPaymentNo(), p.getPaidAt(), p.getAmount(), p.getMethod(), p.getReferenceNo()))
                .toList();

        return new UreticiEkstre(new UreticiKisa(u.getId(), u.getFullName(), u.getNationalId(), u.getTaxNumber(), u.getPhone()),
                bas, son, ozet, devir, teslimatlar,
                avanslar.stream().filter(a -> a.getOffsetAmount().compareTo(a.getAmount()) < 0).map(avansSatiri).toList(),
                hareketler,
                avanslar.stream().filter(a -> donemde.test(a.getGivenAt())).map(avansSatiri).toList(),
                odemeler);
    }
}
