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
        // Siloda üreticilerin emaneti olarak duran lotlar (henüz satışa çevrilmemiş)
        Set<Long> emanetLotlari = new HashSet<>(em.createQuery(
                        "select l.id from Lot l, WeighTicket t where l.ticketId = t.id and t.status = :e", Long.class)
                .setParameter("e", TicketStatus.DEPOSITED).getResultList());
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
                            topla(e.getValue(), Pozisyon::kg), e.getValue().size())).toList(),
                    q(emanetLotlari.isEmpty() ? ZERO : topla(ps.stream().filter(p -> emanetLotlari.contains(p.lotId())).toList(), Pozisyon::kg))));
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
        Map<Long, BigDecimal> emanet = em.createQuery(
                        "select t.producerId, sum(t.netKg) from WeighTicket t where t.status = :e group by t.producerId", Object[].class)
                .setParameter("e", TicketStatus.DEPOSITED).getResultStream()
                .collect(Collectors.toMap(x -> (Long) x[0], x -> (BigDecimal) x[1]));
        return em.createQuery("select p from Producer p order by p.fullName, p.id", Producer.class).getResultStream()
                .map(p -> {
                    BigDecimal b = q(bakiye.getOrDefault(p.getId(), ZERO)), o = q(odenmemis.getOrDefault(p.getId(), ZERO));
                    return new UreticiBakiye(p.getId(), p.getFullName(), b, q(avans.getOrDefault(p.getId(), ZERO)), o,
                            o.min(b).max(ZERO), q(kg.getOrDefault(p.getId(), ZERO)), q(emanet.getOrDefault(p.getId(), ZERO)));
                }).toList();
    }

    // ─── Gün sonu kasa & kantar raporu ───

    public GunSonuRaporu gunSonu(LocalDate gun) {
        OffsetDateTime[] a = Dogrulama.gunAraligi(gun);
        Map<Long, String> ureticiAdi = em.createQuery("select p.id, p.fullName from Producer p", Object[].class)
                .getResultStream().collect(Collectors.toMap(r -> (Long) r[0], r -> (String) r[1]));
        Map<Long, QualityGrade> siniflar = em.createQuery("select g from QualityGrade g", QualityGrade.class)
                .getResultStream().collect(Collectors.toMap(QualityGrade::getId, Function.identity()));

        // Teslimatlar (iptal edilenler hariç)
        List<WeighTicket> tartimlar = em.createQuery("""
                        select t from WeighTicket t where t.grossWeighedAt >= :b and t.grossWeighedAt < :s and t.status <> :iptal
                        order by t.grossWeighedAt, t.id""", WeighTicket.class)
                .setParameter("b", a[0]).setParameter("s", a[1]).setParameter("iptal", TicketStatus.CANCELLED).getResultList();
        List<GunSonuTeslimat> teslimatlar = new ArrayList<>();
        Map<ProductType, List<Object[]>> tureGore = new EnumMap<>(ProductType.class);   // [kg, netHakedis]
        BigDecimal gununNet = ZERO, gununOdenen = ZERO;
        for (WeighTicket t : tartimlar) {
            Settlement s = TartimServisi.hakedis(em, t.getId());
            QualityInspection q = TartimServisi.analiz(em, t.getId());
            Lot lot = TartimServisi.lot(em, t.getId());
            BigDecimal net = s != null ? s.getNetPayable() : ZERO, odenen = s != null ? s.getPaidAmount() : ZERO;
            gununNet = gununNet.add(net);
            gununOdenen = gununOdenen.add(odenen);
            tureGore.computeIfAbsent(t.getProductType(), k -> new ArrayList<>()).add(new Object[]{t.getNetKg(), net});
            teslimatlar.add(new GunSonuTeslimat(t.getGrossWeighedAt(), t.getTicketNo(), lot != null ? lot.getLotNo() : null,
                    ureticiAdi.get(t.getProducerId()), t.getVehiclePlate(), t.getProductType().getAd(),
                    q != null ? siniflar.get(q.getQualityGradeId()).getCode() : null, t.getNetKg(), t.getUnitPrice(),
                    s != null ? s.getGrossAmount() : null, s != null ? s.getDeductionsTotal() : null,
                    s != null ? s.getAdvanceOffsetTotal() : null, s != null ? net : null, s != null ? odenen : null,
                    s != null ? net.subtract(odenen) : null, t.getGrossSource(), t.getTareSource(),
                    t.getStatus() == TicketStatus.DEPOSITED ? "Emanet" : t.getSaleMode().getAd()));
        }
        List<UrunTuruOzeti> urunTurleri = tureGore.entrySet().stream()
                .map(e -> new UrunTuruOzeti(e.getKey(), e.getKey().getAd(), e.getValue().size(),
                        topla(e.getValue(), r -> (BigDecimal) r[0]), topla(e.getValue(), r -> (BigDecimal) r[1])))
                .toList();

        // Kasa: o gün yapılan ödemeler ve verilen avanslar
        List<Payment> odemeler = em.createQuery("select p from Payment p where p.paidAt >= :b and p.paidAt < :s order by p.paidAt, p.id",
                Payment.class).setParameter("b", a[0]).setParameter("s", a[1]).getResultList();
        List<Advance> avanslar = em.createQuery("select v from Advance v where v.givenAt >= :b and v.givenAt < :s order by v.givenAt, v.id",
                Advance.class).setParameter("b", a[0]).setParameter("s", a[1]).getResultList();
        Function<PaymentMethod, BigDecimal> odeme = y -> topla(odemeler.stream().filter(p -> p.getMethod() == y).toList(), Payment::getAmount);
        Function<PaymentMethod, BigDecimal> avans = y -> topla(avanslar.stream().filter(v -> v.getMethod() == y).toList(), Advance::getAmount);
        BigDecimal tumAcik = em.createQuery("select coalesce(sum(s.netPayable - s.paidAmount), 0) from Settlement s", BigDecimal.class)
                .getSingleResult();
        // Satış: o gün yapılan sevkiyatlar ve tahsilatları
        List<Shipment> satislar = em.createQuery(
                        "select s from Shipment s where s.shippedAt >= :b and s.shippedAt < :son order by s.shippedAt, s.id",
                        Shipment.class).setParameter("b", a[0]).setParameter("son", a[1]).getResultList();
        Function<SaleCollection, BigDecimal> satis = c -> topla(satislar.stream().filter(s -> s.getCollection() == c).toList(),
                Shipment::getTotalAmount);
        BigDecimal kasadanCikan = odeme.apply(PaymentMethod.CASH).add(avans.apply(PaymentMethod.CASH));
        KasaOzeti kasa = new KasaOzeti(odeme.apply(PaymentMethod.CASH), odeme.apply(PaymentMethod.BANK_TRANSFER),
                odeme.apply(PaymentMethod.CHEQUE), topla(odemeler, Payment::getAmount),
                avans.apply(PaymentMethod.CASH), avans.apply(PaymentMethod.BANK_TRANSFER), avans.apply(PaymentMethod.CHEQUE),
                topla(avanslar, Advance::getAmount), kasadanCikan,
                gununNet, gununOdenen, gununNet.subtract(gununOdenen), q(tumAcik),
                satis.apply(SaleCollection.CASH), satis.apply(SaleCollection.BANK_TRANSFER), satis.apply(SaleCollection.ON_ACCOUNT),
                topla(satislar, Shipment::getTotalAmount), satis.apply(SaleCollection.CASH).subtract(kasadanCikan));
        Map<Long, String> musteriAdi = em.createQuery("select c.id, c.name from Customer c", Object[].class)
                .getResultStream().collect(Collectors.toMap(x -> (Long) x[0], x -> (String) x[1]));
        Map<Long, String> siloKodu = em.createQuery("select s.id, s.code from Silo s", Object[].class)
                .getResultStream().collect(Collectors.toMap(x -> (Long) x[0], x -> (String) x[1]));
        List<GunSonuSevkiyat> sevkiyatlar = satislar.stream().map(s -> new GunSonuSevkiyat(s.getShippedAt(), s.getShipmentNo(),
                musteriAdi.get(s.getCustomerId()), s.getVehiclePlate(), siloKodu.get(s.getSiloId()),
                siniflar.get(s.getQualityGradeId()).getCode(), s.getProductType().getAd(), s.getNetKg(), s.getUnitPrice(),
                s.getTotalAmount(), s.getCollection().getAd())).toList();

        List<GunSonuKasaHareketi> kasaHareketleri = new ArrayList<>();
        odemeler.forEach(p -> kasaHareketleri.add(new GunSonuKasaHareketi(p.getPaidAt(), "Hakediş ödemesi", p.getPaymentNo(),
                ureticiAdi.get(p.getProducerId()), p.getMethod(), p.getAmount(), p.getReferenceNo())));
        avanslar.forEach(v -> kasaHareketleri.add(new GunSonuKasaHareketi(v.getGivenAt(), "Avans", v.getAdvanceNo(),
                ureticiAdi.get(v.getProducerId()), v.getMethod(), v.getAmount(), null)));
        kasaHareketleri.sort(Comparator.comparing(GunSonuKasaHareketi::zaman));

        // Silo bazlı giren (alım) ve çıkan (sevkiyat) stok ile güncel stok
        Map<Long, BigDecimal> guncelStok = grupla("select m.siloId, sum(m.quantityKg) from StockMovement m group by m.siloId");
        List<SiloGirisi> siloGirisleri = em.createQuery("""
                        select s.code, s.name, s.dedicatedGradeId, s.id,
                        count(distinct case when m.movementType = :giris then m.lotId end),
                        coalesce(sum(case when m.movementType = :giris then m.quantityKg else 0 end), 0),
                        coalesce(sum(case when m.movementType = :cikis then -m.quantityKg else 0 end), 0)
                        from StockMovement m, Silo s where m.siloId = s.id and m.movementType in (:giris, :cikis)
                        and m.movedAt >= :b and m.movedAt < :son group by s.code, s.name, s.dedicatedGradeId, s.id order by s.code""",
                        Object[].class)
                .setParameter("giris", StockMovementType.RECEIPT).setParameter("cikis", StockMovementType.DISPATCH)
                .setParameter("b", a[0]).setParameter("son", a[1])
                .getResultStream()
                .map(r -> new SiloGirisi((String) r[0], (String) r[1], r[2] != null ? siniflar.get((Long) r[2]).getCode() : null,
                        ((Long) r[4]).intValue(), q((BigDecimal) r[5]), q(guncelStok.getOrDefault((Long) r[3], ZERO)),
                        q((BigDecimal) r[6])))
                .toList();

        // O gün emanetten satışa çevrilen fişler (teslim tarihi daha eski olabilir)
        List<EmanetDonusumu> donusumler = em.createQuery("""
                        select t from WeighTicket t where t.depositConvertedAt >= :b and t.depositConvertedAt < :son
                        order by t.depositConvertedAt, t.id""", WeighTicket.class)
                .setParameter("b", a[0]).setParameter("son", a[1]).getResultStream().map(t -> {
                    Settlement s = TartimServisi.hakedis(em, t.getId());
                    QualityInspection q = TartimServisi.analiz(em, t.getId());
                    return new EmanetDonusumu(t.getDepositConvertedAt(), t.getTicketNo(), ureticiAdi.get(t.getProducerId()),
                            t.getGrossWeighedAt(), q != null ? siniflar.get(q.getQualityGradeId()).getCode() : null, t.getNetKg(),
                            t.getUnitPrice(), s != null ? s.getNetPayable() : null);
                }).toList();

        return new GunSonuRaporu(gun, Dogrulama.simdi(), gunlukOzet(gun), kasa, urunTurleri, teslimatlar, kasaHareketleri,
                siloGirisleri, sevkiyatlar, donusumler);
    }

    // ─── Günlük özet ───

    public GunlukOzet gunlukOzet(LocalDate gun) {
        OffsetDateTime[] a = Dogrulama.gunAraligi(gun);
        List<WeighTicket> tartimlar = em.createQuery("""
                        select t from WeighTicket t where t.grossWeighedAt >= :b and t.grossWeighedAt < :s
                        and t.status in :durumlar""", WeighTicket.class)
                .setParameter("b", a[0]).setParameter("s", a[1])
                .setParameter("durumlar", List.of(TicketStatus.GRADED, TicketStatus.SETTLED)).getResultList();
        List<WeighTicket> emanetler = em.createQuery("""
                        select t from WeighTicket t where t.grossWeighedAt >= :b and t.grossWeighedAt < :s and t.status = :emanet""",
                        WeighTicket.class)   // hâlâ emanette olanlar; satışa çevrilenler alım (GRADED/SETTLED) içinde sayılır
                .setParameter("b", a[0]).setParameter("s", a[1]).setParameter("emanet", TicketStatus.DEPOSITED).getResultList();
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
                topla(odemeler, Payment::getAmount), odemeler.size(), topla(avanslar, Advance::getAmount), kirilim,
                emanetler.size(), topla(emanetler, WeighTicket::getNetKg));
    }
}
