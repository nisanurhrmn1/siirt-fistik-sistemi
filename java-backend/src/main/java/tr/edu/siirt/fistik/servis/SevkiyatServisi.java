package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.*;
import tr.edu.siirt.fistik.api.Sevkiyatlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.DogrulamaHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi;
import tr.edu.siirt.fistik.kural.Kurallar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import static java.math.BigDecimal.ZERO;

/** Satış ve sevkiyat: müşteri bul/oluştur → silo kilidi → kantar kaydı → lot bazında stok düşümü. */
@Service
public class SevkiyatServisi {

    private final EntityManager em;
    private final Kurallar kurallar;

    public SevkiyatServisi(EntityManager em, Kurallar kurallar) {
        this.em = em;
        this.kurallar = kurallar;
    }

    private static final Locale TURKCE = Locale.forLanguageTag("tr");

    /** Karşılaştırma anahtarı: Türkçe küçük harf (I→ı, İ→i) ve tek boşluk. "ANTEP FISTIKÇILIK" = "Antep Fıstıkçılık". */
    static String anahtar(String ad) {
        return ad.strip().replaceAll("\\s+", " ").toLowerCase(TURKCE);
    }

    /** Aynı adlı (Türkçe büyük/küçük harf ve boşluk farkı yok) müşteri varsa onu kullanır; yoksa oluşturur. */
    private Customer musteri(String ad, String vergiNo) {
        String a = anahtar(ad);
        Customer c = em.createQuery("select c from Customer c", Customer.class).getResultStream()
                .filter(x -> anahtar(x.getName()).equals(a)).findFirst().orElse(null);
        if (c == null) {
            c = new Customer();
            c.setName(ad.strip().replaceAll("\\s+", " "));
            c.setTaxNumber(vergiNo);
            em.persist(c);
        } else if (c.getTaxNumber() == null && vergiNo != null) {
            c.setTaxNumber(vergiNo);
        }
        return c;
    }

    @Transactional
    public SevkiyatYaniti kaydet(SevkiyatGirisi g, Long kullaniciId) {
        // Silo kilidi: aynı silodan eşzamanlı iki sevkiyat aynı stoğu kullanamaz
        Silo silo = em.find(Silo.class, g.siloId(), LockModeType.PESSIMISTIC_WRITE);
        if (silo == null) throw new BulunamadiHatasi("Silo bulunamadı (id=%d).".formatted(g.siloId()));
        if (!silo.isActive()) throw new IsKuraliHatasi(silo.getCode() + " pasif.");
        QualityGrade sinif = kurallar.siloSinifi(silo);
        BigDecimal net = g.brutKg().subtract(g.daraKg());
        BigDecimal stok = kurallar.siloStogu(silo.getId());
        if (stok.compareTo(net) < 0)
            throw new IsKuraliHatasi("%s silosunda %s stok var; %s çıkış yapılamaz.".formatted(
                    silo.getCode(), Dogrulama.kg(stok), Dogrulama.kg(net)));

        OffsetDateTime an = Dogrulama.simdi();
        Shipment s = new Shipment();
        s.setShipmentNo(kurallar.sonrakiBelgeNo("SV", an));
        s.setCustomerId(musteri(g.musteriAdi(), g.musteriVergiNo()).getId());
        s.setSiloId(silo.getId());
        s.setQualityGradeId(sinif.getId());
        s.setProductType(sinif.getProductType());
        s.setVehiclePlate(Dogrulama.plakaNormalize(g.plaka()));
        s.setDriverName(g.soforAdi());
        s.setGrossKg(g.brutKg());
        s.setTareKg(g.daraKg());
        s.setGrossSource(g.brutKaynagi());
        s.setTareSource(g.daraKaynagi());
        s.setUnitPrice(g.birimFiyat());
        s.setTotalAmount(Dogrulama.para(net.multiply(g.birimFiyat())));
        s.setCollection(g.tahsilat());
        s.setShippedAt(an);
        s.setNote(g.aciklama());
        s.setCreatedById(kullaniciId);
        em.persist(s);
        em.flush();
        kurallar.silodanSevkEt(silo, net, s.getId(), kullaniciId, an);
        em.refresh(s);
        return yanit(s);
    }

    @Transactional(readOnly = true)
    public SevkiyatYaniti getir(Long id) {
        Shipment s = em.find(Shipment.class, id);
        if (s == null) throw new BulunamadiHatasi("Sevkiyat bulunamadı (id=%d).".formatted(id));
        return yanit(s);
    }

    @Transactional(readOnly = true)
    public List<SevkiyatYaniti> listele(LocalDate gun) {
        var q = gun != null
                ? em.createQuery("select s from Shipment s where s.shippedAt >= :b and s.shippedAt < :son order by s.shippedAt desc, s.id desc",
                        Shipment.class).setParameter("b", Dogrulama.gunAraligi(gun)[0]).setParameter("son", Dogrulama.gunAraligi(gun)[1])
                : em.createQuery("select s from Shipment s order by s.shippedAt desc, s.id desc", Shipment.class).setMaxResults(100);
        return q.getResultStream().map(this::yanit).toList();
    }

