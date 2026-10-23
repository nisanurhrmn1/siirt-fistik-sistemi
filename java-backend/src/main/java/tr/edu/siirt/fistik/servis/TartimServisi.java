package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.*;
import tr.edu.siirt.fistik.api.Istekler.KaliteAnalizi;
import tr.edu.siirt.fistik.api.Istekler.OnizlemeGirisi;
import tr.edu.siirt.fistik.api.Istekler.TartimGirisi;
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
import java.util.Locale;

import static java.math.BigDecimal.ZERO;

/** Kantar → kalite → fiyat kilidi → hakediş → stok: tek transaction'da teslimat kaydı. */
@Service
public class TartimServisi {

    private final EntityManager em;
    private final Kurallar kurallar;
    private final FinansServisi finans;

    public TartimServisi(EntityManager em, Kurallar kurallar, FinansServisi finans) {
        this.em = em;
        this.kurallar = kurallar;
        this.finans = finans;
    }

    static QualityInspection analiz(EntityManager em, Long tartimId) {
        return em.createQuery("select q from QualityInspection q where q.ticketId = :t", QualityInspection.class)
                .setParameter("t", tartimId).getResultStream().findFirst().orElse(null);
    }

    static Lot lot(EntityManager em, Long tartimId) {
        return em.createQuery("select l from Lot l where l.ticketId = :t", Lot.class)
                .setParameter("t", tartimId).getResultStream().findFirst().orElse(null);
    }

    static Settlement hakedis(EntityManager em, Long tartimId) {
        return em.createQuery("select s from Settlement s where s.ticketId = :t", Settlement.class)
                .setParameter("t", tartimId).getResultStream().findFirst().orElse(null);
    }

    private QualityGrade sinifKodla(String kod) {
        return em.createQuery("select g from QualityGrade g where g.code = :k", QualityGrade.class)
                .setParameter("k", kod.toUpperCase(Locale.ROOT)).getResultStream().findFirst()
                .orElseThrow(() -> new BulunamadiHatasi("'%s' kodlu kalite sınıfı bulunamadı.".formatted(kod)));
    }

    /** Önce lotun sınıfına adanmış, sonra adanmamış silolar; kapasitesi ve kalite uyumu olan ilki. */
    private Silo siloSec(Lot lot) {
        List<Silo> adaylar = em.createQuery("""
                        select s from Silo s where s.active = true and (s.dedicatedGradeId = :g or s.dedicatedGradeId is null)
                        order by case when s.dedicatedGradeId = :g then 0 else 1 end, s.code""", Silo.class)
                .setParameter("g", lot.getQualityGradeId()).getResultList();
        for (Silo s : adaylar) {
            try {
                kurallar.siloKabulEderMi(s, lot, lot.getInitialKg());
                return s;
            } catch (IsKuraliHatasi e) {
                // sonraki siloyu dene
            }
        }
        String kod = em.find(QualityGrade.class, lot.getQualityGradeId()).getCode();
        throw new IsKuraliHatasi("%s sınıfı %s kg için uygun (aynı kalite, yeterli kapasite) silo yok."
                .formatted(kod, lot.getInitialKg().toPlainString()));
    }

