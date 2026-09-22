package com.project.paymentservice.repository;


import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Normal read-only lookup by booking.
     *
     * The database UNIQUE constraint on booking_id guarantees
     * that at most one logical Payment exists per Booking.
     */
    Optional<Payment> findByBookingId(UUID bookingId);


    /**
     * Locks an existing Payment row while deciding whether
     * a new PaymentAttempt may be created.
     *
     * IMPORTANT:
     * Must be called inside an active transaction.
     *
     * This does NOT protect the first Payment creation race,
     * because no row exists yet in that case.
     * UNIQUE(booking_id) is the final protection for that race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       SELECT p
         FROM Payment p
        WHERE p.id = :paymentId
       """)
    Optional<Payment> findByIdForUpdate(
            @Param("paymentId") UUID paymentId
    );

    /**
     * Atomically assigns the first verified successful attempt
     * as the canonical successful attempt for this Payment.
     *
     * Return value:
     * 1 -> incoming attempt won the first-success claim
     * 0 -> another transition already won; caller MUST re-read
     *      Payment and inspect succeededAttemptId.
     *
     * NOTE:
     * clearAutomatically clears the entire persistence context.
     * After this bulk update, previously managed entity references
     * must not be relied upon; re-read required state from the DB.
     *
     * Payment.succeededAt belongs to the canonical succeededAttemptId.
     * A losing attempt must never overwrite it.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            UPDATE Payment p
               SET p.status = :succeededStatus,
                   p.succeededAttemptId = :attemptId,
                   p.succeededAt = :succeededAt,
                   p.updatedAt = :updatedAt,
                   p.version = p.version + 1
             WHERE p.id = :paymentId
               AND p.status = :pendingStatus
               AND p.succeededAttemptId IS NULL
            """)
    int claimFirstSuccessfulAttempt(
            @Param("paymentId") UUID paymentId,
            @Param("attemptId") UUID attemptId,
            @Param("pendingStatus") PaymentStatus pendingStatus,
            @Param("succeededStatus") PaymentStatus succeededStatus,
            @Param("succeededAt") LocalDateTime succeededAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );



    @Modifying
    @Query(value = """
        INSERT INTO payments (
            id,
            booking_id,
            user_id,
            amount,
            currency,
            status,
            booking_confirmation_status,
            version,
            created_at,
            updated_at
        )
        VALUES (
            :id,
            :bookingId,
            :userId,
            :amount,
            :currency,
            'PENDING',
            'NOT_STARTED',
            0,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP
        )
        ON CONFLICT (booking_id)
        DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("bookingId") UUID bookingId,
            @Param("userId") UUID userId,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency
    );
}