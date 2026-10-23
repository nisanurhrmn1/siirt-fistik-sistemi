package tr.edu.siirt.fistik.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proje kökündeki {@code .env} dosyasını okur (Python sürümüyle ortak dosya) ve
 * {@code DATABASE_URL=postgresql+psycopg://kullanici:sifre@host:port/db} biçimini
 * Spring'in JDBC ayarlarına çevirir. Ortamda tanımlı değişkenler .env'den önceliklidir.
 */
public final class OrtamYukleyici {

    private OrtamYukleyici() {
    }

    public static void yukle() {
        Map<String, String> env = new LinkedHashMap<>();
        for (Path aday : List.of(Path.of(".env"), Path.of("..", ".env"))) {
            if (Files.isRegularFile(aday)) {
                env.putAll(oku(aday));
                break;
            }
        }
        System.getenv().forEach((k, v) -> {
            if (k.equals("DATABASE_URL") || k.equals("JWT_SECRET")) env.put(k, v);
        });
        if (env.get("JWT_SECRET") != null && System.getProperty("fistik.jwt-anahtar") == null) {
            System.setProperty("fistik.jwt-anahtar", env.get("JWT_SECRET"));
        }
        String url = env.get("DATABASE_URL");
        if (url == null || System.getProperty("spring.datasource.url") != null || System.getenv("SPRING_DATASOURCE_URL") != null) {
            return;
        }
        URI uri = URI.create(url.replaceFirst("^postgres(ql)?(\\+\\w+)?://", "postgresql://"));
        String kullanici = null, sifre = null;
        if (uri.getUserInfo() != null) {
            String[] parca = uri.getUserInfo().split(":", 2);
            kullanici = parca[0];
            sifre = parca.length > 1 ? parca[1] : "";
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        System.setProperty("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath());
        if (kullanici != null) System.setProperty("spring.datasource.username", kullanici);
        if (sifre != null) System.setProperty("spring.datasource.password", sifre);
    }

    private static Map<String, String> oku(Path dosya) {
        Map<String, String> sonuc = new LinkedHashMap<>();
        try {
            for (String satir : Files.readAllLines(dosya, StandardCharsets.UTF_8)) {
                satir = satir.strip();
                if (satir.isEmpty() || satir.startsWith("#") || !satir.contains("=")) continue;
                String[] kv = satir.split("=", 2);
                sonuc.put(kv[0].strip(), kv[1].strip().replaceAll("^['\"]|['\"]$", ""));
            }
        } catch (IOException e) {
            throw new IllegalStateException(".env okunamadı: " + dosya, e);
        }
        return sonuc;
    }
}
