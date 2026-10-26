package tr.edu.siirt.fistik.bildirim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Yöneticiye Telegram bildirimi: giriş ekranından hesap talebi gelince.
 * <ul>
 *   <li>Ayarlar ortam değişkeninden gelir (TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_CHAT_ID); token depoya ve loglara girmez.</li>
 *   <li>Ayar yoksa bildirim atlanır, loga uyarı yazılır; talep kaydı her durumda tamamlanır.</li>
 *   <li>Gönderim talep veritabanına işlendikten sonra (commit) ve ayrı bir iş parçacığında yapılır:
 *       Telegram yavaşlasa ya da hata verse de talep formu beklemez.</li>
 * </ul>
 */
@Service
public class TelegramBildirimServisi {

    private static final Logger log = LoggerFactory.getLogger(TelegramBildirimServisi.class);
    private static final DateTimeFormatter TARIH = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final ZoneId TURKIYE = ZoneId.of("Europe/Istanbul");

    /** Telegram'ın bir isteğe verdiği yanıt: başarı, HTTP kodu ve Telegram'ın açıklaması (ör. "Bad Request: chat not found"). */
    record Yanit(boolean basarili, Integer httpKodu, String aciklama, JsonNode govde) {
    }

    /** Yöneticinin "Telegram'ı test et" düğmesine dönen sonuç; token hiçbir alanda yer almaz. */
    public record TestSonucu(boolean basarili, String durum, String aciklama, String botAdi, String sohbet,
                             Integer httpKodu, String telegramYaniti, OffsetDateTime zaman) {
    }

    /** Hesap talebi veritabanına yazıldığında yayınlanır (KullaniciServisi.talepOlustur). */
    public record HesapTalebiOlustu(String adSoyad, String kullaniciAdi, String rol, String not, OffsetDateTime zaman) {
    }

    private final RestClient http;
    private final String apiAdresi;
    private final String token;
    private final String chatId;
    private final String panelAdresi;
    private final ObjectMapper json = new ObjectMapper();
    private volatile long sonTest;

    public TelegramBildirimServisi(@Value("${telegram.bot.token:}") String token,
                                   @Value("${telegram.bot.chat-id:}") String chatId,
                                   @Value("${telegram.bot.api-url:https://api.telegram.org}") String apiAdresi,
                                   @Value("${telegram.panel-url:https://siirt-fistik.onrender.com}") String panelAdresi) {
        this.token = temizle(token);
        this.chatId = temizle(chatId);
        this.panelAdresi = panelAdresi;
        this.apiAdresi = apiAdresi.replaceAll("/+$", "");
        var istemci = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        istemci.setReadTimeout(Duration.ofSeconds(10));
        // Gövde tamponlanır: istek Content-Length ile gider (parça parça aktarım yerine)
        this.http = RestClient.builder().requestFactory(new BufferingClientHttpRequestFactory(istemci)).build();
        if (!ayarli()) log.warn("Telegram bildirimi kapalı: TELEGRAM_BOT_TOKEN ve TELEGRAM_BOT_CHAT_ID tanımlı değil.");
        else log.info("Telegram bildirimi açık (sohbet {}).", maskele(this.chatId));
    }

