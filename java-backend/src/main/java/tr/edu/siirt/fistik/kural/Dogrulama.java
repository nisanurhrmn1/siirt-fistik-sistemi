package tr.edu.siirt.fistik.kural;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/** Saf yardımcılar: TC/VKN/IBAN/plaka/telefon doğrulama, para yuvarlama, Türkiye saati. */
public final class Dogrulama {

    /** Türkiye 2016'dan beri kalıcı olarak UTC+3. */
    public static final ZoneOffset TR = ZoneOffset.ofHours(3);
    private static final Pattern PLAKA = Pattern.compile("(0[1-9]|[1-7]\\d|8[01])[A-Z]{1,3}\\d{2,5}");
    private static final Pattern IBAN = Pattern.compile("TR\\d{24}");
    private static final Pattern TELEFON = Pattern.compile("0[2-5]\\d{9}");

    private Dogrulama() {
    }

    /** Kuruşa yuvarlar (ROUND_HALF_UP). */
    public static BigDecimal para(BigDecimal deger) {
        return deger.setScale(2, RoundingMode.HALF_UP);
    }

    /** Veritabanının mikro saniye hassasiyetine kırpılmış UTC "şimdi". */
    public static OffsetDateTime simdi() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static LocalDate bugunTr() {
        return OffsetDateTime.now(TR).toLocalDate();
    }

    /** Türkiye saatine göre günün [başlangıç, bitiş) aralığı. */
    public static OffsetDateTime[] gunAraligi(LocalDate gun) {
        OffsetDateTime bas = gun.atStartOfDay().atOffset(TR);
        return new OffsetDateTime[]{bas, bas.plusDays(1)};
    }

    public static boolean tcKimlikGecerli(String no) {
        if (no == null || !no.matches("\\d{11}") || no.charAt(0) == '0') return false;
        int[] d = no.chars().map(c -> c - '0').toArray();
        int tek = d[0] + d[2] + d[4] + d[6] + d[8], cift = d[1] + d[3] + d[5] + d[7];
        if (Math.floorMod(tek * 7 - cift, 10) != d[9]) return false;
        int toplam = 0;
        for (int i = 0; i < 10; i++) toplam += d[i];
        return toplam % 10 == d[10];
    }

    public static boolean vergiNoGecerli(String no) {
        return no != null && no.matches("\\d{10}");
    }

    public static String ibanNormalize(String iban) {
        return iban == null ? null : iban.replace(" ", "").toUpperCase();
    }

    public static boolean ibanGecerli(String iban) {
        iban = ibanNormalize(iban);
        if (iban == null || !IBAN.matcher(iban).matches()) return false;
        String yeniden = iban.substring(4) + iban.substring(0, 4);
        StringBuilder sayi = new StringBuilder();
        for (char c : yeniden.toCharArray()) sayi.append(Character.getNumericValue(c));
        return new BigInteger(sayi.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    public static String plakaNormalize(String plaka) {
        String p = plaka == null ? "" : plaka.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        if (!PLAKA.matcher(p).matches()) throw new Hatalar.IsKuraliHatasi("Geçersiz plaka: " + plaka);
        return p;
    }

    /** '+90 532 123 45 67', '0532-123-4567', '5321234567' → '05321234567'. */
    public static String telefonNormalize(String telefon) {
        String d = telefon == null ? "" : telefon.replaceAll("[\\s()\\-.]", "");
        if (d.startsWith("+90")) d = "0" + d.substring(3);
        else if (d.startsWith("90") && d.length() == 12) d = "0" + d.substring(2);
        else if (d.length() == 10) d = "0" + d;
        if (!TELEFON.matcher(d).matches())
            throw new Hatalar.IsKuraliHatasi("Geçersiz telefon numarası: " + telefon + " (örnek: 0532 123 45 67)");
        return d;
    }

    /** 55432.5 → "55.432,50 TL" (kullanıcıya dönen mesajlar için). */
    public static String tl(BigDecimal deger) {
        String s = String.format(java.util.Locale.ROOT, "%,.2f", para(deger));
        return s.replace(",", "_").replace(".", ",").replace("_", ".") + " TL";
    }

    /** 3500 → "3.500 kg", 12.5 → "12,5 kg" (kullanıcıya dönen mesajlar için). */
    public static String kg(BigDecimal deger) {
        String s = String.format(java.util.Locale.ROOT, "%,.2f", para(deger));
        s = s.replace(",", "_").replace(".", ",").replace("_", ".");
        return s.replaceAll(",?0+$", "").replaceAll(",$", "") + " kg";
    }
}
