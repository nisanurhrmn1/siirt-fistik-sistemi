package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Ödemenin hakedişlere dağıtımı (parçalı/tam ödeme). */
@Entity
@Table(name = "payment_allocations")
@Getter
@Setter
@NoArgsConstructor
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "amount")
    private BigDecimal amount;
}
