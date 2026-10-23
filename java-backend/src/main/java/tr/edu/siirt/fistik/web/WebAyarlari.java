package tr.edu.siirt.fistik.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Web paneli ve API dokümantasyonu.
 * <ul>
 *   <li>{@code /} — yönetim paneli (static/index.html; Alpine.js + Tailwind, tümü yerel)</li>
 *   <li>{@code /docs} — Swagger UI (springdoc; jar içinden, internet gerektirmez)</li>
 *   <li>{@code /redoc} — ReDoc (static/vendor içindeki yerel kopya)</li>
 * </ul>
 */
@Configuration
public class WebAyarlari {

    @Bean
    public OpenAPI apiBilgisi() {
        return new OpenAPI().info(new Info()
                .title("Siirt Fıstığı Alım ve Üretici Ödeme Sistemi")
                .version("2.0.0")
                .description("""
                        Kantar, kalite, fiyat, hakediş, avans, ödeme ve depo yönetimi için REST API (Spring Boot).

                        Para ve ağırlık değerleri hassasiyet kaybı olmaması için JSON'da **metin** olarak döner
                        (ör. `"300000.00"`); isteklerde sayı veya metin gönderilebilir.

                        Kimlik doğrulama henüz yok: işlemi yapan kullanıcı `X-Kullanici-Id` başlığıyla verilir,
                        boşsa ilk aktif yönetici kullanılır."""))
                .tags(java.util.List.of(
                        etiket("Üreticiler", "Üretici kaydı, listeleme ve cari hesap ekstresi"),
                        etiket("Kantar ve Alım", "Tartım, kalite analizi, fiyat kilidi, hakediş ve stok girişi"),
                        etiket("Fiyat Yönetimi", "Kalite sınıfı bazlı günlük taban fiyatlar"),
                        etiket("Finans: Avans, Hakediş, Ödeme", "Avans, avans mahsubu ve parçalı/tam ödeme"),
                        etiket("Depo", "Silo ve kalite bazlı stok"),
                        etiket("Raporlar", "Günlük alım ve finans özetleri"),
                        etiket("Sistem", "Sağlık kontrolü")));
    }

    private static io.swagger.v3.oas.models.tags.Tag etiket(String ad, String aciklama) {
        return new io.swagger.v3.oas.models.tags.Tag().name(ad).description(aciklama);
    }

    @Controller
    public static class Dokumanlar {
        @GetMapping("/docs")
        public String swagger() {
            return "redirect:/swagger-ui/index.html";
        }

        @GetMapping("/redoc")
        public String redoc() {
            return "forward:/redoc.html";
        }
    }
}
