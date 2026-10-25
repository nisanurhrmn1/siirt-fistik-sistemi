package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.*;
import tr.edu.siirt.fistik.api.Sevkiyatlar.*;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;
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

    @Transactional(readOnly = true)
    public List<MusteriYaniti> musteriler() {
        return em.createQuery("select c from Customer c where c.active = true order by c.name", Customer.class).getResultStream()
                .map(c -> {
                    Object[] t = em.createQuery("""
                                    select count(s), coalesce(sum(s.netKg), 0), coalesce(sum(s.totalAmount), 0),
                                    coalesce(sum(case when s.collection = :vadeli then s.totalAmount else 0 end), 0)
                                    from Shipment s where s.customerId = :c""", Object[].class)
                            .setParameter("c", c.getId()).setParameter("vadeli", SaleCollection.ON_ACCOUNT).getSingleResult();
                    return new MusteriYaniti(c.getId(), c.getName(), c.getTaxNumber(), ((Long) t[0]).intValue(),
                            (BigDecimal) t[1], (BigDecimal) t[2], (BigDecimal) t[3]);
                }).toList();
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
