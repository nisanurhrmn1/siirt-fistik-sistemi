package tr.edu.siirt.fistik.alan;

/** Cari hesap hareket türü. (+) üreticinin alacağı, (−) borcu / ona ödenen. */
public enum LedgerEntryType {
    DELIVERY, DEDUCTION, ADVANCE, PAYMENT, ADJUSTMENT, REVERSAL
}
