package tr.edu.siirt.fistik;

import org.junit.jupiter.api.Test;
import tr.edu.siirt.fistik.alan.DeductionCalcMethod;
import tr.edu.siirt.fistik.alan.DeductionType;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.IsKuraliHatasi;
import tr.edu.siirt.fistik.kural.Kurallar;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Veritabanı gerektirmeyen iş kuralı birim testleri.
 * API'nin uçtan uca davranışı, Python ile ortak sözleşme testleriyle doğrulanır:
 * {@code pytest --java http://127.0.0.1:8020} (bkz. README).
 */
class KurallarTest {

    @Test
    void tcKimlikAlgoritmasi() {
        assertTrue(Dogrulama.tcKimlikGecerli("10000000146"));
        assertFalse(Dogrulama.tcKimlikGecerli("10000000147"), "son kontrol basamağı yanlış");
        assertFalse(Dogrulama.tcKimlikGecerli("01234567890"), "0 ile başlayamaz");
        assertFalse(Dogrulama.tcKimlikGecerli("1234"));
    }

    @Test
    void ibanMod97() {
        assertTrue(Dogrulama.ibanGecerli("TR33 0006 1005 1978 6457 8413 26"));
        assertFalse(Dogrulama.ibanGecerli("TR000006100519786457841326"));
        assertFalse(Dogrulama.ibanGecerli("DE89370400440532013000"), "yalnızca TR IBAN");
    }

    @Test
    void plakaVeTelefonNormalizasyonu() {
        assertEquals("56ABC123", Dogrulama.plakaNormalize("56 abc 123"));
        assertThrows(IsKuraliHatasi.class, () -> Dogrulama.plakaNormalize("99 XYZ 1"));
        assertEquals("05321234567", Dogrulama.telefonNormalize("+90 532 123 45 67"));
        assertEquals("04842234567", Dogrulama.telefonNormalize("0484-223-4567"));
        assertThrows(IsKuraliHatasi.class, () -> Dogrulama.telefonNormalize("123"));
    }

    @Test
    void paraKurusaYuvarlanirVeTurkceBicimlenir() {
        assertEquals(new BigDecimal("10.01"), Dogrulama.para(new BigDecimal("10.005")));
        assertEquals("55.432,00 TL", Dogrulama.tl(new BigDecimal("55432")));
    }

    @Test
    void kesintiYontemleri() {
        BigDecimal brut = new BigDecimal("300000.00"), net = new BigDecimal("1000.00");
        assertEquals(new BigDecimal("6000.00"), Kurallar.kesintiTutari(kesinti(DeductionCalcMethod.PERCENT, "2"), brut, net)[1]);
        assertEquals(new BigDecimal("600.00"), Kurallar.kesintiTutari(kesinti(DeductionCalcMethod.PERCENT, "0.2"), brut, net)[1]);
        assertEquals(new BigDecimal("500.00"), Kurallar.kesintiTutari(kesinti(DeductionCalcMethod.PER_KG, "0.5"), brut, net)[1]);
        assertEquals(new BigDecimal("250.00"), Kurallar.kesintiTutari(kesinti(DeductionCalcMethod.FIXED, "250"), brut, net)[1]);
    }

    @Test
    void gunSiniriTurkiyeSaatidir() {
        var aralik = Dogrulama.gunAraligi(LocalDate.of(2026, 10, 22));
        assertEquals("2026-10-21T21:00Z", aralik[0].withOffsetSameInstant(java.time.ZoneOffset.UTC).toString());
        assertEquals(24, java.time.Duration.between(aralik[0], aralik[1]).toHours());
    }

    private static DeductionType kesinti(DeductionCalcMethod yontem, String oran) {
        DeductionType d = new DeductionType();
        d.setCalcMethod(yontem);
        d.setRate(new BigDecimal(oran));
        return d;
    }
}
