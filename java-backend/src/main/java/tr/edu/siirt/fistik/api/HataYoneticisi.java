package tr.edu.siirt.fistik.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tr.edu.siirt.fistik.api.Yanitlar.DogrulamaHatasiYaniti;
import tr.edu.siirt.fistik.api.Yanitlar.HataYaniti;
import tr.edu.siirt.fistik.kural.Hatalar.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Hata → HTTP yanıtı. Gövdeler {"hata": "..."}; doğrulama hatalarında alan bazlı Türkçe "detaylar" listesi
 * döner. Alan adları JSON'daki gibi snake_case ve iç içe yol noktalı yazılır (ör. kalite.nem_orani).
 */
@RestControllerAdvice
public class HataYoneticisi {

    private static final int DOGRULAMA = 422;

    static String alanAdi(String yol) {
        return yol.replaceAll("\\[(\\d+)]", ".$1").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static ResponseEntity<Object> hata(HttpStatus durum, String mesaj) {
        return ResponseEntity.status(durum).body(new HataYaniti(mesaj));
    }

    private static ResponseEntity<Object> dogrulama(List<AlanHatasi> detaylar) {
        return ResponseEntity.status(DOGRULAMA).body(new DogrulamaHatasiYaniti("Gönderilen veriler geçersiz.", detaylar));
    }

    @ExceptionHandler(IsKuraliHatasi.class)
    ResponseEntity<Object> isKurali(IsKuraliHatasi e) {
        return hata(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BulunamadiHatasi.class)
    ResponseEntity<Object> bulunamadi(BulunamadiHatasi e) {
        return hata(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CakismaHatasi.class)
    ResponseEntity<Object> cakisma(CakismaHatasi e) {
        return hata(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(tr.edu.siirt.fistik.kural.Hatalar.CokFazlaDenemeHatasi.class)
    ResponseEntity<Object> cokFazla(tr.edu.siirt.fistik.kural.Hatalar.CokFazlaDenemeHatasi e) {
        return hata(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(YetkiHatasi.class)
    ResponseEntity<Object> yetki(YetkiHatasi e) {
        return hata(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> butunluk(DataIntegrityViolationException e) {
        return hata(HttpStatus.CONFLICT, "Kayıt veritabanı kısıtlarını ihlal ediyor (tekrarlı veya tutarsız veri).");
    }

    @ExceptionHandler(DogrulamaHatasi.class)
    ResponseEntity<Object> dogrulamaHatasi(DogrulamaHatasi e) {
        return dogrulama(e.getDetaylar());
    }

    /** @Valid istek gövdesi: alan kısıtları. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Object> gecersizGovde(MethodArgumentNotValidException e) {
        List<AlanHatasi> d = new ArrayList<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) d.add(new AlanHatasi(alanAdi(f.getField()), f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(g -> d.add(new AlanHatasi("istek", g.getDefaultMessage())));
        return dogrulama(d);
    }

    /** Sorgu/yol parametresi kısıtları (@Min, @Max, @Pattern...). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Object> gecersizParametre(HandlerMethodValidationException e) {
        List<AlanHatasi> d = new ArrayList<>();
        e.getAllValidationResults().forEach(r -> {
            String ad = r.getMethodParameter().getParameterName();
            var istek = r.getMethodParameter().getParameterAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            if (istek != null && !istek.value().isEmpty()) ad = istek.value();
            for (var hata : r.getResolvableErrors()) d.add(new AlanHatasi(alanAdi(ad), hata.getDefaultMessage()));
        });
        return dogrulama(d);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> kisit(ConstraintViolationException e) {
        return dogrulama(e.getConstraintViolations().stream().map(v -> {
            String yol = v.getPropertyPath().toString();
            return new AlanHatasi(alanAdi(yol.substring(yol.lastIndexOf('.') + 1)), v.getMessage());
        }).toList());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> parametreTipi(MethodArgumentTypeMismatchException e) {
        return dogrulama(List.of(new AlanHatasi(alanAdi(e.getName()), tipMesaji(e.getRequiredType()))));
    }

    /** Okunamayan JSON: tanımsız alan, yanlış tip, bozuk JSON, boş gövde. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> okunamadi(HttpMessageNotReadableException e) {
        Throwable neden = e.getCause();
        if (neden instanceof UnrecognizedPropertyException u)
            return dogrulama(List.of(new AlanHatasi(yol(u), "Tanımsız alan; adını kontrol edin.")));
        if (neden instanceof InvalidFormatException f)
            return dogrulama(List.of(new AlanHatasi(yol(f), tipMesaji(f.getTargetType()))));
        if (neden instanceof JsonMappingException m && !m.getPath().isEmpty())
            return dogrulama(List.of(new AlanHatasi(yol(m), tipMesaji(null))));
        String mesaj = e.getMessage() != null && e.getMessage().startsWith("Required request body is missing")
                ? "Bu alan zorunludur." : "Geçersiz JSON.";
        return dogrulama(List.of(new AlanHatasi("istek", mesaj)));
    }

    private static String yol(JsonMappingException e) {
        return e.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : String.valueOf(r.getIndex()))
                .collect(Collectors.joining("."));
    }

    private static String tipMesaji(Class<?> tip) {
        if (tip == null) return "Geçersiz değer.";
        if (tip.isEnum())
            return "Şu değerlerden biri olmalıdır: " + Arrays.stream(tip.getEnumConstants()).map(Object::toString)
                    .collect(Collectors.joining(", ")) + ".";
        if (tip == BigDecimal.class || tip == Double.class) return "Geçerli bir sayı olmalıdır.";
        if (tip == Long.class || tip == Integer.class || tip == long.class || tip == int.class) return "Tam sayı olmalıdır.";
        if (tip == LocalDate.class) return "Geçerli bir tarih olmalıdır (YYYY-AA-GG).";
        if (tip == OffsetDateTime.class) return "Geçerli bir tarih-saat olmalıdır (ISO 8601, ör. 2026-10-22T09:00:00+03:00).";
        if (tip == Boolean.class || tip == boolean.class) return "true veya false olmalıdır.";
        return "Geçersiz değer.";
    }
}
