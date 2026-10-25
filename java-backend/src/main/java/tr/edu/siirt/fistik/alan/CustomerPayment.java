package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Alıcıdan tahsilat (vadeli sevkiyatın ödenmesi). Değiştirilemez; veritabanı tetikleyicisi korur. */
@Entity
@Table(name = "customer_payments")
@Getter
@Setter
@NoArgsConstructor
public class CustomerPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_no")
    private String receiptNo;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "amount")
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method")
    private PaymentMethod method;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "reference_no")
    private String referenceNo;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by_id")
    private Long createdById;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
