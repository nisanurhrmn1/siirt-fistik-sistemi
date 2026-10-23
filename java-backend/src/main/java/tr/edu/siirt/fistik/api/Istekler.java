package tr.edu.siirt.fistik.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tr.edu.siirt.fistik.alan.PaymentMethod;
import tr.edu.siirt.fistik.alan.ProducerType;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar.AlanHatasi;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * İstek gövdeleri (JSON alan adları snake_case). Alan kısıtları Bean Validation ile denetlenir (422);
 * birden çok alanı ilgilendiren kurallar {@code modelHatalari()} ile, alan kısıtları geçtikten sonra denetlenir.
 */
public final class Istekler {

    private Istekler() {
    }

    static final String ZORUNLU = "Bu alan zorunludur.";
    static final String POZITIF = "0 değerinden büyük olmalıdır.";
    static final String NEGATIF_OLAMAZ = "0 veya daha büyük olmalıdır.";
    static final String IKI_ONDALIK = "En fazla 2 ondalık basamak girilebilir.";

    static String kirp(String s) {
        if (s == null) return null;
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    /** Model düzeyinde ek kontrolleri olan istekler. */
    public interface ModelKontrollu {
        List<AlanHatasi> modelHatalari();
    }

    // ─── Üretici ───

    public record BankaHesabiGirisi(
            @NotNull(message = ZORUNLU) @Size(min = 2, max = 80, message = "2–80 karakter olmalıdır.")
            @Schema(example = "Ziraat Bankası") String bankaAdi,
            @NotNull(message = ZORUNLU) @Gecerli(Gecerli.Tur.IBAN)
            @Schema(example = "TR33 0006 1005 1978 6457 8413 26") String iban,
            @Size(max = 200, message = "En fazla 200 karakter olabilir.") String hesapSahibi) {
        public BankaHesabiGirisi {
            bankaAdi = kirp(bankaAdi);
            iban = iban == null ? null : Dogrulama.ibanNormalize(iban.strip());
            hesapSahibi = kirp(hesapSahibi);
        }
    }

    public record UreticiGirisi(
            @Schema(description = "INDIVIDUAL = gerçek kişi, COMPANY = tüzel kişi") ProducerType tip,
            @Gecerli(Gecerli.Tur.TC) @Schema(example = "10000000146") String tcKimlikNo,
            @Gecerli(Gecerli.Tur.VKN) String vergiNo,
            @Size(max = 80) String vergiDairesi,
            @NotNull(message = ZORUNLU) @Size(min = 3, max = 200, message = "3–200 karakter olmalıdır.")
            @Schema(example = "Ahmet Yılmaz") String adSoyad,
            @NotNull(message = ZORUNLU) @Gecerli(Gecerli.Tur.TELEFON) @Schema(example = "0532 123 45 67") String telefon,
            @Size(max = 120) @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Biçim geçersiz.") String eposta,
            @Size(max = 500) String adres,
            @Size(max = 60) @Schema(example = "Kurtalan") String ilce,
            @Size(max = 60) String il,
            @Valid BankaHesabiGirisi bankaHesabi,
            @Size(max = 1000) String notlar) implements ModelKontrollu {

        public UreticiGirisi {
            tip = tip == null ? ProducerType.INDIVIDUAL : tip;
            tcKimlikNo = kirp(tcKimlikNo);
            vergiNo = kirp(vergiNo);
            vergiDairesi = kirp(vergiDairesi);
            adSoyad = adSoyad == null ? null : adSoyad.strip();
            telefon = telefon == null ? null : telefon.strip();
            eposta = kirp(eposta);
            adres = kirp(adres);
            ilce = kirp(ilce);
            il = kirp(il) == null ? "Siirt" : il.strip();
            notlar = kirp(notlar);
        }

        @Override
        public List<AlanHatasi> modelHatalari() {
            if (tip == ProducerType.INDIVIDUAL && tcKimlikNo == null)
                return List.of(new AlanHatasi("istek", "Gerçek kişi üretici için tc_kimlik_no zorunludur."));
            if (tip == ProducerType.COMPANY && vergiNo == null)
                return List.of(new AlanHatasi("istek", "Tüzel kişi üretici için vergi_no zorunludur."));
            return List.of();
        }
    }

    // ─── Kantar ───

    public record KaliteAnalizi(
            @DecimalMin(value = "0", inclusive = false, message = POZITIF) @DecimalMax(value = "10000", message = "En fazla 10000 olabilir.")
            @Digits(integer = 6, fraction = 2, message = IKI_ONDALIK) BigDecimal numuneGram,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ) @DecimalMax(value = "100", message = "En fazla 100 olabilir.")
            @Digits(integer = 3, fraction = 2, message = IKI_ONDALIK) @Schema(description = "Çatlak (ağzı açık) oranı %", example = "90") BigDecimal catlakOrani,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ) @DecimalMax(value = "100", message = "En fazla 100 olabilir.")
            @Digits(integer = 3, fraction = 2, message = IKI_ONDALIK) @Schema(description = "Randıman (iç oranı) %", example = "58") BigDecimal randiman,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ) @DecimalMax(value = "100", message = "En fazla 100 olabilir.")
            @Digits(integer = 3, fraction = 2, message = IKI_ONDALIK) @Schema(description = "Boş oranı %", example = "2") BigDecimal bosOrani,
            @DecimalMin(value = "0", message = NEGATIF_OLAMAZ) @DecimalMax(value = "100", message = "En fazla 100 olabilir.")
            @Digits(integer = 3, fraction = 2, message = IKI_ONDALIK) @Schema(description = "Çürük oranı %", example = "1") BigDecimal curukOrani,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ) @DecimalMax(value = "100", message = "En fazla 100 olabilir.")
            @Digits(integer = 3, fraction = 2, message = IKI_ONDALIK) @Schema(description = "Nem oranı %", example = "6") BigDecimal nemOrani) {

        public KaliteAnalizi {
            numuneGram = numuneGram == null ? new BigDecimal("500") : numuneGram;
            curukOrani = curukOrani == null ? BigDecimal.ZERO : curukOrani;
        }

        List<AlanHatasi> hatalar() {
            if (bosOrani != null && bosOrani.add(curukOrani).compareTo(BigDecimal.valueOf(100)) > 0)
                return List.of(new AlanHatasi("kalite", "Boş ve çürük oranlarının toplamı %100'ü aşamaz."));
            return List.of();
        }
    }

    public record TartimGirisi(
            @NotNull(message = ZORUNLU) @Positive(message = POZITIF) Long ureticiId,
            @NotNull(message = ZORUNLU) @Gecerli(Gecerli.Tur.PLAKA) @Schema(example = "56 ABC 123") String plaka,
            @Size(max = 120) String soforAdi,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", inclusive = false, message = POZITIF)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK)
            @Schema(description = "Dolu araç ağırlığı (kg)", example = "4800") BigDecimal brutKg,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK)
            @Schema(description = "Boş araç ağırlığı (kg)", example = "3800") BigDecimal daraKg,
            @NotNull(message = ZORUNLU) @Valid KaliteAnalizi kalite,
            @Schema(description = "Boşsa kaliteye uygun ilk silo otomatik seçilir") Long siloId,
            @Size(max = 10) String manuelKaliteKodu,
            @Size(max = 500) String manuelKaliteGerekcesi) implements ModelKontrollu {

        public TartimGirisi {
            soforAdi = kirp(soforAdi);
            manuelKaliteKodu = kirp(manuelKaliteKodu);
            manuelKaliteGerekcesi = kirp(manuelKaliteGerekcesi);
        }

        @Override
        public List<AlanHatasi> modelHatalari() {
            List<AlanHatasi> h = new ArrayList<>(kalite.hatalar());
            if (!h.isEmpty()) return h;
            if (daraKg.compareTo(brutKg) >= 0)
                h.add(new AlanHatasi("istek", "Dara ağırlığı brüt ağırlıktan küçük olmalıdır (net kg pozitif olmalı)."));
            else if (manuelKaliteKodu != null && manuelKaliteGerekcesi == null)
                h.add(new AlanHatasi("istek", "Manuel kalite ataması için manuel_kalite_gerekcesi zorunludur."));
            return h;
        }
    }

    public record OnizlemeGirisi(
            @Positive(message = POZITIF) Long ureticiId,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", inclusive = false, message = POZITIF)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK) BigDecimal brutKg,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK) BigDecimal daraKg,
            @NotNull(message = ZORUNLU) @Valid KaliteAnalizi kalite,
            @Size(max = 10) String manuelKaliteKodu) implements ModelKontrollu {

        public OnizlemeGirisi {
            manuelKaliteKodu = kirp(manuelKaliteKodu);
        }

        @Override
        public List<AlanHatasi> modelHatalari() {
            List<AlanHatasi> h = new ArrayList<>(kalite.hatalar());
            if (h.isEmpty() && daraKg.compareTo(brutKg) >= 0)
                h.add(new AlanHatasi("istek", "Dara ağırlığı brüt ağırlıktan küçük olmalıdır (net kg pozitif olmalı)."));
            return h;
        }
    }

    // ─── Fiyat ───

    public record FiyatKalemi(
            @NotNull(message = ZORUNLU) @Size(min = 1, max = 10) @Schema(example = "A") String kaliteKodu,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", inclusive = false, message = POZITIF)
            @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK) @Schema(example = "300") BigDecimal birimFiyat) {
        public FiyatKalemi {
            kaliteKodu = kaliteKodu == null ? null : kaliteKodu.strip().toUpperCase(Locale.ROOT);
        }
    }

    /** Saat dilimi verilmemiş tarih-saati Türkiye saati kabul eder (panelin datetime-local girişi). */
    public static class EsnekTarihSaat extends JsonDeserializer<OffsetDateTime> {
        @Override
        public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String s = p.getValueAsString();
            if (s == null || s.isBlank()) return null;
            try {
                return OffsetDateTime.parse(s);
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(s).atOffset(Dogrulama.TR);
                } catch (DateTimeParseException e2) {
                    return (OffsetDateTime) ctx.handleWeirdStringValue(OffsetDateTime.class, s, "geçersiz tarih-saat");
                }
            }
        }
    }

    public record FiyatGirisi(
            @NotNull(message = ZORUNLU) @Size(min = 1, message = "En az 1 öğe içermelidir.") List<@Valid FiyatKalemi> fiyatlar,
            @JsonDeserialize(using = EsnekTarihSaat.class)
            @Schema(description = "Boşsa hemen geçerli olur. Saat dilimi verilmezse Türkiye saati kabul edilir.")
            OffsetDateTime gecerlilikBaslangici,
            @Size(max = 500) String aciklama) implements ModelKontrollu {

        public FiyatGirisi {
            aciklama = kirp(aciklama);
        }

        @Override
        public List<AlanHatasi> modelHatalari() {
            var kodlar = new HashSet<String>();
            for (FiyatKalemi k : fiyatlar)
                if (!kodlar.add(k.kaliteKodu()))
                    return List.of(new AlanHatasi("fiyatlar", "Aynı kalite sınıfı listede birden fazla kez yer alamaz."));
            return List.of();
        }
    }

    // ─── Finans ───

    public record AvansGirisi(
            @NotNull(message = ZORUNLU) @Positive(message = POZITIF) Long ureticiId,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", inclusive = false, message = POZITIF)
            @Digits(integer = 12, fraction = 2, message = IKI_ONDALIK) BigDecimal tutar,
            PaymentMethod odemeYontemi,
            Long bankaHesapId,
            @Size(max = 500) String aciklama) {
        public AvansGirisi {
            odemeYontemi = odemeYontemi == null ? PaymentMethod.BANK_TRANSFER : odemeYontemi;
            aciklama = kirp(aciklama);
        }
    }

    public record OdemeGirisi(
            @DecimalMin(value = "0", inclusive = false, message = POZITIF) @Digits(integer = 12, fraction = 2, message = IKI_ONDALIK)
            @Schema(description = "Boş bırakılırsa hakedişin kalan tutarının tamamı ödenir") BigDecimal tutar,
            PaymentMethod odemeYontemi,
            Long bankaHesapId,
            @Size(max = 60) @Schema(description = "Dekont / EFT referansı") String referansNo,
            @Size(max = 500) String aciklama) {
        public OdemeGirisi {
            odemeYontemi = odemeYontemi == null ? PaymentMethod.BANK_TRANSFER : odemeYontemi;
            referansNo = kirp(referansNo);
            aciklama = kirp(aciklama);
        }
    }
}
