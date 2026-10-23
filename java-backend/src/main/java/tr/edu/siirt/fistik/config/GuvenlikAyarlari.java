package tr.edu.siirt.fistik.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import tr.edu.siirt.fistik.api.Yanitlar.HataYaniti;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/**
 * Kimlik doğrulama ve yetkilendirme.
 *
 * <ul>
 *   <li>Giriş {@code POST /api/v1/oturum/giris} ile yapılır; yanıt olarak imzalı bir JWT (HS256) döner.</li>
 *   <li>Sonraki isteklerde {@code Authorization: Bearer <token>} gönderilir; sunucu oturum tutmaz (stateless).</li>
 *   <li>Token'daki "rol" iddiası Spring rolüne çevrilir; uç noktalar {@code @PreAuthorize} ile korunur.</li>
 *   <li>Panel, Swagger, statik dosyalar ve sağlık kontrolü herkese açıktır; API'nin geri kalanı giriş ister.</li>
 * </ul>
 * Token header'da taşındığı ve çerez kullanılmadığı için CSRF koruması gerekmez.
 */
@Configuration
@EnableMethodSecurity
public class GuvenlikAyarlari {

    private static final Logger log = LoggerFactory.getLogger(GuvenlikAyarlari.class);

    @Bean
    public PasswordEncoder sifreleyici() {
        return new BCryptPasswordEncoder();
    }

    /** İmza anahtarı .env'deki JWT_SECRET'tan; yoksa her açılışta rastgele üretilir (o zaman yeniden başlatmada oturumlar düşer). */
    @Bean
    public SecretKey jwtAnahtari(@Value("${fistik.jwt-anahtar:}") String anahtar) {
        byte[] bayt;
        if (anahtar == null || anahtar.isBlank()) {
            bayt = new byte[32];
            new SecureRandom().nextBytes(bayt);
            log.warn("JWT_SECRET tanımlı değil; geçici anahtar üretildi. Sunucu yeniden başlarsa kullanıcılar tekrar giriş yapmalı.");
        } else {
            bayt = anahtar.getBytes(StandardCharsets.UTF_8);
            if (bayt.length < 32) throw new IllegalStateException("JWT_SECRET en az 32 karakter olmalı.");
        }
        return new SecretKeySpec(bayt, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtUretici(SecretKey anahtar) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(anahtar));
    }

    @Bean
    public JwtDecoder jwtCozucu(SecretKey anahtar) {
        return NimbusJwtDecoder.withSecretKey(anahtar).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public SecurityFilterChain guvenlikZinciri(HttpSecurity http, ObjectMapper json, JdbcTemplate jdbc) throws Exception {
        // Rol token'dan değil veritabanından okunur: yönetici rolü değiştirir veya hesabı pasifleştirirse
        // bu, token süresinin (8 saat) dolmasını beklemeden bir sonraki istekte geçerli olur.
        Converter<Jwt, AbstractAuthenticationToken> rolCevirici = jwt -> {
            String rol = jdbc.query("select role from users where id = ? and is_active", rs -> rs.next() ? rs.getString(1) : null,
                    Long.valueOf(jwt.getSubject()));
            if (rol == null) throw new DisabledException("Hesap pasif veya silinmiş.");
            return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + rol)), jwt.getSubject());
        };

        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/redoc", "/redoc.html", "/docs", "/static/**", "/favicon.ico",
                                "/swagger-ui.html", "/swagger-ui/**", "/openapi.json", "/openapi.json/**", "/saglik", "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/oturum/giris", "/api/v1/oturum/demo-giris",
                                "/api/v1/oturum/hesap-talebi").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/oturum/demo-hesaplar").permitAll()
                        // Kullanıcı yönetimi ayrıca adres düzeyinde de korunur: yetkisiz istek gövde doğrulamasına hiç ulaşmaz
                        .requestMatchers("/api/v1/kullanicilar/**", "/api/v1/kullanicilar").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(rolCevirici))
                        .authenticationEntryPoint((req, res, e) -> yaz(res, json, HttpServletResponse.SC_UNAUTHORIZED,
                                "Oturum açmanız gerekiyor (oturum süresi dolmuş olabilir).")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> yaz(res, json, HttpServletResponse.SC_UNAUTHORIZED,
                                "Oturum açmanız gerekiyor."))
                        .accessDeniedHandler((req, res, ex) -> yaz(res, json, HttpServletResponse.SC_FORBIDDEN,
                                "Bu işlem için yetkiniz yok" + rolAciklamasi() + ".")))
                .build();
    }

    private static String rolAciklamasi() {
        var a = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return "";
        return a.getAuthorities().stream().map(y -> y.getAuthority().replaceFirst("^ROLE_", "")).findFirst().map(r -> {
            try {
                return " (rolünüz: " + tr.edu.siirt.fistik.alan.UserRole.valueOf(r).getAd() + ")";
            } catch (IllegalArgumentException e) {
                return "";
            }
        }).orElse("");
    }

    private static void yaz(HttpServletResponse res, ObjectMapper json, int durum, String mesaj) throws IOException {
        res.setStatus(durum);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), new HataYaniti(mesaj));
    }
}
