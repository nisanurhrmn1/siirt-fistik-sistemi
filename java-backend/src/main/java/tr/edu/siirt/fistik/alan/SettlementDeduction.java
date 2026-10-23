package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Hakedişe uygulanan kesintinin oran ve tutar snapshot'ı. */
@Entity
@Table(name = "settlement_deductions")
@Getter
@Setter
@NoArgsConstructor
public class SettlementDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "deduction_type_id")
    private Long deductionTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "calc_method")
    private DeductionCalcMethod calcMethod;

    @Column(name = "rate")
    private BigDecimal rate;

    @Column(name = "base_amount")
    private BigDecimal baseAmount;

    @Column(name = "amount")
    private BigDecimal amount;
}
