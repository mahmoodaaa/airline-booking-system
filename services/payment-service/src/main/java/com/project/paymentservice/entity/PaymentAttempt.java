package com.project.paymentservice.entity;

import com.project.paymentservice.enums.PaymentAttemptStatus;
import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;



@Entity
@Table(
        name = "payment_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_attempt_provider_idempotency_key",
                        columnNames = "provider_idempotency_key"
                ),
                @UniqueConstraint(
                        name = "uk_attempt_provider_checkout",
                        columnNames = {"provider", "provider_checkout_id"}
                ),
                @UniqueConstraint(
                        name = "uk_attempt_provider_payment",
                        columnNames = {"provider", "provider_payment_id"}
                )
        },
        indexes = { @Index(name = "idx_attempt_payment_id", columnList = "payment_id"),
                    @Index(name = "idx_attempt_status", columnList = "status")}
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // =========================================
    // Parent Payment
    // =========================================

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    // =========================================
    // Provider identity
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30, updatable = false)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30, updatable = false)
    private PaymentMethodType paymentMethod;

    // =========================================
    // Provider operation identity
    // =========================================

    @Column(
            name = "provider_idempotency_key",
            nullable = false,
            length = 150,
            updatable = false
    )
    private String providerIdempotencyKey;

    // =========================================
    // Provider references
    // =========================================

    @Column(name = "provider_checkout_id", length = 255)
    private String providerCheckoutId;

    @Column(name = "provider_payment_id", length = 255)
    private String providerPaymentId;

    @Column(name = "redirect_url", columnDefinition = "TEXT")
    private String redirectUrl;


    @Column(name = "provider_expires_at")
    private LocalDateTime providerExpiresAt;

    // =========================================
    // Lifecycle
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentAttemptStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    // =========================================
    // Concurrency
    // =========================================

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // =========================================
    // Audit
    // =========================================

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}