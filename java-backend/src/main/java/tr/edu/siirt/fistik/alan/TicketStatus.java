package tr.edu.siirt.fistik.alan;

/** Teslimat (kantar fişi) yaşam döngüsü. */
public enum TicketStatus {
    GROSS_WEIGHED, NET_WEIGHED,
    /** Emanet: kalite sınıfı ve lot var, siloda duruyor; fiyat ve hakediş "satışa çevir" ile oluşur. */
    DEPOSITED,
    GRADED, SETTLED, CANCELLED
}
