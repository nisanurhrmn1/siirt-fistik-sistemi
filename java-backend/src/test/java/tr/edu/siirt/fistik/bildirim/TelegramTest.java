package tr.edu.siirt.fistik.bildirim;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tr.edu.siirt.fistik.bildirim.TelegramBildirimServisi.HesapTalebiOlustu;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Telegram bildirimi: mesaj biçimi, ayarsız çalışma ve sahte Telegram sunucusuna gönderim (internete çıkılmaz). */
class TelegramTest {

    private static final String PANEL = "https://siirt-fistik.onrender.com";
    private static final HesapTalebiOlustu TALEP = new HesapTalebiOlustu("Ayşe Kaya", "ayse.kaya", "Kantar memuru",
            "Kurtalan kantarında çalışıyorum.", OffsetDateTime.parse("2026-10-26T07:30:00Z"));

    private HttpServer sunucu;
    private final List<String> yollar = new ArrayList<>();
    private final List<String> govdeler = new ArrayList<>();
    private final List<String> uzunluklar = new ArrayList<>();
    private int yanitKodu = 200;

    @BeforeEach
    void sahteTelegram() throws IOException {
        sunucu = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sunucu.createContext("/", d -> {
            yollar.add(d.getRequestMethod() + " " + d.getRequestURI().getRawPath());
            uzunluklar.add(d.getRequestHeaders().getFirst("Content-Length"));
            govdeler.add(new String(d.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] yanit = (yanitKodu == 200 ? "{\"ok\":true}" : "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}")
                    .getBytes(StandardCharsets.UTF_8);
            d.sendResponseHeaders(yanitKodu, yanit.length);
            d.getResponseBody().write(yanit);
            d.close();
        });
        sunucu.start();
    }

    @AfterEach
    void kapat() {
        sunucu.stop(0);
    }

    private String adres() {
        return "http://127.0.0.1:" + sunucu.getAddress().getPort();
    }

    @Test
    void mesajIstenenBicimdeVeTurkiyeSaatiyle() {
        String m = TelegramBildirimServisi.mesaj(TALEP, PANEL);
        assertTrue(m.startsWith("🔔 <b>Yeni Hesap Talebi Var!</b>"));
        assertTrue(m.contains("👤 <b>Ad Soyad:</b> Ayşe Kaya"));
        assertTrue(m.contains("🔑 <b>Kullanıcı Adı:</b> ayse.kaya"));
        assertTrue(m.contains("💼 <b>Talep Edilen Rol:</b> Kantar memuru"));
        assertTrue(m.contains("📝 <b>Not:</b> Kurtalan kantarında çalışıyorum."));
        assertTrue(m.contains("⏰ <b>Tarih:</b> 26.10.2026 10:30"));          // UTC 07:30 → Türkiye 10:30
        assertTrue(m.endsWith("👉 Onaylamak için panele gidin: " + PANEL));
    }

    @Test
    void kullanicininYazdigiHtmlEtiketOlarakYorumlanmaz() {
        var t = new HesapTalebiOlustu("<b>Sahte</b> & Co", "x", "Muhasebe", "<a href=\"http://kotu\">tıkla</a>", OffsetDateTime.now());
        String m = TelegramBildirimServisi.mesaj(t, PANEL);
        assertTrue(m.contains("&lt;b&gt;Sahte&lt;/b&gt; &amp; Co"));
        assertTrue(m.contains("&lt;a href=\"http://kotu\"&gt;tıkla&lt;/a&gt;"));
        assertFalse(m.contains("<a "));
    }

    @Test
    void notBossaTireYazilir() {
        var t = new HesapTalebiOlustu("Ali Veli", "ali", "Muhasebe", null, OffsetDateTime.now());
        assertTrue(TelegramBildirimServisi.mesaj(t, PANEL).contains("📝 <b>Not:</b> —"));
    }

    @Test
    void ayarYoksaIstekAtilmazVeHataFirlatilmaz() {
        var s = new TelegramBildirimServisi("", "", adres(), PANEL);
        assertFalse(s.ayarli());
        assertFalse(s.gonder("deneme"));
        s.hesapTalebiGeldi(TALEP);                       // olay dinleyicisi de sessizce geçer
        assertTrue(yollar.isEmpty());
        assertFalse(new TelegramBildirimServisi("123:ABC", " ", adres(), PANEL).ayarli());
    }

    @Test
    void ayarliysaSendMessageUcunaHtmlMesajGonderilir() {
        var s = new TelegramBildirimServisi("123:ABC", "987654", adres(), PANEL);
        s.hesapTalebiGeldi(TALEP);
        assertEquals(List.of("POST /bot123:ABC/sendMessage"), yollar);
        assertNotNull(uzunluklar.get(0), "Content-Length başlığı olmalı");
        String g = govdeler.get(0);
        assertTrue(g.contains("\"chat_id\":\"987654\""));
        assertTrue(g.contains("\"parse_mode\":\"HTML\""));
        assertTrue(g.contains("Yeni Hesap Talebi Var!") && g.contains("ayse.kaya"));
    }

    @Test
    void telegramHataVerirseIstisnaFirlatilmaz() {
        yanitKodu = 400;
        var s = new TelegramBildirimServisi("123:ABC", "987654", adres(), PANEL);
        assertFalse(s.gonder("deneme"));
        assertEquals(1, yollar.size());
    }

    @Test
    void sunucuyaUlasilamazsaIstisnaFirlatilmaz() {
        String kapali = adres();
        sunucu.stop(0);
        var s = new TelegramBildirimServisi("123:ABC", "987654", kapali, PANEL);
        assertFalse(s.gonder("deneme"));
    }
}
