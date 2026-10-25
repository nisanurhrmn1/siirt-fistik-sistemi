package tr.edu.siirt.fistik.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tr.edu.siirt.fistik.alan.ProductType;
import tr.edu.siirt.fistik.alan.SaleCollection;
import tr.edu.siirt.fistik.alan.WeightSource;
import tr.edu.siirt.fistik.kural.Hatalar.AlanHatasi;
import tr.edu.siirt.fistik.servis.SevkiyatServisi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static tr.edu.siirt.fistik.api.Istekler.*;

/** Satış ve sevkiyat (depodan çıkış): istek/yanıt gövdeleri ve uç noktalar. */
public final class Sevkiyatlar {

    private Sevkiyatlar() {
    }

    public record SevkiyatGirisi(
            @NotNull(message = ZORUNLU) @Size(min = 2, max = 200, message = "2–200 karakter olmalıdır.")
            @Schema(description = "Alıcı firma; aynı adla kayıtlı müşteri varsa o kullanılır, yoksa oluşturulur",
                    example = "Antep Fıstıkçılık A.Ş.") String musteriAdi,
            @Pattern(regexp = "\\d{10,11}", message = "Vergi No (10 hane) veya TC Kimlik No (11 hane) olmalıdır.")
            @Schema(description = "İsteğe bağlı", example = "1234567890") String musteriVergiNo,
            @NotNull(message = ZORUNLU) @Gecerli(Gecerli.Tur.PLAKA) @Schema(example = "27 AF 270") String plaka,
            @Size(max = 120) String soforAdi,
            @NotNull(message = ZORUNLU) @Positive(message = POZITIF) @Schema(description = "Çıkış yapılacak silo") Long siloId,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", inclusive = false, message = POZITIF)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK)
            @Schema(description = "Dolu araç ağırlığı (kg)", example = "11700") BigDecimal brutKg,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", message = NEGATIF_OLAMAZ)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK)
            @Schema(description = "Boş araç ağırlığı (kg)", example = "8200") BigDecimal daraKg,
            @NotNull(message = ZORUNLU) @DecimalMin(value = "0", inclusive = false, message = POZITIF)
            @DecimalMax(value = "100000", message = "En fazla 100000 olabilir.") @Digits(integer = 10, fraction = 2, message = IKI_ONDALIK)
            @Schema(description = "Satış birim fiyatı (₺/kg)", example = "420") BigDecimal birimFiyat,
            @NotNull(message = ZORUNLU) @Schema(description = "CASH: nakit alındı, BANK_TRANSFER: havale alındı, ON_ACCOUNT: vadeli / açık hesap")
            SaleCollection tahsilat,
            WeightSource brutKaynagi,
            WeightSource daraKaynagi,
            @Size(max = 500) String aciklama) implements ModelKontrollu {

        public SevkiyatGirisi {
            musteriAdi = kirp(musteriAdi);
            musteriVergiNo = kirp(musteriVergiNo);
            soforAdi = kirp(soforAdi);
            aciklama = kirp(aciklama);
            brutKaynagi = brutKaynagi == null ? WeightSource.MANUAL : brutKaynagi;
            daraKaynagi = daraKaynagi == null ? WeightSource.MANUAL : daraKaynagi;
        }

        @Override
        public List<AlanHatasi> modelHatalari() {
            List<AlanHatasi> h = new ArrayList<>();
            if (daraKg.compareTo(brutKg) >= 0)
                h.add(new AlanHatasi("istek", "Dara (boş araç) brüt (dolu araç) ağırlığından küçük olmalıdır."));
            return h;
        }
    }

    /** emanet: lot üreticinin emanetiydi (satışa çevrilmemiş). */
    public record SevkiyatLotu(String lotNo, String fisNo, String uretici, BigDecimal kg, boolean emanet) {
    }

    public record MusteriYaniti(Long id, String ad, String vergiNo, int sevkiyatSayisi, BigDecimal toplamKg,
                                BigDecimal toplamTutar, BigDecimal acikHesap) {
    }

    public record SevkiyatYaniti(Long id, String sevkiyatNo, OffsetDateTime tarih, Long musteriId, String musteriAdi,
                                 String musteriVergiNo, String plaka, String soforAdi, Long siloId, String siloKodu,
                                 String siloAdi, String kaliteKodu, String kaliteAdi, ProductType urunTuru,
                                 String urunTuruAdi, BigDecimal brutKg, BigDecimal daraKg, BigDecimal netKg,
                                 WeightSource brutKaynagi, WeightSource daraKaynagi, BigDecimal birimFiyat,
                                 BigDecimal toplamTutar, SaleCollection tahsilat, String tahsilatAdi, String kantarMemuru,
                                 String aciklama, List<SevkiyatLotu> lotlar, BigDecimal siloKalanKg) {
    }

    @RestController
    @RequestMapping("/api/v1")
    @Tag(name = "Satış ve Sevkiyat", description = "Alıcıya kamyonla çıkış, stok düşümü ve sevk irsaliyesi")
    public static class SevkiyatKontrolcusu {
        private final SevkiyatServisi servis;
        private final Kontrolculer.KullaniciCozucu kullanici;

        public SevkiyatKontrolcusu(SevkiyatServisi servis, Kontrolculer.KullaniciCozucu kullanici) {
            this.servis = servis;
            this.kullanici = kullanici;
        }

        @PostMapping("/sevkiyatlar")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize(Yetki.SEVKIYAT)
        @Operation(summary = "Sevkiyat kaydet (Yönetici, Depo sorumlusu)",
                description = "Net kg kadar silo stoğu lot bazında (ilk giren ilk çıkar) düşer. Silodaki stoktan fazla çıkış 400 döner.")
        public SevkiyatYaniti kaydet(@Valid @RequestBody SevkiyatGirisi g) {
            return servis.kaydet(Kontrolculer.model(g), kullanici.mevcut());
        }

        @GetMapping("/sevkiyatlar")
        @Operation(summary = "Sevkiyatlar (tarih verilirse o gün, yoksa son 100)")
        public List<SevkiyatYaniti> listele(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tarih) {
            return servis.listele(tarih);
        }

        @GetMapping("/sevkiyatlar/{id}")
        @Operation(summary = "Sevkiyat detayı (irsaliye için)")
        public SevkiyatYaniti getir(@PathVariable Long id) {
            return servis.getir(id);
        }

        @GetMapping("/musteriler")
        @Operation(summary = "Alıcı firmalar ve satış özetleri (açık hesap alacağı dahil)")
        public List<MusteriYaniti> musteriler() {
            return servis.musteriler();
        }
    }
}
