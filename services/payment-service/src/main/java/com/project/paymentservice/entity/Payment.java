package com.project.paymentservice.entity;

import com.project.paymentservice.enums.BookingConfirmationStatus;
import com.project.paymentservice.enums.PaymentStatus;
import com.project.paymentservice.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_booking_id",
                        columnNames = "booking_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_payment_user_id",
                        columnList = "user_id"
                ),
                @Index(
                        name = "idx_payment_status",
                        columnList = "status"
                ),
                @Index(
                        name = "idx_payment_booking_confirmation",
                        columnList = "booking_confirmation_status"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // =========================================
    // Cross-service references
    // =========================================

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // =========================================
    // Immutable financial snapshot
    // =========================================

    @Column(name = "amount", nullable = false, precision = 19, scale = 3, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    // =========================================
    // Financial truth
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    /**
     * Canonical successful attempt for this logical payment.
     * Assigned once through an atomic first-success CAS claim.
     * Any other attempt that later reports provider success is treated
     * as a non-canonical financial success requiring operational handling.
     */
    @Column(name = "succeeded_attempt_id")
    private UUID succeededAttemptId;

    @Column(name = "succeeded_at")
    private LocalDateTime succeededAt;


    // =========================================
    // Booking synchronization truth
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_confirmation_status", nullable = false, length = 30)
    private BookingConfirmationStatus bookingConfirmationStatus;

    @Column(name = "booking_confirmation_last_error", length = 500)
    private String bookingConfirmationLastError;

    @Column(name = "booking_confirmed_at")
    private LocalDateTime bookingConfirmedAt;

    // =========================================
    // Refund truth (booking-rejection compensation only)
    //
    // Populated only when:
    //   PaymentStatus      = SUCCEEDED
    //   BookingConfirmationStatus = REJECTED
    //
    // PaymentStatus remains SUCCEEDED — it describes the financial
    // truth that money was successfully captured.
    // RefundStatus separately tracks whether that money was returned.
    // =========================================

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 30)
    private RefundStatus refundStatus;

    @Column(name = "provider_refund_id", length = 255)
    private String providerRefundId;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "refund_last_error", length = 500)
    private String refundLastError;

    // =========================================
    // Concurrency
    // =========================================

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
