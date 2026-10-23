package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Üretici cari hesap hareketi — yalnızca eklenir (UPDATE/DELETE trigger ile yasak). */
@Entity
@Table(name = "producer_ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class ProducerLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "producer_id")
    private Long producerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type")
    private LedgerEntryType entryType;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "entry_at")
    private OffsetDateTime entryAt;

    @Column(name = "description")
    private String description;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "settlement_deduction_id")
    private Long settlementDeductionId;

    @Column(name = "advance_id")
    private Long advanceId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