    @Transactional
    public TartimYaniti kaydet(TartimGirisi g, Long kullaniciId) {
        Producer u = em.find(Producer.class, g.ureticiId());
        if (u == null) throw new BulunamadiHatasi("Üretici bulunamadı (id=%d).".formatted(g.ureticiId()));
        if (!u.isActive()) throw new IsKuraliHatasi("Pasif üreticiden alım yapılamaz.");
        Silo silo = null;
        if (g.siloId() != null) {
            silo = em.find(Silo.class, g.siloId());
            if (silo == null) throw new BulunamadiHatasi("Silo bulunamadı (id=%d).".formatted(g.siloId()));
        }
        Long manuelSinif = g.manuelKaliteKodu() != null ? sinifKodla(g.manuelKaliteKodu()).getId() : null;

        // Tek zaman damgası: fiyat bu ana göre kilitlenir.
        OffsetDateTime an = Dogrulama.simdi();
        WeighTicket t = kurallar.tartimOlustur(u.getId(), g.plaka(), g.brutKg(), kullaniciId, an, g.soforAdi());
        kurallar.daraKaydet(t, g.daraKg(), an);
        KaliteAnalizi k = g.kalite();
        kurallar.kaliteAnaliziKaydet(t, k.numuneGram(), k.catlakOrani(), k.randiman(), k.bosOrani(), k.curukOrani(),
                k.nemOrani(), kullaniciId, an, manuelSinif, g.manuelKaliteGerekcesi());
        kurallar.hakedisOlustur(t, kullaniciId, an);
        Lot lot = lot(em, t.getId());
        kurallar.lotuSiloyaAl(lot, silo != null ? silo : siloSec(lot), kullaniciId, null, an);
        em.flush();
        em.refresh(t);
        return yanit(t);
    }

    @Transactional(readOnly = true)
    public TartimYaniti getir(Long id) {
        WeighTicket t = em.find(WeighTicket.class, id);
        if (t == null) throw new BulunamadiHatasi("Tartım bulunamadı (id=%d).".formatted(id));
        return yanit(t);
    }

    TartimYaniti yanit(WeighTicket t) {
        QualityInspection q = analiz(em, t.getId());
        Lot lot = lot(em, t.getId());
        Settlement s = hakedis(em, t.getId());
        DailyPrice p = t.getDailyPriceId() != null ? em.find(DailyPrice.class, t.getDailyPriceId()) : null;
        Producer u = em.find(Producer.class, t.getProducerId());

        KaliteSonucu kalite = null;
        if (q != null) {
            QualityGrade g = em.find(QualityGrade.class, q.getQualityGradeId());
            kalite = new KaliteSonucu(g.getCode(), g.getName(), q.getSplitRatio(), q.getYieldRatio(), q.getEmptyRatio(),
                    q.getRottenRatio(), q.getMoistureRatio(), q.isManualOverride());
        }
        List<StokKaydi> stok = new ArrayList<>();
        if (lot != null) {
            for (Object[] r : em.createQuery("""
                            select s.id, s.code, sum(m.quantityKg) from StockMovement m, Silo s
                            where m.siloId = s.id and m.lotId = :l group by s.id, s.code order by s.code""", Object[].class)
                    .setParameter("l", lot.getId()).getResultList()) {
                BigDecimal miktar = (BigDecimal) r[2];
                if (miktar != null && miktar.signum() != 0) stok.add(new StokKaydi((Long) r[0], (String) r[1], miktar));
            }
        }
        return new TartimYaniti(t.getId(), t.getTicketNo(), lot != null ? lot.getLotNo() : null, t.getStatus(),
                t.getProducerId(), u.getFullName(), t.getVehiclePlate(), t.getDriverName(), t.getGrossKg(), t.getTareKg(),
                t.getNetKg(), t.getGrossWeighedAt(), kalite,
                p != null ? new FiyatKilidi(p.getId(), t.getUnitPrice(), p.getValidFrom(), p.getApprovedAt(), t.getPricedAt()) : null,
                s != null ? finans.hakedisYaniti(s) : null, stok, kurallar.ureticiBakiyesi(t.getProducerId()));
    }