    /** Render'a yapıştırırken gelen boşluk ve tırnaklar ("123:ABC") temizlenir. */
    static String temizle(String s) {
        if (s == null) return "";
        s = s.strip();
        while (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'")))
            s = s.substring(1, s.length() - 1).strip();
        return s;
    }

    /** Ekranda gösterilecek sohbet kimliği: yalnızca baştan ve sondan ikişer hane. */
    static String maskele(String s) {
        if (s.isEmpty()) return "";
        return s.length() <= 4 ? "••••" : s.substring(0, 2) + "•".repeat(Math.min(6, s.length() - 4)) + s.substring(s.length() - 2);
    }

    public boolean ayarli() {
        return !token.isEmpty() && !chatId.isEmpty();
    }

    /** Telegram HTML biçimi: kullanıcının yazdığı metin etiket olarak yorumlanmasın. */
    static String kacis(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String mesaj(HesapTalebiOlustu t, String panelAdresi) {
        String not = t.not() == null || t.not().isBlank() ? "—" : kacis(t.not());
        return """
                🔔 <b>Yeni Hesap Talebi Var!</b>
                👤 <b>Ad Soyad:</b> %s
                🔑 <b>Kullanıcı Adı:</b> %s
                💼 <b>Talep Edilen Rol:</b> %s
                📝 <b>Not:</b> %s
                ⏰ <b>Tarih:</b> %s

                👉 Onaylamak için panele gidin: %s"""
                .formatted(kacis(t.adSoyad()), kacis(t.kullaniciAdi()), kacis(t.rol()), not,
                        t.zaman().atZoneSameInstant(TURKIYE).format(TARIH), panelAdresi);
    }

    /** Hata mesajlarında token görünmesin (bağlantı hatası adresi içerir). */
    private String gizle(String s) {
        return s == null || token.isEmpty() ? s : s.replace(token, "***");
    }

    /** Commit sonrası, ayrı iş parçacığında: talep geri alınırsa (ör. kullanıcı adı alınmış) bildirim gitmez. */
    @Async("bildirimYurutucu")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void hesapTalebiGeldi(HesapTalebiOlustu t) {
        gonder(mesaj(t, panelAdresi));
    }

    /** Mesajı gönderir; hiçbir durumda istisna fırlatmaz. Başarılıysa true. */
    public boolean gonder(String metin) {
        if (!ayarli()) {
            log.warn("Telegram ayarı yok; hesap talebi bildirimi gönderilmedi.");
            return false;
        }
        Yanit y = mesajGonder(metin);
        if (y.basarili()) log.info("Telegram bildirimi gönderildi.");
        else if (y.httpKodu() != null) log.warn("Telegram bildirimi reddedildi (HTTP {}): {}", y.httpKodu(), y.aciklama());
        else log.warn("Telegram bildirimi gönderilemedi: {}", y.aciklama());
        return y.basarili();
    }

    private Yanit mesajGonder(String metin) {
        return istek("sendMessage", Map.of("chat_id", chatId, "text", metin, "parse_mode", "HTML", "disable_web_page_preview", true));
    }

    /** Bot API çağrısı (govde null ise GET). İstisna fırlatmaz; ağ hatasında httpKodu null döner. */
    private Yanit istek(String metot, Map<String, Object> govde) {
        // Adres elle kurulur: şablon değişkeni token'daki ":" işaretini %3A'ya çevirirdi
        URI adres = URI.create(apiAdresi + "/bot" + token + "/" + metot);
        try {
            String cevap = govde == null
                    ? http.get().uri(adres).retrieve().body(String.class)
                    : http.post().uri(adres).contentType(MediaType.APPLICATION_JSON).body(govde).retrieve().body(String.class);
            return new Yanit(true, 200, null, oku(cevap));
        } catch (RestClientResponseException e) {
            JsonNode g = oku(e.getResponseBodyAsString());
            String aciklama = g != null && g.hasNonNull("description") ? g.get("description").asText() : e.getStatusText();
            return new Yanit(false, e.getStatusCode().value(), gizle(aciklama), g);
        } catch (RuntimeException e) {
            return new Yanit(false, null, gizle(e.getMessage()), null);
        }
    }

    private JsonNode oku(String s) {
        try {
            return s == null || s.isBlank() ? null : json.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private TestSonucu sonuc(boolean basarili, String durum, String aciklama, String bot, Yanit y, OffsetDateTime zaman) {
        return new TestSonucu(basarili, durum, aciklama, bot, maskele(chatId),
                y == null ? null : y.httpKodu(), y == null || basarili ? null : y.aciklama(), zaman);
    }

    /**
     * Yönetici için uçtan uca deneme: ayarlar → token (getMe) → sohbete deneme mesajı.
     * Telegram'ın hatası, ne yapılacağını söyleyen Türkçe bir açıklamaya çevrilir.
     */
    public synchronized TestSonucu test() {
        OffsetDateTime simdi = OffsetDateTime.now();
        long ms = System.currentTimeMillis();
        if (ms - sonTest < 20_000)
            return sonuc(false, "BEKLE", "Az önce test edildi; 20 saniye sonra tekrar deneyin.", null, null, simdi);
        sonTest = ms;

        List<String> eksik = new ArrayList<>();
        if (token.isEmpty()) eksik.add("TELEGRAM_BOT_TOKEN");
        if (chatId.isEmpty()) eksik.add("TELEGRAM_BOT_CHAT_ID");
        if (!eksik.isEmpty())
            return sonuc(false, "AYAR_YOK", "Sunucuda tanımlı değil: " + String.join(", ", eksik)
                    + ". Render → Environment'a tam bu adla ekleyip Save Changes deyin; sunucu yeniden başlayınca geçerli olur.",
                    null, null, simdi);

        Yanit ben = istek("getMe", null);
        if (!ben.basarili()) {
            if (ben.httpKodu() == null)
                return sonuc(false, "ULASILAMADI", "Telegram'a ulaşılamadı: " + ben.aciklama(), null, ben, simdi);
            boolean bicim = token.matches("\\d+:[A-Za-z0-9_-]{30,}");
            return sonuc(false, "TOKEN_HATALI", "Telegram bu token'ı tanımadı. BotFather'dan token'ı yeniden kopyalayıp "
                    + "TELEGRAM_BOT_TOKEN'a yapıştırın (başında/sonunda boşluk olmasın)."
                    + (bicim ? "" : " Girilen değer bir bot token'ına benzemiyor: \"123456789:AAH…\" biçiminde olmalı."),
                    null, ben, simdi);
        }
        JsonNode bilgi = ben.govde() == null ? null : ben.govde().path("result");
        String bot = bilgi != null && bilgi.hasNonNull("username") ? "@" + bilgi.get("username").asText() : null;
        String kim = bot != null ? bot : "bota";

        Yanit y = mesajGonder("✅ <b>Siirt Fıstığı paneli</b>: Telegram bildirimi çalışıyor.\n⏰ "
                + simdi.atZoneSameInstant(TURKIYE).format(TARIH));
        if (y.basarili())
            return sonuc(true, "BASARILI", "Deneme mesajı gönderildi; Telegram'ı kontrol edin. Hesap talepleri bu sohbete düşecek.",
                    bot, y, simdi);
        if (y.httpKodu() == null)
            return sonuc(false, "ULASILAMADI", "Telegram'a ulaşılamadı: " + y.aciklama(), bot, y, simdi);
        String a = y.aciklama() == null ? "" : y.aciklama().toLowerCase(Locale.ROOT);
        if (a.contains("chat not found"))
            return sonuc(false, "SOHBET_YOK", "Sohbet bulunamadı. TELEGRAM_BOT_CHAT_ID yanlış olabilir ya da " + kim
                    + " Telegram'dan hiç /start yazılmamış. Bota /start yazıp tarayıcıda api.telegram.org/bot<TOKEN>/getUpdates "
                    + "adresini açın; \"chat\":{\"id\": sonrasındaki sayıyı TELEGRAM_BOT_CHAT_ID olarak kaydedin.", bot, y, simdi);
        if (a.contains("blocked"))
            return sonuc(false, "BOT_ENGELLI", "Bot bu sohbette engellenmiş. Telegram'da " + kim + " açıp engeli kaldırın ve /start yazın.",
                    bot, y, simdi);
        if (a.contains("initiate conversation"))
            return sonuc(false, "START_YOK", "Bot size ilk mesajı atamaz: Telegram'da " + kim + " açıp /start yazın, sonra tekrar test edin.",
                    bot, y, simdi);
        if (a.contains("not a member") || a.contains("kicked"))
            return sonuc(false, "GRUPTA_YOK", "Bot bu gruba üye değil: botu gruba ekleyin, sonra tekrar test edin.", bot, y, simdi);
        return sonuc(false, "HATA", "Telegram mesajı reddetti (HTTP " + y.httpKodu() + ").", bot, y, simdi);
    }

    /** Yalnızca yönetici: Kullanıcılar sekmesindeki "Telegram'ı test et" düğmesi. */
    @RestController
    @RequestMapping("/api/v1/kullanicilar")
    @PreAuthorize(tr.edu.siirt.fistik.api.Yetki.KULLANICI)
    @io.swagger.v3.oas.annotations.tags.Tag(name = "Kullanıcılar")
    public static class TestKontrolcusu {
        private final TelegramBildirimServisi servis;

        public TestKontrolcusu(TelegramBildirimServisi servis) {
            this.servis = servis;
        }

        @PostMapping("/telegram-test")
        @io.swagger.v3.oas.annotations.Operation(summary = "Telegram bildirimini test et",
                description = "Ayarları ve token'ı denetler, yapılandırılmış sohbete deneme mesajı gönderir; hata varsa nedenini döndürür. 20 saniyede bir.")
        public TestSonucu test() {
            return servis.test();
        }
    }

    /** Bildirimler için küçük, ayrı bir iş parçacığı havuzu (istek iş parçacıklarını meşgul etmez). */
    @Configuration
    @EnableAsync
    public static class Ayarlar {
        @Bean(name = "bildirimYurutucu")
        public Executor bildirimYurutucu() {
            ThreadPoolTaskExecutor y = new ThreadPoolTaskExecutor();
            y.setCorePoolSize(1);
            y.setMaxPoolSize(2);
            y.setQueueCapacity(100);
            y.setThreadNamePrefix("bildirim-");
            y.initialize();
            return y;
        }
    }
}
