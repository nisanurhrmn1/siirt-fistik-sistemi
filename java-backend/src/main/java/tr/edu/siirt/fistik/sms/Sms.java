package tr.edu.siirt.fistik.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * SMS sağlayıcıları. Yeni bir sağlayıcı (ör. İletiMerkezi) için {@link SmsSaglayici} uygulanır ve
 * {@link SmsAyarlari} içinde adıyla seçilebilir hâle getirilir.
 *
 * <pre>
 *   SMS_SAGLAYICI=simulasyon   (varsayılan) gerçek SMS gönderilmez; mesaj loglanır ve panelde gösterilir
 *   SMS_SAGLAYICI=netgsm       Netgsm HTTP API; SMS_KULLANICI, SMS_SIFRE, SMS_BASLIK gerekir
 * </pre>
 * Netgsm seçilip bilgiler eksikse sistem hata vermez, simülasyona düşer.
 */
public final class Sms {

    private Sms() {
    }

    private static final Logger log = LoggerFactory.getLogger(Sms.class);

    /** Sağlayıcıdan dönen sonuç. Hata durumunda istisna fırlatılmaz; başarısız sonuç döner. */
    public record SmsSonucu(boolean basarili, String referans, String hata) {
        static SmsSonucu tamam(String referans) {
            return new SmsSonucu(true, referans, null);
        }

        static SmsSonucu basarisiz(String hata) {
            return new SmsSonucu(false, null, hata);
        }
    }

    public interface SmsSaglayici {
        /** Kayıtlarda görünen ad, ör. "netgsm". */
        String ad();

        /** false: simülasyon (gerçek SMS gitmez). */
        boolean gercek();

        /** Telefon: 05XXXXXXXXX biçiminde. */
        SmsSonucu gonder(String telefon, String mesaj);
    }

    /** Gerçek SMS göndermez: mesajı loglar. Kredi / hesap tanımlı değilken ve testlerde kullanılır. */
    public static class SimulasyonSaglayici implements SmsSaglayici {
        @Override
        public String ad() {
            return "simulasyon";
        }

        @Override
        public boolean gercek() {
            return false;
        }

        @Override
        public SmsSonucu gonder(String telefon, String mesaj) {
            log.info("SMS (simülasyon) → {}: {}", telefon, mesaj);
            return SmsSonucu.tamam("SIM-" + UUID.randomUUID().toString().substring(0, 8));
        }
    }

    /**
     * Netgsm HTTP GET API (https://www.netgsm.com.tr/dokuman). Yanıt "00 &lt;görev no&gt;" ise başarılıdır;
     * diğer kodlar hata anlamına gelir. Türkçe karakterler için dil=TR gönderilir.
     */
    public static class NetgsmSaglayici implements SmsSaglayici {
        static final String ADRES = "https://api.netgsm.com.tr/sms/send/get";
        static final Map<String, String> HATALAR = Map.of(
                "20", "Mesaj metni hatalı veya çok uzun",
                "30", "Kullanıcı adı / şifre hatalı ya da API erişim izni yok",
                "40", "Mesaj başlığı (gönderici adı) sistemde tanımlı değil",
                "70", "Hatalı sorgu: parametreler eksik veya hatalı",
                "80", "Gönderim sınırı aşıldı",
                "85", "Mükerrer gönderim sınırı aşıldı");

        private final String kullanici, sifre, baslik;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        public NetgsmSaglayici(String kullanici, String sifre, String baslik) {
            this.kullanici = kullanici;
            this.sifre = sifre;
            this.baslik = baslik;
        }

        @Override
        public String ad() {
            return "netgsm";
        }

        @Override
        public boolean gercek() {
            return true;
        }

        private static String k(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        /** "05321234567" → "5321234567" (Netgsm 10 haneli numara bekler). */
        static String netgsmNumara(String telefon) {
            String d = telefon.replaceAll("\\D", "");
            return d.length() > 10 ? d.substring(d.length() - 10) : d;
        }

        /** Netgsm yanıtı: "00 123456789" → başarılı (görev no), "30" → hata. */
        static SmsSonucu yanitiCoz(String govde) {
            String[] p = govde == null ? new String[0] : govde.strip().split("\\s+");
            if (p.length == 0 || p[0].isEmpty()) return SmsSonucu.basarisiz("Netgsm boş yanıt döndü");
            if (p[0].equals("00") || p[0].equals("01") || p[0].equals("02"))
                return SmsSonucu.tamam(p.length > 1 ? p[1] : p[0]);
            return SmsSonucu.basarisiz("Netgsm " + p[0] + ": " + HATALAR.getOrDefault(p[0], "bilinmeyen hata"));
        }

        @Override
        public SmsSonucu gonder(String telefon, String mesaj) {
            String adres = ADRES + "?usercode=" + k(kullanici) + "&password=" + k(sifre) + "&gsmno=" + k(netgsmNumara(telefon))
                    + "&message=" + k(mesaj) + "&msgheader=" + k(baslik) + "&dil=TR";
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(adres)).timeout(Duration.ofSeconds(8)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return SmsSonucu.basarisiz("Netgsm HTTP " + r.statusCode());
                return yanitiCoz(r.body());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return SmsSonucu.basarisiz("Netgsm'e bağlanılamadı: " + e.getMessage());
            }
        }
    }

    @Configuration
    public static class SmsAyarlari {
        @Bean
        public SmsSaglayici smsSaglayici(@Value("${fistik.sms.saglayici:simulasyon}") String saglayici,
                                         @Value("${fistik.sms.kullanici:}") String kullanici,
                                         @Value("${fistik.sms.sifre:}") String sifre,
                                         @Value("${fistik.sms.baslik:}") String baslik) {
            if ("netgsm".equalsIgnoreCase(saglayici)) {
                if (kullanici.isBlank() || sifre.isBlank() || baslik.isBlank()) {
                    log.warn("SMS_SAGLAYICI=netgsm ama SMS_KULLANICI / SMS_SIFRE / SMS_BASLIK eksik; SMS simülasyon modunda.");
                    return new SimulasyonSaglayici();
                }
                log.info("SMS sağlayıcı: Netgsm (başlık: {})", baslik);
                return new NetgsmSaglayici(kullanici, sifre, baslik);
            }
            if (!"simulasyon".equalsIgnoreCase(saglayici))
                log.warn("Bilinmeyen SMS_SAGLAYICI '{}'; SMS simülasyon modunda.", saglayici);
            return new SimulasyonSaglayici();
        }
    }
}
