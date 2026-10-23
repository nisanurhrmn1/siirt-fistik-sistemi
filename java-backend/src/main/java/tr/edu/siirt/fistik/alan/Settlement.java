package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Hakediş: netPayable = grossAmount − deductionsTotal − advanceOffsetTotal (her zaman ≥ 0). */
@Entity
@Table(name = "settlements")
@Getter
@Setter
@NoArgsConstructor
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_no")
    private String settlementNo;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "producer_id")
    private Long producerId;

    @Column(name = "net_kg")
    private BigDecimal netKg;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "gross_amount")
    private BigDecimal grossAmount;

    @Column(name = "deductions_total")
    private BigDecimal deductionsTotal;

    @Column(name = "advance_offset_total")
    private BigDecimal advanceOffsetTotal;

    @Column(name = "net_payable")
    private BigDecimal netPayable;

    @Column(name = "paid_amount")
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SettlementStatus status;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
