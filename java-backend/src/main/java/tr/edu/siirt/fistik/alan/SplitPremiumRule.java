package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Çatlak primi kuralı (ürün türü başına): birim fiyat = baz fiyat + (çatlak % − baz çatlak %) × puan başı ₺,
 * en fazla ± azami fark. Baz oranın altı iskonto, üstü primdir.
 */
@Entity
@Table(name = "split_premium_rules")
@Getter
@Setter
@NoArgsConstructor
public class SplitPremiumRule {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type")
    private ProductType productType;

    @Column(name = "base_split_ratio")
    private BigDecimal baseSplitRatio;

    @Column(name = "premium_per_point")
    private BigDecimal premiumPerPoint;

    @Column(name = "max_adjustment")
    private BigDecimal maxAdjustment;

    @Column(name = "is_active")
    private boolean active;

    @Column(name = "updated_by_id")
    private Long updatedById;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
