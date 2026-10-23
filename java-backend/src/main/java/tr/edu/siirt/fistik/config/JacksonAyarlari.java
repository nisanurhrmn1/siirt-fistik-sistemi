package tr.edu.siirt.fistik.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * JSON sözleşmesi Python sürümüyle birebir aynıdır:
 * alan adları snake_case (uretici_id), para/ağırlık değerleri hassasiyet kaybı olmasın diye metin ("300000.00"),
 * tanımsız alan gönderilirse 422.
 */
@Configuration
public class JacksonAyarlari {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonSozlesmesi() {
        SimpleModule ondalik = new SimpleModule().addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal deger, JsonGenerator gen, SerializerProvider p) throws IOException {
                gen.writeString(deger.toPlainString());
            }
        });
        return b -> b
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .modulesToInstall(ondalik)
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
