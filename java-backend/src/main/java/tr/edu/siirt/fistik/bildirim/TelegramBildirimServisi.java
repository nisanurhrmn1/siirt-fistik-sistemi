package tr.edu.siirt.fistik.bildirim;

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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    /** Hesap talebi veritabanına yazıldığında yayınlanır (KullaniciServisi.talepOlustur). */
    public record HesapTalebiOlustu(String adSoyad, String kullaniciAdi, String rol, String not, OffsetDateTime zaman) {
    }

    private final RestClient http;
    private final String apiAdresi;
    private final String token;
    private final String chatId;
    private final String panelAdresi;

    public TelegramBildirimServisi(@Value("${telegram.bot.token:}") String token,
                                   @Value("${telegram.bot.chat-id:}") String chatId,
                                   @Value("${telegram.bot.api-url:https://api.telegram.org}") String apiAdresi,
                                   @Value("${telegram.panel-url:https://siirt-fistik.onrender.com}") String panelAdresi) {
        this.token = token == null ? "" : token.strip();
        this.chatId = chatId == null ? "" : chatId.strip();
        this.panelAdresi = panelAdresi;
        this.apiAdresi = apiAdresi.replaceAll("/+$", "");
        var istemci = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        istemci.setReadTimeout(Duration.ofSeconds(10));
        // Gövde tamponlanır: istek Content-Length ile gider (parça parça aktarım yerine)
        this.http = RestClient.builder().requestFactory(new BufferingClientHttpRequestFactory(istemci)).build();
        if (!ayarli()) log.warn("Telegram bildirimi kapalı: TELEGRAM_BOT_TOKEN ve TELEGRAM_BOT_CHAT_ID tanımlı değil.");
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
        try {
            // Adres elle kurulur: şablon değişkeni token'daki ":" işaretini %3A'ya çevirirdi
            http.post().uri(URI.create(apiAdresi + "/bot" + token + "/sendMessage"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", metin, "parse_mode", "HTML", "disable_web_page_preview", true))
                    .retrieve().toBodilessEntity();
            log.info("Telegram bildirimi gönderildi.");
            return true;
        } catch (RestClientResponseException e) {
            log.warn("Telegram bildirimi reddedildi (HTTP {}): {}", e.getStatusCode().value(), gizle(e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            log.warn("Telegram bildirimi gönderilemedi: {}", gizle(e.getMessage()));
        }
        return false;
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
