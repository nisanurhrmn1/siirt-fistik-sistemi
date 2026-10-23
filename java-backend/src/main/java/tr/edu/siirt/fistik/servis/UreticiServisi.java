package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.Producer;
import tr.edu.siirt.fistik.alan.ProducerBankAccount;
import tr.edu.siirt.fistik.api.Istekler.UreticiGirisi;
import tr.edu.siirt.fistik.api.Yanitlar.BankaHesabiYaniti;
import tr.edu.siirt.fistik.api.Yanitlar.Sayfa;
import tr.edu.siirt.fistik.api.Yanitlar.UreticiYaniti;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.CakismaHatasi;

import java.util.List;

@Service
public class UreticiServisi {

    private final EntityManager em;

    public UreticiServisi(EntityManager em) {
        this.em = em;
    }

    public Producer bul(Long id) {
        Producer p = em.find(Producer.class, id);
        if (p == null) throw new BulunamadiHatasi("Üretici bulunamadı (id=%d).".formatted(id));
        return p;
    }

    @Transactional
    public UreticiYaniti olustur(UreticiGirisi g) {
        if (g.tcKimlikNo() != null) mevcutsaHata("nationalId", g.tcKimlikNo(), "Bu TC Kimlik No");
        if (g.vergiNo() != null) mevcutsaHata("taxNumber", g.vergiNo(), "Bu Vergi No");

        Producer p = new Producer();
        p.setProducerType(g.tip());
        p.setNationalId(g.tcKimlikNo());
        p.setTaxNumber(g.vergiNo());
        p.setTaxOffice(g.vergiDairesi());
        p.setFullName(g.adSoyad());
        p.setPhone(Dogrulama.telefonNormalize(g.telefon()));
        p.setEmail(g.eposta());
        p.setAddress(g.adres());
        p.setDistrict(g.ilce());
        p.setCity(g.il());
        p.setNotes(g.notlar());
        p.setActive(true);
        em.persist(p);
        em.flush();
        if (g.bankaHesabi() != null) {
            ProducerBankAccount b = new ProducerBankAccount();
            b.setProducerId(p.getId());
            b.setBankName(g.bankaHesabi().bankaAdi());
            b.setIban(g.bankaHesabi().iban());
            b.setAccountHolder(g.bankaHesabi().hesapSahibi() != null ? g.bankaHesabi().hesapSahibi() : g.adSoyad());
            b.setDefaultAccount(true);
            b.setActive(true);
            em.persist(b);
            em.flush();
        }
        return yanit(p);
    }

    private void mevcutsaHata(String alan, String deger, String etiket) {
        em.createQuery("select p.id from Producer p where p." + alan + " = :d", Long.class).setParameter("d", deger)
                .getResultStream().findFirst().ifPresent(id -> {
                    throw new CakismaHatasi("%s ile kayıtlı bir üretici zaten var (id=%d).".formatted(etiket, id));
                });
    }

    @Transactional(readOnly = true)
    public UreticiYaniti getir(Long id) {
        return yanit(bul(id));
    }

    @Transactional(readOnly = true)
    public Sayfa<UreticiYaniti> listele(String arama, Boolean aktif, int sayfa, int boyut) {
        StringBuilder kosul = new StringBuilder(" where 1 = 1");
        if (arama != null && !arama.isBlank()) {
            kosul.append(" and (lower(p.fullName) like :a or p.nationalId like :a2 or p.taxNumber like :a2 or p.phone like :a2)");
        }
        if (aktif != null) kosul.append(" and p.active = :aktif");
        var sayac = em.createQuery("select count(p) from Producer p" + kosul, Long.class);
        var sorgu = em.createQuery("select p from Producer p" + kosul + " order by p.fullName, p.id", Producer.class);
        for (var q : List.of(sayac, sorgu)) {
            if (arama != null && !arama.isBlank()) {
                q.setParameter("a", "%" + arama.strip().toLowerCase(new java.util.Locale("tr")) + "%");
                q.setParameter("a2", "%" + arama.strip() + "%");
            }
            if (aktif != null) q.setParameter("aktif", aktif);
        }
        List<UreticiYaniti> kayitlar = sorgu.setFirstResult((sayfa - 1) * boyut).setMaxResults(boyut)
                .getResultList().stream().map(this::yanit).toList();
        return new Sayfa<>(sayac.getSingleResult(), sayfa, boyut, kayitlar);
    }

    UreticiYaniti yanit(Producer p) {
        List<BankaHesabiYaniti> hesaplar = em.createQuery(
                        "select b from ProducerBankAccount b where b.producerId = :u order by b.id", ProducerBankAccount.class)
                .setParameter("u", p.getId()).getResultStream()
                .map(b -> new BankaHesabiYaniti(b.getId(), b.getBankName(), b.getIban(), b.getAccountHolder(),
                        b.isDefaultAccount(), b.isActive()))
                .toList();
        return new UreticiYaniti(p.getId(), p.getProducerType(), p.getNationalId(), p.getTaxNumber(), p.getTaxOffice(),
                p.getFullName(), p.getPhone(), p.getEmail(), p.getAddress(), p.getDistrict(), p.getCity(), p.isActive(),
                p.getCreatedAt(), hesaplar);
    }
}
