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

/** Kantar fişi = tek teslimat. dailyPriceId + unitPrice tartım anında kilitlenen fiyattır (snapshot). */
@Entity
@Table(name = "weigh_tickets")
@Getter
@Setter
@NoArgsConstructor
public class WeighTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_no")
    private String ticketNo;

    @Column(name = "producer_id")
    private Long producerId;

    @Column(name = "vehicle_plate")
    private String vehiclePlate;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "gross_kg")
    private BigDecimal grossKg;

    @Column(name = "tare_kg")
    private BigDecimal tareKg;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})  // gross_kg - tare_kg, veritabanında hesaplanır
    @Column(name = "net_kg", insertable = false, updatable = false)
    private BigDecimal netKg;

    @Column(name = "gross_weighed_at")
    private OffsetDateTime grossWeighedAt;

    @Column(name = "tare_weighed_at")
    private OffsetDateTime tareWeighedAt;

    @Column(name = "weighed_by_id")
    private Long weighedById;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TicketStatus status;

    @Column(name = "daily_price_id")
    private Long dailyPriceId;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "priced_at")
    private OffsetDateTime pricedAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
