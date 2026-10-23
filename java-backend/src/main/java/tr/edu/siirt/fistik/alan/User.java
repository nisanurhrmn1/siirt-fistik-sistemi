package tr.edu.siirt.fistik.alan;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Sistem kullanıcısı; her kritik işlem kime ait izlenir. */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username")
    private String username;

    @Column(name = "full_name")
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private UserRole role;

    @Column(name = "is_active")
    private boolean active;

    /** BCrypt özeti; düz şifre asla saklanmaz. */
    @Column(name = "password_hash")
    private String passwordHash;

    /** Giriş ekranından gelen hesap talebi: yönetici onaylayana kadar pasif kalır. */
    @Column(name = "pending_approval")
    private boolean pendingApproval;

    /** Yöneticinin verdiği geçici şifre: ilk girişte değiştirilmesi istenir. */
    @Column(name = "must_change_password")
    private boolean mustChangePassword;

    @Column(name = "request_note")
    private String requestNote;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
