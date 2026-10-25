package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Teslimattan alınan numunenin kalite analizi (teslimat başına bir analiz). */
@Entity
@Table(name = "quality_inspections")
@Getter
@Setter
@NoArgsConstructor
public class QualityInspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "sample_weight_g")
    private BigDecimal sampleWeightG;

    /** Numune analizinde girilen çatlak gram (varsa oran bundan hesaplanmıştır). */
    @Column(name = "split_weight_g")
    private BigDecimal splitWeightG;

    @Column(name = "empty_weight_g")
    private BigDecimal emptyWeightG;

    @Column(name = "split_ratio")
    private BigDecimal splitRatio;

    @Column(name = "yield_ratio")
    private BigDecimal yieldRatio;

    @Column(name = "empty_ratio")
    private BigDecimal emptyRatio;

    @Column(name = "rotten_ratio")
    private BigDecimal rottenRatio;

    @Column(name = "moisture_ratio")
    private BigDecimal moistureRatio;

    @Column(name = "quality_grade_id")
    private Long qualityGradeId;

    @Column(name = "is_manual_override")
    private boolean manualOverride;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "inspected_by_id")
    private Long inspectedById;

    @Column(name = "inspected_at")
    private OffsetDateTime inspectedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
