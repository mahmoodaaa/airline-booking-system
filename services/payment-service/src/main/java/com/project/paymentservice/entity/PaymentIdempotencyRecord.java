package com.project.paymentservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(
        name = "payment_idempotency_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_idempotency_user_key",
                        columnNames = {"user_id", "idempotency_key_hash"})
        },
        indexes = {@Index(name = "idx_payment_idempotency_booking", columnList = "booking_id"),
                   @Index(name = "idx_payment_idempotency_payment", columnList = "payment_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentIdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // =========================================
    // API command identity
    // =========================================

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * SHA-256 of the original client Idempotency-Key.
     */
    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    /**
     * SHA-256 of the normalized logical request.
     * For Sprint 5 MVP the request identity is mainly bookingId.
     */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    // =========================================
    // Resolved logical result
    // =========================================

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "attempt_id")
    private UUID attemptId;

    // =========================================
    // Audit
    // =========================================

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}