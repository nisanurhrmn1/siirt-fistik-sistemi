package tr.edu.siirt.fistik.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tr.edu.siirt.fistik.alan.Producer;
import tr.edu.siirt.fistik.api.Yanitlar.EkstreTeslimat;
import tr.edu.siirt.fistik.api.Yanitlar.UreticiEkstre;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.CokFazlaDenemeHatasi;
import tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi;
import tr.edu.siirt.fistik.servis.FinansServisi;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Halka açık "Üretici Bakiye ve Emanet Sorgulama": şifresiz, T.C. Kimlik No + kayıtlı telefonun son 4 hanesiyle.
 *
 * <ul>
 *   <li>Yalnızca o üreticinin özet bilgisi döner; T.C., telefon, IBAN ve başka hiçbir üretici verisi yanıtta yoktur.</li>
 *   <li>"Kayıt yok" ile "telefon eşleşmedi" aynı mesajı verir: hangi T.C.'nin kayıtlı olduğu anlaşılamaz.</li>
 *   <li>Son 4 hane yalnızca 10.000 olasılık olduğundan başarısız denemeler T.C. başına ve IP başına sınırlanır (429).</li>
 * </ul>
 */
public final class Sorgu {

    private Sorgu() {
    }

    private static final Logger log = LoggerFactory.getLogger(Sorgu.class);
    static final String ESLESMEDI = "T.C. Kimlik No veya telefon bilgisi eşleşmedi.";

    public record SorguGirisi(
            @NotBlank(message = "Bu alan zorunludur.") @Gecerli(Gecerli.Tur.TC) @Schema(example = "10000000146") String tcKimlikNo,
            @NotBlank(message = "Bu alan zorunludur.") @Pattern(regexp = "\\d{4}", message = "Telefonun son 4 hanesini girin.")
            @Schema(example = "2233") String telefonSon4) {
        public SorguGirisi {
            tcKimlikNo = tcKimlikNo == null ? null : tcKimlikNo.strip();
            telefonSon4 = telefonSon4 == null ? null : telefonSon4.strip();
        }
    }

    public record SorguTeslimat(OffsetDateTime tarih, String fisNo, String urunTuruAdi, String kaliteKodu, BigDecimal netKg,
                                String teslimSekli, BigDecimal netOdenecek, BigDecimal odenen, BigDecimal kalan, String durum) {
    }

    /** Üreticinin kendi özeti. Ad maskelidir ("Ahmet Y."); kimlik ve iletişim bilgisi yoktur. */
    public record UreticiSorguYaniti(String adMaskeli, OffsetDateTime sorguZamani, BigDecimal toplamTeslimKg,
                                     BigDecimal satilanKg, BigDecimal emanetKg, BigDecimal toplamAvans, BigDecimal acikAvans,
                                     BigDecimal toplamOdeme, BigDecimal netBakiye, String bakiyeDurumu,
                                     BigDecimal odenebilirTutar, List<SorguTeslimat> sonTeslimatlar) {
    }

    /** "Ahmet Yılmaz" → "Ahmet Y.", "Ayşe Nur Kaya" → "Ayşe K." */
    static String maskele(String ad) {
        String[] p = ad.strip().split("\\s+");
        if (p.length == 1) return p[0].charAt(0) + "***";
        return p[0] + " " + p[p.length - 1].charAt(0) + ".";
    }

    /** Başarısız deneme sınırlayıcı (bellekte; tek sunucu için yeterli). */
    @Component
    public static class DenemeSiniri {
        private final int tcSiniri, ipSiniri;
        private final Duration pencere;
        private final Map<String, Deque<Instant>> kayitlar = new ConcurrentHashMap<>();

        public DenemeSiniri(@Value("${fistik.sorgu.tc-deneme:5}") int tcSiniri,
                            @Value("${fistik.sorgu.ip-deneme:60}") int ipSiniri,
                            @Value("${fistik.sorgu.pencere-dakika:15}") int pencereDakika) {
            this.tcSiniri = tcSiniri;
            this.ipSiniri = ipSiniri;
            this.pencere = Duration.ofMinutes(pencereDakika);
        }

        private int say(String anahtar) {
            Deque<Instant> d = kayitlar.get(anahtar);
            if (d == null) return 0;
            synchronized (d) {
                Instant sinir = Instant.now().minus(pencere);
                while (!d.isEmpty() && d.peekFirst().isBefore(sinir)) d.pollFirst();
                return d.size();
            }
        }

