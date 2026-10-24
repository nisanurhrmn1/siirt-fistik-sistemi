package tr.edu.siirt.fistik.alan;

/** Satışın tahsilat durumu. */
public enum SaleCollection {
    CASH("Nakit alındı"),
    BANK_TRANSFER("Havale alındı"),
    ON_ACCOUNT("Vadeli / açık hesap");

    private final String ad;

    SaleCollection(String ad) {
        this.ad = ad;
    }

    public String getAd() {
        return ad;
    }
}
