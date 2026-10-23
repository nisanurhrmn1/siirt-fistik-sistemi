package tr.edu.siirt.fistik;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import tr.edu.siirt.fistik.config.OrtamYukleyici;

/**
 * Siirt Fıstığı Alım ve Üretici Ödeme Sistemi — Spring Boot giriş noktası.
 *
 * <pre>
 *   java-backend\mvnw.cmd spring-boot:run      (geliştirme)
 *   Panel: http://127.0.0.1:8000/   Swagger: http://127.0.0.1:8000/docs
 * </pre>
 */
@SpringBootApplication
public class SiirtFistikApplication {

    public static void main(String[] args) {
        OrtamYukleyici.yukle();   // proje kökündeki .env → veritabanı bağlantısı
        SpringApplication.run(SiirtFistikApplication.class, args);
    }
}
