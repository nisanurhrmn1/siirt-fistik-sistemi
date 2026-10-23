package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Yıl bazlı belge numarası sayacı (KF-2026-000001, LOT-…, HK-…, AV-…, OD-…); satır kilidiyle artırılır. */
@Entity
@Table(name = "document_sequences")
@IdClass(DocumentSequence.Anahtar.class)
@Getter
@Setter
@NoArgsConstructor
public class DocumentSequence {

    @Id
    @Column(name = "doc_type")
    private String docType;

    @Id
    @Column(name = "year")
    private Integer year;

    @Column(name = "last_value")
    private Integer lastValue;

    /** Bileşik birincil anahtar (doc_type, year). */
    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Anahtar implements Serializable {
        private String docType;
        private Integer year;

        public Anahtar(String docType, Integer year) {
            this.docType = docType;
            this.year = year;
        }
    }
}
