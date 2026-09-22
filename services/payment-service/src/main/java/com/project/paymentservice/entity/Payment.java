package com.project.paymentservice.entity;

import com.project.paymentservice.enums.BookingConfirmationStatus;
import com.project.paymentservice.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
     * as a duplicate financial charge and compensated (D10/D11/D12).
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