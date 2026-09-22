
package com.project.paymentservice.repository;

import com.project.paymentservice.entity.PaymentIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentIdempotencyRecordRepository
        extends JpaRepository<PaymentIdempotencyRecord, UUID> {

    /**
     * Finds an idempotency claim for a specific customer.
     *
     * Used to distinguish:
     *
     * same key + same requestHash
     *     -> replay / continue the same logical request
     *
     * same key + different requestHash
     *     -> conflict
     *
     * Concurrency for the first claim is enforced by the database
     * UNIQUE(user_id, idempotency_key_hash) constraint.
     */
    Optional<PaymentIdempotencyRecord> findByUserIdAndIdempotencyKeyHash(UUID userId, String idempotencyKeyHash);


    @Modifying
    @Query(value = """
        INSERT INTO payment_idempotency_records (
            id,
            user_id,
            idempotency_key_hash,
            request_hash,
            booking_id,
            created_at,
            updated_at
        )
        VALUES (
            :id,
            :userId,
            :keyHash,
            :requestHash,
            :bookingId,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP
        )
        ON CONFLICT (user_id, idempotency_key_hash)
        DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("keyHash") String keyHash,
            @Param("requestHash") String requestHash,
            @Param("bookingId") UUID bookingId
    );
}