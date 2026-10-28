package tr.edu.siirt.fistik.api;

import tr.edu.siirt.fistik.alan.UserRole;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rol → işlem yetki matrisi. Tek kaynak: hem {@code @PreAuthorize} ifadeleri hem de panele gönderilen
 * yetki listesi buradan üretilir. Okuma işlemleri (listeler, raporlar, ekstre) giriş yapmış herkese açıktır.
 */
public final class Yetki {

    private Yetki() {
    }

    // @PreAuthorize ifadeleri (derleme zamanı sabiti olmalı)
    public static final String TARTIM = "hasAnyRole('ADMIN','SCALE_OPERATOR')";
    public static final String FIYAT = "hasAnyRole('ADMIN','PRICE_MANAGER')";
    public static final String URETICI = "hasAnyRole('ADMIN','ACCOUNTING')";
    public static final String FINANS = "hasAnyRole('ADMIN','ACCOUNTING')";
    public static final String KULLANICI = "hasRole('ADMIN')";
    public static final String SEVKIYAT = "hasAnyRole('ADMIN','WAREHOUSE')";
    public static final String RAPOR = "hasAnyRole('ADMIN','ACCOUNTING','PRICE_MANAGER')";

    /** Panelin buton kilitleri için: işlem → izinli roller. */
    public static final Map<String, Set<UserRole>> ISLEMLER = new LinkedHashMap<>();

    static {
        ISLEMLER.put("tartim", EnumSet.of(UserRole.ADMIN, UserRole.SCALE_OPERATOR));
        ISLEMLER.put("fiyat_gir", EnumSet.of(UserRole.ADMIN, UserRole.PRICE_MANAGER));
        ISLEMLER.put("uretici_ekle", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING));
        ISLEMLER.put("avans_ver", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING));
        ISLEMLER.put("odeme_yap", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING));
        ISLEMLER.put("emanet_cevir", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING));
        ISLEMLER.put("tahsilat", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING));
        ISLEMLER.put("kesinti_ayar", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING));
        ISLEMLER.put("kullanici_yonet", EnumSet.of(UserRole.ADMIN));
        ISLEMLER.put("sevkiyat", EnumSet.of(UserRole.ADMIN, UserRole.WAREHOUSE));
        ISLEMLER.put("rapor_analiz", EnumSet.of(UserRole.ADMIN, UserRole.ACCOUNTING, UserRole.PRICE_MANAGER));
    }

    public static List<String> yetkiler(UserRole rol) {
        return ISLEMLER.entrySet().stream().filter(e -> e.getValue().contains(rol)).map(Map.Entry::getKey).toList();
    }
}