        void kontrol(String tc, String ip) {
            if (say("tc:" + tc) >= tcSiniri || say("ip:" + ip) >= ipSiniri)
                throw new CokFazlaDenemeHatasi("Çok fazla hatalı deneme yapıldı. Lütfen %d dakika sonra tekrar deneyin."
                        .formatted(pencere.toMinutes()));
        }

        void basarisiz(String tc, String ip) {
            for (String a : List.of("tc:" + tc, "ip:" + ip)) {
                Deque<Instant> d = kayitlar.computeIfAbsent(a, k -> new ArrayDeque<>());
                synchronized (d) {
                    d.addLast(Instant.now());
                }
            }
        }
    }

    @Service
    public static class SorguServisi {
        private final EntityManager em;
        private final FinansServisi finans;
        private final DenemeSiniri sinir;

        public SorguServisi(EntityManager em, FinansServisi finans, DenemeSiniri sinir) {
            this.em = em;
            this.finans = finans;
            this.sinir = sinir;
        }

        @Transactional(readOnly = true)
        public UreticiSorguYaniti sorgula(SorguGirisi g, String ip) {
            sinir.kontrol(g.tcKimlikNo(), ip);
            Producer p = em.createQuery("select p from Producer p where p.nationalId = :tc and p.active = true", Producer.class)
                    .setParameter("tc", g.tcKimlikNo()).getResultStream().findFirst().orElse(null);
            String telefon = p == null || p.getPhone() == null ? "" : p.getPhone().replaceAll("\\D", "");
            if (p == null || telefon.length() < 4 || !telefon.endsWith(g.telefonSon4())) {
                sinir.basarisiz(g.tcKimlikNo(), ip);
                log.info("Üretici sorgusu eşleşmedi (ip {})", ip);   // T.C. loglanmaz
                throw new IsKuraliHatasi(ESLESMEDI);
            }
            UreticiEkstre e = finans.ekstre(p.getId(), null, null);
            var o = e.ozet();
            List<EkstreTeslimat> ts = e.teslimatlar();
            List<SorguTeslimat> son = ts.subList(Math.max(0, ts.size() - 10), ts.size()).reversed().stream()
                    .map(t -> new SorguTeslimat(t.tarih(), t.fisNo(), t.urunTuruAdi(), t.kaliteKodu(), t.netKg(),
                            t.kaynakFisNo() != null ? "Emanetten satış" : t.emanet() ? "Emanet" : "Satış",
                            t.netOdenecek(), t.odenen(), t.kalan(),
                            t.emanet() && t.hakedisId() == null
                                    ? (t.emanetKalanKg().signum() > 0 ? "Emanette" : "Tamamı satıldı")
                                    : t.hakedisDurumu() == null ? "—" : switch (t.hakedisDurumu()) {
                                        case UNPAID -> "Ödenmedi";
                                        case PARTIALLY_PAID -> "Kısmen ödendi";
                                        case PAID -> "Ödendi";
                                        case OFFSET_ONLY -> "Avansa mahsup edildi";
                                    }))
                    .toList();
            // Emanet bozdurma fişleri (EB-…) yeni teslimat değildir: teslim edilen kg yalnızca asıl fişlerden toplanır
            BigDecimal teslim = ts.stream().filter(t -> t.kaynakFisNo() == null).map(EkstreTeslimat::netKg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new UreticiSorguYaniti(maskele(p.getFullName()), Dogrulama.simdi(), teslim,
                    teslim.subtract(o.emanetStokKg()), o.emanetStokKg(), o.toplamVerilenAvans(), o.acikAvansBakiyesi(),
                    o.toplamOdeme(), o.bakiye(), o.bakiyeDurumu(), o.odenebilirTutar(), son);
        }
    }

    @RestController
    @RequestMapping("/api/v1/sorgu")
    @Tag(name = "Üretici Sorgulama")
    public static class SorguKontrolcusu {
        private final SorguServisi servis;

        public SorguKontrolcusu(SorguServisi servis) {
            this.servis = servis;
        }

        @PostMapping("/uretici")
        @Operation(summary = "Üretici bakiye ve emanet sorgusu (halka açık, giriş gerektirmez)",
                description = "T.C. Kimlik No + kayıtlı telefonun son 4 hanesi. Yalnızca o üreticinin özeti döner; "
                        + "eşleşmezse 400, T.C. başına 5 / IP başına 60 hatalı denemeden sonra 15 dakika 429.")
        public UreticiSorguYaniti sorgula(@Valid @RequestBody SorguGirisi g, HttpServletRequest istek) {
            return servis.sorgula(g, istek.getRemoteAddr());
        }
    }
}
