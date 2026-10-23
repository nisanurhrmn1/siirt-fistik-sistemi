package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Üreticiye teslimattan önce verilen avans. offsetAmount mahsup edilen kısımdır. */
@Entity
@Table(name = "advances")
@Getter
@Setter
@NoArgsConstructor
public class Advance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "advance_no")
    private String advanceNo;

    @Column(name = "producer_id")
    private Long producerId;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "offset_amount")
    private BigDecimal offsetAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AdvanceStatus status;

    @Column(name = "given_at")
    private OffsetDateTime givenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "method")
    private PaymentMethod method;

    @Column(name = "bank_account_id")
    private Long bankAccountId;

    @Column(name = "description")
    private String description;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
