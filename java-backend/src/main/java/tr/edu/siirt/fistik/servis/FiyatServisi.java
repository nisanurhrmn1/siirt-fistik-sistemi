package tr.edu.siirt.fistik.servis;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.edu.siirt.fistik.alan.DailyPrice;
import tr.edu.siirt.fistik.alan.PriceStatus;
import tr.edu.siirt.fistik.alan.QualityGrade;
import tr.edu.siirt.fistik.api.Istekler.FiyatGirisi;
import tr.edu.siirt.fistik.api.Istekler.FiyatKalemi;
import tr.edu.siirt.fistik.api.Yanitlar.FiyatYaniti;
import tr.edu.siirt.fistik.api.Yanitlar.GuncelFiyatKalemi;
import tr.edu.siirt.fistik.api.Yanitlar.GuncelFiyatlar;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.BulunamadiHatasi;
import tr.edu.siirt.fistik.kural.Kurallar;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FiyatServisi {

    private final EntityManager em;
    private final Kurallar kurallar;

    public FiyatServisi(EntityManager em, Kurallar kurallar) {
        this.em = em;
        this.kurallar = kurallar;
    }

    private FiyatYaniti yanit(DailyPrice p) {
        QualityGrade g = em.find(QualityGrade.class, p.getQualityGradeId());
        return new FiyatYaniti(p.getId(), g.getCode(), g.getName(), p.getPricePerKg(), p.getCurrency(), p.getValidFrom(),
                p.getValidTo(), p.getStatus(), p.getApprovedAt());
    }

    /**
     * Fiyatları girer ve onaylar. Aynı sınıfın açık fiyatı yeni fiyatın başladığı an kapanır;
     * daha önce yapılmış tartımların kilitli fiyatı değişmez.
     */
    @Transactional
    public List<FiyatYaniti> gir(FiyatGirisi g, Long kullaniciId) {
        OffsetDateTime simdi = Dogrulama.simdi();
        OffsetDateTime baslangic = g.gecerlilikBaslangici() != null
                ? g.gecerlilikBaslangici().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS) : simdi;
        Map<String, QualityGrade> siniflar = em.createQuery("select q from QualityGrade q where q.active = true", QualityGrade.class)
                .getResultStream().collect(Collectors.toMap(QualityGrade::getCode, Function.identity(), (a, b) -> a, TreeMap::new));
        List<DailyPrice> olusan = new ArrayList<>();
        for (FiyatKalemi k : g.fiyatlar()) {
            QualityGrade sinif = siniflar.get(k.kaliteKodu());
            if (sinif == null)
                throw new BulunamadiHatasi("'%s' kodlu aktif kalite sınıfı yok. Tanımlı sınıflar: %s."
                        .formatted(k.kaliteKodu(), String.join(", ", siniflar.keySet())));
            DailyPrice p = new DailyPrice();
            p.setQualityGradeId(sinif.getId());
            p.setPricePerKg(k.birimFiyat());
            p.setCurrency("TRY");
            p.setValidFrom(baslangic);
            p.setStatus(PriceStatus.DRAFT);
            p.setCreatedById(kullaniciId);
            p.setNote(g.aciklama());
            em.persist(p);
            em.flush();
            kurallar.fiyatOnayla(p, kullaniciId, simdi);
            olusan.add(p);
        }
        return olusan.stream().map(this::yanit).toList();
    }

    @Transactional(readOnly = true)
    public GuncelFiyatlar guncel() {
        OffsetDateTime simdi = Dogrulama.simdi();
        List<GuncelFiyatKalemi> kalemler = em.createQuery(
                        "select g from QualityGrade g where g.active = true order by g.priority", QualityGrade.class)
                .getResultStream().map(g -> {
                    DailyPrice p = kurallar.gecerliFiyat(g.getId(), simdi);
                    return new GuncelFiyatKalemi(g.getCode(), g.getName(), p != null ? yanit(p) : null);
                }).toList();
        return new GuncelFiyatlar(simdi, kalemler);
    }
}
