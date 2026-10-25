package tr.edu.siirt.fistik.sms;

import org.junit.jupiter.api.Test;
import tr.edu.siirt.fistik.sms.Sms.NetgsmSaglayici;
import tr.edu.siirt.fistik.sms.Sms.SmsSonucu;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** SMS sağlayıcı yardımcıları (ağ çağrısı yapılmaz). */
class SmsTest {

    @Test
    void netgsmBasariliYanitGorevNumarasiniDondurur() {
        SmsSonucu s = NetgsmSaglayici.yanitiCoz("00 1234567890\n");
        assertTrue(s.basarili());
        assertEquals("1234567890", s.referans());
    }

    @Test
    void netgsmHataKodlariAnlasilirMesajaCevrilir() {
        SmsSonucu s = NetgsmSaglayici.yanitiCoz("30");
        assertFalse(s.basarili());
        assertTrue(s.hata().contains("Kullanıcı adı / şifre hatalı"));
        assertTrue(NetgsmSaglayici.yanitiCoz("99").hata().contains("bilinmeyen"));
        assertFalse(NetgsmSaglayici.yanitiCoz("").basarili());
    }

    @Test
    void netgsmNumarasi10Hanedir() {
        assertEquals("5321234567", NetgsmSaglayici.netgsmNumara("05321234567"));
        assertEquals("5321234567", NetgsmSaglayici.netgsmNumara("+90 532 123 45 67"));
    }

    @Test
    void eksiBakiyeAvansBorcuOlarakYazilir() {
        assertEquals("55.432,50 TL", SmsServisi.bakiyeMetni(new BigDecimal("55432.5")));
        assertEquals("-1.000,00 TL (avans borcu)", SmsServisi.bakiyeMetni(new BigDecimal("-1000")));
    }

    @Test
    void simulasyonHerZamanBasariliVeGercekDegil() {
        var s = new Sms.SimulasyonSaglayici();
        assertFalse(s.gercek());
        assertTrue(s.gonder("05321234567", "deneme").referans().startsWith("SIM-"));
    }
}
