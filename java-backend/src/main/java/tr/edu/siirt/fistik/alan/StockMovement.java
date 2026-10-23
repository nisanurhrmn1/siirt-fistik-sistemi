package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Stok hareketi (işaretli miktar). Silo/lot stoğu = Σ quantityKg. */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_id")
    private Long lotId;

    @Column(name = "silo_id")
    private Long siloId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type")
    private StockMovementType movementType;

    @Column(name = "quantity_kg")
    private BigDecimal quantityKg;

    @Column(name = "moved_at")
    private OffsetDateTime movedAt;

    @Column(name = "transfer_group")
    private String transferGroup;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