    private MusteriYaniti musteriYaniti(Customer c) {
        Object[] t = em.createQuery("""
                        select count(s), coalesce(sum(s.netKg), 0), coalesce(sum(s.totalAmount), 0),
                        coalesce(sum(case when s.collection = :vadeli then s.totalAmount else 0 end), 0), max(s.shippedAt)
                        from Shipment s where s.customerId = :c""", Object[].class)
                .setParameter("c", c.getId()).setParameter("vadeli", SaleCollection.ON_ACCOUNT).getSingleResult();
        Object[] th = em.createQuery("select coalesce(sum(p.amount), 0), max(p.receivedAt) from CustomerPayment p where p.customerId = :c",
                Object[].class).setParameter("c", c.getId()).getSingleResult();
        BigDecimal toplam = (BigDecimal) t[2], vadeli = (BigDecimal) t[3], tahsilat = (BigDecimal) th[0];
        BigDecimal pesin = toplam.subtract(vadeli);
        OffsetDateTime son = t[4] == null ? (OffsetDateTime) th[1]
                : th[1] == null || ((OffsetDateTime) t[4]).isAfter((OffsetDateTime) th[1]) ? (OffsetDateTime) t[4] : (OffsetDateTime) th[1];
        return new MusteriYaniti(c.getId(), c.getName(), c.getTaxNumber(), ((Long) t[0]).intValue(), (BigDecimal) t[1],
                toplam, vadeli.subtract(tahsilat), pesin.add(tahsilat), vadeli, tahsilat, son);
    }

    @Transactional(readOnly = true)
    public List<MusteriYaniti> musteriler() {
        return em.createQuery("select c from Customer c where c.active = true order by c.name", Customer.class).getResultStream()
                .map(this::musteriYaniti).toList();
    }

    private Customer musteriBul(Long id) {
        Customer c = em.find(Customer.class, id);
        if (c == null) throw new BulunamadiHatasi("Alıcı bulunamadı (id=%d).".formatted(id));
        return c;
    }

    @Transactional
    public TahsilatYaniti tahsilatEkle(Long musteriId, TahsilatGirisi g, Long kullaniciId) {
        Customer c = em.find(Customer.class, musteriId, LockModeType.PESSIMISTIC_WRITE);
        if (c == null) throw new BulunamadiHatasi("Alıcı bulunamadı (id=%d).".formatted(musteriId));
        OffsetDateTime simdi = Dogrulama.simdi();
        LocalDate bugun = Dogrulama.bugunTr();
        if (g.tarih() != null && g.tarih().isAfter(bugun))
            throw new DogrulamaHatasi("tarih", "Gelecek tarihli tahsilat girilemez.");
        // Bugün ise şu an; geçmiş bir gün ise o günün öğlesi (Türkiye saati)
        OffsetDateTime an = g.tarih() == null || g.tarih().equals(bugun) ? simdi
                : g.tarih().atTime(12, 0).atOffset(Dogrulama.TR);
        CustomerPayment p = new CustomerPayment();
        p.setReceiptNo(kurallar.sonrakiBelgeNo("TH", simdi));
        p.setCustomerId(c.getId());
        p.setAmount(g.tutar());
        p.setMethod(g.odemeYontemi());
        p.setReceivedAt(an);
        p.setReferenceNo(g.referansNo());
        p.setNote(g.aciklama());
        p.setCreatedById(kullaniciId);
        em.persist(p);
        em.flush();
        return new TahsilatYaniti(p.getId(), p.getReceiptNo(), c.getId(), c.getName(), p.getAmount(), p.getMethod(),
                p.getReceivedAt(), p.getReferenceNo(), p.getNote(), musteriYaniti(c).acikHesap());
    }

