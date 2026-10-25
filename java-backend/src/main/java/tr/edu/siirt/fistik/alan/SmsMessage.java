package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Müstahsile gönderilen (veya simüle edilen) bilgilendirme SMS'i. */
@Entity
@Table(name = "sms_messages")
@Getter
@Setter
@NoArgsConstructor
public class SmsMessage {

    public enum Trigger { DELIVERY, ADVANCE }

    public enum Status {
        SENT("Gönderildi"), SIMULATED("Gönderildi (simülasyon)"), FAILED("Gönderilemedi");

        private final String ad;

        Status(String ad) {
            this.ad = ad;
        }

        public String getAd() {
            return ad;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "producer_id")
    private Long producerId;

    @Column(name = "phone")
    private String phone;

    @Column(name = "message")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type")
    private Trigger triggerType;

    /** Teslimatta tartım id'si, avansta avans id'si. */
    @Column(name = "related_id")
    private Long relatedId;

    @Column(name = "provider")
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private Status status;

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "error")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
