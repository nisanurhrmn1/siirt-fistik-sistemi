package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Kalite sınıfı ve otomatik sınıflandırma eşikleri (null = kriter uygulanmaz). */
@Entity
@Table(name = "quality_grades")
@Getter
@Setter
@NoArgsConstructor
public class QualityGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "min_split_ratio")
    private BigDecimal minSplitRatio;

    @Column(name = "min_yield_ratio")
    private BigDecimal minYieldRatio;

    @Column(name = "max_defect_ratio")
    private BigDecimal maxDefectRatio;

    @Column(name = "max_moisture_ratio")
    private BigDecimal maxMoistureRatio;

    @Column(name = "is_active")
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
