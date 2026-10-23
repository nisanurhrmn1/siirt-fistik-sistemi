package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.*;
import tr.edu.siirt.fistik.api.Yanitlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.math.BigDecimal.ZERO;

@Service
@Transactional(readOnly = true)
public class RaporServisi {

    private final EntityManager em;

    public RaporServisi(EntityManager em) {
        this.em = em;
    }

    private static BigDecimal q(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal agirlikli(BigDecimal pay, BigDecimal agirlik) {
        return agirlik.signum() == 0 ? null : pay.divide(agirlik, 2, RoundingMode.HALF_UP);
    }

    private static <T> BigDecimal topla(Collection<T> liste, Function<T, BigDecimal> f) {
        return liste.stream().map(f).reduce(ZERO, BigDecimal::add);
    }

    // ─── Stok ───

    private record Pozisyon(Long siloId, Long lotId, Long sinifId, BigDecimal kg) {
    }

    public StokDurumu stokDurumu(String gruplama, Long siloId, String kaliteKodu) {
        Map<Long, QualityGrade> siniflar = em.createQuery("select g from QualityGrade g", QualityGrade.class)
                .getResultStream().collect(Collectors.toMap(QualityGrade::getId, Function.identity()));
        Map<Long, Silo> silolar = em.createQuery("select s from Silo s order by s.code", Silo.class)
                .getResultStream().collect(Collectors.toMap(Silo::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        if (siloId != null && !silolar.containsKey(siloId))
            throw new BulunamadiHatasi("Silo bulunamadı (id=%d).".formatted(siloId));
        Long sinifFiltre = null;
        if (kaliteKodu != null && !kaliteKodu.isBlank()) {
            sinifFiltre = siniflar.values().stream().filter(g -> g.getCode().equalsIgnoreCase(kaliteKodu)).map(QualityGrade::getId)
                    .findFirst().orElseThrow(() -> new BulunamadiHatasi("'%s' kodlu kalite sınıfı bulunamadı.".formatted(kaliteKodu)));
        }
        final Long sf = sinifFiltre;
        List<Pozisyon> pozisyonlar = em.createQuery("""
                        select m.siloId, m.lotId, l.qualityGradeId, sum(m.quantityKg) from StockMovement m, Lot l
                        where m.lotId = l.id group by m.siloId, m.lotId, l.qualityGradeId""", Object[].class)
                .getResultStream()
                .map(r -> new Pozisyon((Long) r[0], (Long) r[1], (Long) r[2], q((BigDecimal) r[3])))
                .filter(p -> (siloId == null || p.siloId().equals(siloId)) && (sf == null || p.sinifId().equals(sf)))
                .filter(p -> p.kg().signum() != 0).toList();
        BigDecimal toplam = topla(pozisyonlar, Pozisyon::kg);
        OffsetDateTime simdi = Dogrulama.simdi();

        if ("kalite".equals(gruplama)) {
            Map<Long, List<Pozisyon>> sinifa = pozisyonlar.stream().collect(Collectors.groupingBy(Pozisyon::sinifId));
            List<KaliteStok> kaliteler = sinifa.entrySet().stream()
                    .sorted(Comparator.comparing(e -> siniflar.get(e.getKey()).getPriority()))
                    .map(e -> new KaliteStok(siniflar.get(e.getKey()).getCode(), siniflar.get(e.getKey()).getName(),
                            topla(e.getValue(), Pozisyon::kg), (int) e.getValue().stream().map(Pozisyon::lotId).distinct().count(),
                            e.getValue().stream().map(p -> silolar.get(p.siloId()).getCode()).distinct().sorted().toList()))
                    .toList();
            return new StokDurumu("kalite", simdi, toplam, null, kaliteler);
        }

        Map<Long, List<Pozisyon>> siloya = pozisyonlar.stream().collect(Collectors.groupingBy(Pozisyon::siloId));
        List<Silo> secilen = siloId != null ? List.of(silolar.get(siloId))
                : silolar.values().stream().filter(s -> s.isActive() || siloya.containsKey(s.getId())).toList();
        List<SiloStok> sonuc = new ArrayList<>();
        for (Silo s : secilen) {
            List<Pozisyon> ps = siloya.getOrDefault(s.getId(), List.of());
            BigDecimal stok = topla(ps, Pozisyon::kg);
            Map<Long, List<Pozisyon>> sinifPay = ps.stream().collect(Collectors.groupingBy(Pozisyon::sinifId, LinkedHashMap::new, Collectors.toList()));
            sonuc.add(new SiloStok(s.getId(), s.getCode(), s.getName(),
                    s.getDedicatedGradeId() != null ? siniflar.get(s.getDedicatedGradeId()).getCode() : null,
                    s.getCapacityKg(), stok, s.getCapacityKg().subtract(stok),
                    stok.multiply(BigDecimal.valueOf(100)).divide(s.getCapacityKg(), 2, RoundingMode.HALF_UP),
                    sinifPay.entrySet().stream().map(e -> new KaliteStokPayi(siniflar.get(e.getKey()).getCode(),
                            topla(e.getValue(), Pozisyon::kg), e.getValue().size())).toList()));
        }
        return new StokDurumu("silo", simdi, toplam, sonuc, null);
    }

    // ─── Üretici bakiyeleri ───

    private Map<Long, BigDecimal> grupla(String jpql) {
        return em.createQuery(jpql, Object[].class).getResultStream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> r[1] == null ? ZERO : (BigDecimal) r[1]));
    }

    public List<UreticiBakiye> ureticiBakiyeleri() {
        Map<Long, BigDecimal> bakiye = grupla("select e.producerId, sum(e.amount) from ProducerLedgerEntry e group by e.producerId");
        Map<Long, BigDecimal> avans = grupla("select a.producerId, sum(a.amount - a.offsetAmount) from Advance a group by a.producerId");
        Map<Long, BigDecimal> odenmemis = grupla("select s.producerId, sum(s.netPayable - s.paidAmount) from Settlement s group by s.producerId");
        Map<Long, BigDecimal> kg = grupla("select s.producerId, sum(s.netKg) from Settlement s group by s.producerId");
        return em.createQuery("select p from Producer p order by p.fullName, p.id", Producer.class).getResultStream()
                .map(p -> {
                    BigDecimal b = q(bakiye.getOrDefault(p.getId(), ZERO)), o = q(odenmemis.getOrDefault(p.getId(), ZERO));
                    return new UreticiBakiye(p.getId(), p.getFullName(), b, q(avans.getOrDefault(p.getId(), ZERO)), o,
                            o.min(b).max(ZERO), q(kg.getOrDefault(p.getId(), ZERO)));
                }).toList();
    }

    // ─── Günlük özet ───

    public GunlukOzet gunlukOzet(LocalDate gun) {
        OffsetDateTime[] a = Dogrulama.gunAraligi(gun);
        List<WeighTicket> tartimlar = em.createQuery("""
                        select t from WeighTicket t where t.grossWeighedAt >= :b and t.grossWeighedAt < :s
                        and t.status in :durumlar""", WeighTicket.class)
                .setParameter("b", a[0]).setParameter("s", a[1])
                .setParameter("durumlar", List.of(TicketStatus.GRADED, TicketStatus.SETTLED)).getResultList();
        List<Payment> odemeler = em.createQuery("select p from Payment p where p.paidAt >= :b and p.paidAt < :s", Payment.class)
                .setParameter("b", a[0]).setParameter("s", a[1]).getResultList();
        List<Advance> avanslar = em.createQuery("select v from Advance v where v.givenAt >= :b and v.givenAt < :s", Advance.class)
                .setParameter("b", a[0]).setParameter("s", a[1]).getResultList();

        Map<Long, QualityInspection> analizler = new HashMap<>();
        Map<Long, Settlement> hakedisler = new HashMap<>();
        for (WeighTicket t : tartimlar) {
            analizler.put(t.getId(), TartimServisi.analiz(em, t.getId()));
            Settlement s = TartimServisi.hakedis(em, t.getId());
            if (s != null) hakedisler.put(t.getId(), s);
        }
        Map<Long, QualityGrade> siniflar = em.createQuery("select g from QualityGrade g", QualityGrade.class)
                .getResultStream().collect(Collectors.toMap(QualityGrade::getId, Function.identity()));

        BigDecimal netKg = topla(tartimlar, WeighTicket::getNetKg);
        Function<WeighTicket, BigDecimal> kgRandiman = t -> t.getNetKg().multiply(analizler.get(t.getId()).getYieldRatio());
        Function<WeighTicket, BigDecimal> kgFiyat = t -> t.getNetKg().multiply(t.getUnitPrice());
        Collection<Settlement> hs = hakedisler.values();

        Map<Long, List<WeighTicket>> sinifa = tartimlar.stream()
                .collect(Collectors.groupingBy(t -> analizler.get(t.getId()).getQualityGradeId()));
        List<KaliteKirilimi> kirilim = sinifa.entrySet().stream()
                .sorted(Comparator.comparing(e -> siniflar.get(e.getKey()).getPriority()))
                .map(e -> {
                    List<WeighTicket> ts = e.getValue();
                    BigDecimal kg = topla(ts, WeighTicket::getNetKg);
                    return new KaliteKirilimi(siniflar.get(e.getKey()).getCode(), ts.size(), kg,
                            topla(ts.stream().filter(t -> hakedisler.containsKey(t.getId())).toList(),
                                    t -> hakedisler.get(t.getId()).getGrossAmount()),
                            agirlikli(topla(ts, kgFiyat), kg), agirlikli(topla(ts, kgRandiman), kg));
                }).toList();

        return new GunlukOzet(gun, tartimlar.size(), (int) tartimlar.stream().map(WeighTicket::getProducerId).distinct().count(),
                netKg, agirlikli(topla(tartimlar, kgRandiman), netKg), agirlikli(topla(tartimlar, kgFiyat), netKg),
                topla(hs, Settlement::getGrossAmount), topla(hs, Settlement::getDeductionsTotal),
                topla(hs, Settlement::getAdvanceOffsetTotal), topla(hs, Settlement::getNetPayable),
                topla(odemeler, Payment::getAmount), odemeler.size(), topla(avanslar, Advance::getAmount), kirilim);
    }
}
