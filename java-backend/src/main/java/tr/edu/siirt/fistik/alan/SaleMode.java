package tr.edu.siirt.fistik.alan;

/** Teslim şekli: hemen satış (fiyat tartımda kilitlenir) veya emanet (üretici ileride bozdurur). */
public enum SaleMode {
    SALE("Hemen satış"),
    DEPOSIT("Emanet");

    private final String ad;

    SaleMode(String ad) {
        this.ad = ad;
    }

    public String getAd() {
        return ad;
    }
}
