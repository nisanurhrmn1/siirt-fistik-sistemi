package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Kalite sınıfı bazında, zaman aralığıyla geçerli taban fiyat. Onaylı fiyat değiştirilemez. */
@Entity
@Table(name = "daily_prices")
@Getter
@Setter
@NoArgsConstructor
public class DailyPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quality_grade_id")
    private Long qualityGradeId;

    @Column(name = "price_per_kg")
    private BigDecimal pricePerKg;

    @Column(name = "currency")
    private String currency;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PriceStatus status;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "approved_by_id")
    private Long approvedById;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "note")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
