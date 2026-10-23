package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Avans mahsubu: hangi avansın hangi hakedişten ne kadar düşüldüğü (FIFO). */
@Entity
@Table(name = "advance_offsets")
@Getter
@Setter
@NoArgsConstructor
public class AdvanceOffset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "advance_id")
    private Long advanceId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "amount")
    private BigDecimal amount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
