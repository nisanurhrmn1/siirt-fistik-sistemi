package tr.edu.siirt.fistik.alan;

/** Ürün türü: kalite sınıfları, fiyatlar ve silolar türe göre ayrıdır (yaş fıstıkta nem sınırı uygulanmaz). */
public enum ProductType {
    DRY("Kuru-kavlak"),
    FRESH("Yaş fıstık");

    private final String ad;

    ProductType(String ad) {
        this.ad = ad;
    }

    public String getAd() {
        return ad;
    }
}