    /** Sevkiyatlar (borç) ve tahsilatlar (alacak); peşin tahsil edilen sevkiyatın tahsilatı aynı anda alacak yazılır. */
    @Transactional(readOnly = true)
    public MusteriEkstre musteriEkstre(Long musteriId, LocalDate baslangic, LocalDate bitis) {
        Customer c = musteriBul(musteriId);
        if (baslangic != null && bitis != null && baslangic.isAfter(bitis))
            throw new IsKuraliHatasi("baslangic tarihi bitis tarihinden sonra olamaz.");
        OffsetDateTime bas = baslangic != null ? Dogrulama.gunAraligi(baslangic)[0] : null;
        OffsetDateTime son = bitis != null ? Dogrulama.gunAraligi(bitis)[1] : null;

        record Kayit(OffsetDateTime tarih, int sira, MusteriHareket h) {
        }
        List<Kayit> tum = new java.util.ArrayList<>();
        for (Shipment s : em.createQuery("select s from Shipment s where s.customerId = :c", Shipment.class)
                .setParameter("c", c.getId()).getResultList()) {
            QualityGrade g = em.find(QualityGrade.class, s.getQualityGradeId());
            String aciklama = "%s · %s %s · %s".formatted(s.getVehiclePlate(), s.getProductType().getAd(), g.getCode(),
                    em.find(Silo.class, s.getSiloId()).getCode());
            tum.add(new Kayit(s.getShippedAt(), 0, new MusteriHareket(s.getShippedAt(), "SATIS", "Satış (sevkiyat)",
                    s.getShipmentNo(), aciklama, s.getNetKg(), s.getUnitPrice(), s.getTotalAmount(), ZERO, null, null)));
            if (s.getCollection() != SaleCollection.ON_ACCOUNT) {
                PaymentMethod y = s.getCollection() == SaleCollection.CASH ? PaymentMethod.CASH : PaymentMethod.BANK_TRANSFER;
                tum.add(new Kayit(s.getShippedAt(), 1, new MusteriHareket(s.getShippedAt(), "PESIN", "Peşin tahsilat",
                        s.getShipmentNo(), s.getCollection().getAd(), null, null, ZERO, s.getTotalAmount(), null, y)));
            }
        }
        for (CustomerPayment p : em.createQuery("select p from CustomerPayment p where p.customerId = :c", CustomerPayment.class)
                .setParameter("c", c.getId()).getResultList()) {
            tum.add(new Kayit(p.getReceivedAt(), 2, new MusteriHareket(p.getReceivedAt(), "TAHSILAT", "Tahsilat", p.getReceiptNo(),
                    java.util.stream.Stream.of(p.getReferenceNo(), p.getNote()).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.joining(" · ")), null, null, ZERO, p.getAmount(), null, p.getMethod())));
        }
        tum.sort(java.util.Comparator.comparing(Kayit::tarih).thenComparing(Kayit::sira));

        BigDecimal devir = ZERO, yuruyen;
        List<MusteriHareket> hareketler = new java.util.ArrayList<>();
        for (Kayit k : tum) if (bas != null && k.tarih().isBefore(bas)) devir = devir.add(k.h().borc()).subtract(k.h().alacak());
        yuruyen = devir;
        for (Kayit k : tum) {
            if ((bas != null && k.tarih().isBefore(bas)) || (son != null && !k.tarih().isBefore(son))) continue;
            MusteriHareket h = k.h();
            yuruyen = yuruyen.add(h.borc()).subtract(h.alacak());
            hareketler.add(new MusteriHareket(h.tarih(), h.tur(), h.turAdi(), h.belgeNo(), h.aciklama(), h.kg(), h.birimFiyat(),
                    h.borc(), h.alacak(), yuruyen, h.odemeYontemi()));
        }
        return new MusteriEkstre(musteriYaniti(c), bas, son, devir, hareketler);
    }

    SevkiyatYaniti yanit(Shipment s) {
        Customer c = em.find(Customer.class, s.getCustomerId());
        Silo silo = em.find(Silo.class, s.getSiloId());
        QualityGrade g = em.find(QualityGrade.class, s.getQualityGradeId());
        User u = em.find(User.class, s.getCreatedById());
        List<SevkiyatLotu> lotlar = em.createQuery("""
                        select l.lotNo, t.ticketNo, p.fullName, -sum(m.quantityKg), t.status from StockMovement m, Lot l, WeighTicket t, Producer p
                        where m.shipmentId = :s and m.lotId = l.id and l.ticketId = t.id and t.producerId = p.id
                        group by l.lotNo, t.ticketNo, p.fullName, l.id, t.status order by l.id""", Object[].class)
                .setParameter("s", s.getId()).getResultStream()
                .map(r -> new SevkiyatLotu((String) r[0], (String) r[1], (String) r[2], (BigDecimal) r[3],
                        r[4] == TicketStatus.DEPOSITED)).toList();
        return new SevkiyatYaniti(s.getId(), s.getShipmentNo(), s.getShippedAt(), c.getId(), c.getName(), c.getTaxNumber(),
                s.getVehiclePlate(), s.getDriverName(), silo.getId(), silo.getCode(), silo.getName(), g.getCode(), g.getName(),
                s.getProductType(), s.getProductType().getAd(), s.getGrossKg(), s.getTareKg(), s.getNetKg(),
                s.getGrossSource(), s.getTareSource(), s.getUnitPrice(), s.getTotalAmount(), s.getCollection(),
                s.getCollection().getAd(), u != null ? u.getFullName() : null, s.getNote(), lotlar,
                kurallar.siloStogu(silo.getId()).max(ZERO));
    }
}
