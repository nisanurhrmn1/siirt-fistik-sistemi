package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;

/** Satış ve sevkiyat: alıcıya kamyonla çıkış. Net kg kadar silo stoğu düşer (lot bazında, ilk giren ilk çıkar). */
@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_no")
    private String shipmentNo;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "silo_id")
    private Long siloId;

    @Column(name = "quality_grade_id")
    private Long qualityGradeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type")
    private ProductType productType;

    @Column(name = "vehicle_plate")
    private String vehiclePlate;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "gross_kg")
    private BigDecimal grossKg;

    /** Boş araç ağırlığı. */
    @Column(name = "tare_kg")
    private BigDecimal tareKg;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})  // gross_kg - tare_kg, veritabanında hesaplanır
    @Column(name = "net_kg", insertable = false, updatable = false)
    private BigDecimal netKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "gross_source")
    private WeightSource grossSource = WeightSource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "tare_source")
    private WeightSource tareSource = WeightSource.MANUAL;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection")
    private SaleCollection collection;

    @Column(name = "shipped_at")
    private OffsetDateTime shippedAt;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
