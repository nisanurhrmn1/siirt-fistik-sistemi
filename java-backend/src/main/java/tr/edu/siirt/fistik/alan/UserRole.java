package tr.edu.siirt.fistik.alan;

/** Kullanıcı rolleri ve Türkçe görünen adları. */
public enum UserRole {
    ADMIN("Yönetici"),
    SCALE_OPERATOR("Kantar memuru"),
    QUALITY("Kalite sorumlusu"),
    ACCOUNTING("Muhasebe"),
    WAREHOUSE("Depo sorumlusu"),
    PRICE_MANAGER("Fiyat yöneticisi");

    private final String ad;

    UserRole(String ad) {
        this.ad = ad;
    }

    public String getAd() {
        return ad;
    }
}
