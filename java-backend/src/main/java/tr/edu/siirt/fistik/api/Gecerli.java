package tr.edu.siirt.fistik.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import tr.edu.siirt.fistik.kural.Dogrulama;
import tr.edu.siirt.fistik.kural.Hatalar;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Türkiye'ye özgü alan doğrulamaları: TC Kimlik No, Vergi No, IBAN, telefon, araç plakası. Boş değer geçerlidir. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Gecerli.Dogrulayici.class)
public @interface Gecerli {

    Tur value();

    String message() default "Geçersiz değer.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    enum Tur { TC, VKN, IBAN, TELEFON, PLAKA }

    class Dogrulayici implements ConstraintValidator<Gecerli, String> {
        private Tur tur;

        @Override
        public void initialize(Gecerli a) {
            tur = a.value();
        }

        @Override
        public boolean isValid(String deger, ConstraintValidatorContext ctx) {
            if (deger == null || deger.isBlank()) return true;
            String mesaj = switch (tur) {
                case TC -> Dogrulama.tcKimlikGecerli(deger) ? null
                        : "TC Kimlik No geçersiz: 11 haneli olmalı, 0 ile başlamamalı ve kontrol basamakları tutmalı.";
                case VKN -> Dogrulama.vergiNoGecerli(deger) ? null : "Vergi No geçersiz: 10 haneli rakamdan oluşmalı.";
                case IBAN -> Dogrulama.ibanGecerli(deger) ? null
                        : "IBAN geçersiz: TR ile başlamalı, 26 karakter olmalı ve kontrol basamağı tutmalı.";
                case TELEFON -> hata(() -> Dogrulama.telefonNormalize(deger), "");
                case PLAKA -> hata(() -> Dogrulama.plakaNormalize(deger), " (örnek: 56 ABC 123)");
            };
            if (mesaj == null) return true;
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(mesaj.replace("{", "\\{").replace("$", "\\$"))
                    .addConstraintViolation();
            return false;
        }

        private static String hata(Runnable kontrol, String ek) {
            try {
                kontrol.run();
                return null;
            } catch (Hatalar.IsKuraliHatasi e) {
                return e.getMessage() + ek;
            }
        }
    }
}