    /** Hiçbir şey kaydetmeden, kaydedilseydi oluşacak sınıf/fiyat/hakedişi aynı kurallarla hesaplar. */
    @Transactional(readOnly = true)
    public TartimOnizleme onizle(OnizlemeGirisi g) {
        BigDecimal netKg = g.brutKg().subtract(g.daraKg());
        List<String> uyarilar = new ArrayList<>();
        KaliteAnalizi k = g.kalite();
        QualityGrade sinif = g.manuelKaliteKodu() != null ? sinifKodla(g.manuelKaliteKodu())
                : kurallar.sinifBelirle(k.catlakOrani(), k.randiman(), k.bosOrani(), k.curukOrani(), k.nemOrani());

        BigDecimal acikAvans = ZERO;
        if (g.ureticiId() != null) {
            acikAvans = em.createQuery("select coalesce(sum(a.amount - a.offsetAmount), 0) from Advance a where a.producerId = :u",
                    BigDecimal.class).setParameter("u", g.ureticiId()).getSingleResult();
        }

        DailyPrice fiyat = kurallar.gecerliFiyat(sinif.getId(), Dogrulama.simdi());
        if (fiyat == null) {
            uyarilar.add(sinif.getCode() + " sınıfı için şu an onaylı fiyat yok; tartım kaydedilemez.");
            return new TartimOnizleme(netKg, sinif.getCode(), sinif.getName(), null, null, null, List.of(), ZERO, acikAvans,
                    ZERO, null, uyarilar);
        }
        BigDecimal brut = Dogrulama.para(netKg.multiply(fiyat.getPricePerKg()));
        List<KesintiOnizleme> kesintiler = em.createQuery(
                        "select d from DeductionType d where d.active = true order by d.sortOrder", DeductionType.class)
                .getResultStream().map(dt -> new KesintiOnizleme(dt.getCode(), dt.getName(), Kurallar.kesintiTutari(dt, brut, netKg)[1]))
                .toList();
        BigDecimal kesintiToplami = kesintiler.stream().map(KesintiOnizleme::tutar).reduce(ZERO, BigDecimal::add);
        BigDecimal mahsup = acikAvans.min(brut.subtract(kesintiToplami).max(ZERO));
        if (mahsup.signum() > 0)
            uyarilar.add("Üreticinin %s açık avansı var; %s bu teslimattan mahsup edilecek."
                    .formatted(Dogrulama.tl(acikAvans), Dogrulama.tl(mahsup)));
        return new TartimOnizleme(netKg, sinif.getCode(), sinif.getName(), fiyat.getId(), fiyat.getPricePerKg(), brut,
                kesintiler, kesintiToplami, acikAvans, mahsup, brut.subtract(kesintiToplami).subtract(mahsup), uyarilar);
    }

    @Transactional(readOnly = true)
    public List<TartimOzeti> listele(LocalDate gun, Long ureticiId) {
        OffsetDateTime[] aralik = Dogrulama.gunAraligi(gun);
        var q = em.createQuery("select t from WeighTicket t where t.grossWeighedAt >= :b and t.grossWeighedAt < :s"
                + (ureticiId != null ? " and t.producerId = :u" : "") + " order by t.grossWeighedAt desc, t.id desc", WeighTicket.class)
                .setParameter("b", aralik[0]).setParameter("s", aralik[1]);
        if (ureticiId != null) q.setParameter("u", ureticiId);
        List<TartimOzeti> sonuc = new ArrayList<>();
        for (WeighTicket t : q.getResultList()) {
            Settlement s = hakedis(em, t.getId());
            Lot l = lot(em, t.getId());
            QualityInspection qi = analiz(em, t.getId());
            sonuc.add(new TartimOzeti(t.getId(), t.getTicketNo(), l != null ? l.getLotNo() : null, t.getGrossWeighedAt(),
                    t.getProducerId(), em.find(Producer.class, t.getProducerId()).getFullName(), t.getVehiclePlate(),
                    t.getNetKg(), qi != null ? em.find(QualityGrade.class, qi.getQualityGradeId()).getCode() : null,
                    t.getUnitPrice(), s != null ? s.getId() : null, s != null ? s.getNetPayable() : null,
                    s != null ? s.getNetPayable().subtract(s.getPaidAmount()) : null,
                    s != null ? s.getStatus().name() : null));
        }
        return sonuc;
    }
}
