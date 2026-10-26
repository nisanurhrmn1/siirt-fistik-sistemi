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
    private int getMeKodu = 200;
    private String hataAciklamasi = "Bad Request: chat not found";

    @BeforeEach
    void sahteTelegram() throws IOException {
        sunucu = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sunucu.createContext("/", d -> {
            yollar.add(d.getRequestMethod() + " " + d.getRequestURI().getRawPath());
            uzunluklar.add(d.getRequestHeaders().getFirst("Content-Length"));
            govdeler.add(new String(d.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean getMe = d.getRequestURI().getRawPath().endsWith("/getMe");
            int kod = getMe ? getMeKodu : yanitKodu;
            String metin = getMe
                    ? (kod == 200 ? "{\"ok\":true,\"result\":{\"id\":1,\"is_bot\":true,\"username\":\"fistik_bot\"}}"
                                  : "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}")
                    : (kod == 200 ? "{\"ok\":true}" : "{\"ok\":false,\"description\":\"" + hataAciklamasi + "\"}");
            byte[] yanit = metin.getBytes(StandardCharsets.UTF_8);
            d.sendResponseHeaders(kod, yanit.length);
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

    // ─── Yöneticinin "Telegram'ı test et" düğmesi ───

    private TelegramBildirimServisi ayarli() {
        return new TelegramBildirimServisi("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw", "812345678", adres(), PANEL);
    }

    @Test
    void testAyarEksikseHangisininEksikOldugunuSoyler() {
        var t = new TelegramBildirimServisi("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw", "", adres(), PANEL).test();
        assertEquals("AYAR_YOK", t.durum());
        assertTrue(t.aciklama().contains("TELEGRAM_BOT_CHAT_ID") && !t.aciklama().contains("TELEGRAM_BOT_TOKEN"));
        assertTrue(yollar.isEmpty());
    }

    @Test
    void testBasariliysaBotAdiVeMaskeliSohbetDoner() {
        var t = ayarli().test();
        assertTrue(t.basarili(), t.aciklama());
        assertEquals("BASARILI", t.durum());
        assertEquals("@fistik_bot", t.botAdi());
        assertEquals("81•••••78", t.sohbet());
        assertEquals(List.of("GET /bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/getMe",
                "POST /bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/sendMessage"), yollar);
        assertTrue(govdeler.get(1).contains("Telegram bildirimi çalışıyor"));
    }

    @Test
    void testTokenHataliysaMesajGonderilmez() {
        getMeKodu = 401;
        var t = new TelegramBildirimServisi("bot-token-degil", "812345678", adres(), PANEL).test();
        assertEquals("TOKEN_HATALI", t.durum());
        assertEquals(401, t.httpKodu());
        assertEquals("Unauthorized", t.telegramYaniti());
        assertTrue(t.aciklama().contains("benzemiyor"));                    // biçim de uyarılır
        assertEquals(1, yollar.size());
    }

    @Test
    void testTelegramHatalariTurkceAciklamayaCevrilir() {
        yanitKodu = 400;
        var t = ayarli().test();
        assertEquals("SOHBET_YOK", t.durum());
        assertTrue(t.aciklama().contains("@fistik_bot") && t.aciklama().contains("/start") && t.aciklama().contains("getUpdates"));
        assertEquals("Bad Request: chat not found", t.telegramYaniti());

        yanitKodu = 403;
        hataAciklamasi = "Forbidden: bot can't initiate conversation with a user";
        assertEquals("START_YOK", ayarli().test().durum());
        hataAciklamasi = "Forbidden: bot was blocked by the user";
        assertEquals("BOT_ENGELLI", ayarli().test().durum());
    }

    @Test
    void testArtArdaBasilamaz() {
        var s = ayarli();
        assertEquals("BASARILI", s.test().durum());
        var ikinci = s.test();
        assertEquals("BEKLE", ikinci.durum());
        assertEquals(2, yollar.size());                                    // ikinci basış Telegram'a gitmedi
    }

    @Test
    void testCevaplariTokeniIcermez() {
        getMeKodu = 401;
        var t = ayarli().test();
        assertFalse(String.valueOf(t).contains("AAHdqTcv"));
    }

    @Test
    void yapistirilanTirnakVeBosluklarTemizlenir() {
        assertEquals("123:ABC", TelegramBildirimServisi.temizle("  \"123:ABC\" "));
        assertEquals("-1001234", TelegramBildirimServisi.temizle("'-1001234'"));
        assertEquals("", TelegramBildirimServisi.temizle(null));
        assertEquals("-1••••••34", TelegramBildirimServisi.maskele("-100123434"));
        assertEquals("••••", TelegramBildirimServisi.maskele("42"));
    }

    @Test
    void testteSunucuyaUlasilamazsaNedeniSoylenir() {
        String kapali = adres();
        sunucu.stop(0);
        var t = new TelegramBildirimServisi("123:ABC", "987654", kapali, PANEL).test();
        assertEquals("ULASILAMADI", t.durum());
        assertFalse(t.aciklama().contains("123:ABC"));
    }

    @Test
    void sunucuyaUlasilamazsaIstisnaFirlatilmaz() {
        String kapali = adres();
        sunucu.stop(0);
        var s = new TelegramBildirimServisi("123:ABC", "987654", kapali, PANEL);
        assertFalse(s.gonder("deneme"));
    }
}
