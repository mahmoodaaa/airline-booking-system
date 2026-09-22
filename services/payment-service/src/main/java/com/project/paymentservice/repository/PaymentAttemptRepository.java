package com.project.paymentservice.repository;

import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.enums.PaymentAttemptStatus;
import com.project.paymentservice.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository
        extends JpaRepository<PaymentAttempt, UUID> {

    /**
     * Finds the currently unresolved attempt for a Payment.
     *
     * Expected active statuses in Sprint 5:
     * INITIALIZING, OPEN, UNKNOWN.
     *
     * Must be used while the parent Payment row is protected
     * by PESSIMISTIC_WRITE when making an attempt-creation decision.
     */
    @Query("""
            SELECT a
              FROM PaymentAttempt a
             WHERE a.payment.id = :paymentId
               AND a.status IN :statuses
            """)
    Optional<PaymentAttempt> findActiveAttempt(@Param("paymentId") UUID paymentId,
                                               @Param("statuses") Collection<PaymentAttemptStatus> statuses);


    /**
     * Correlates provider webhook events (like Stripe Checkout Session)
     * with the local PaymentAttempt.
     */
    Optional<PaymentAttempt> findByProviderAndProviderCheckoutId(PaymentProvider provider, String providerCheckoutId);


    /**
     * Atomically finalizes an INITIALIZING attempt after
     * the provider successfully creates the checkout session.
     *
     * The status and session data are written together,
     * preventing an invalid OPEN attempt with missing provider data.
     *
     * Return value:
     * 1 -> transition succeeded
     * 0 -> attempt is no longer in the expected state
     *
     * NOTE:
     * This bulk update clears the persistence context.
     * Re-read state afterwards when required.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            UPDATE PaymentAttempt a
               SET a.status = :openStatus,
                   a.providerCheckoutId = :checkoutId,
                   a.providerPaymentId = :paymentId,
                   a.redirectUrl = :redirectUrl,
                   a.providerExpiresAt = :expiresAt,
                   a.updatedAt = :updatedAt,
                   a.version = a.version + 1
             WHERE a.id = :attemptId
               AND a.status = :expectedStatus
            """)
    int markOpen(
            @Param("attemptId") UUID attemptId,
            @Param("expectedStatus") PaymentAttemptStatus expectedStatus,
            @Param("openStatus") PaymentAttemptStatus openStatus,
            @Param("checkoutId") String checkoutId,
            @Param("paymentId") String paymentId,
            @Param("redirectUrl") String redirectUrl,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );


    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE PaymentAttempt a
           SET a.status = :unknownStatus,
               a.failureReason = :failureReason,
               a.updatedAt = :updatedAt,
               a.version = a.version + 1
         WHERE a.id = :attemptId
           AND a.status = :expectedStatus
        """)
    int markUnknown(
            @Param("attemptId") UUID attemptId,
            @Param("expectedStatus") PaymentAttemptStatus expectedStatus,
            @Param("unknownStatus") PaymentAttemptStatus unknownStatus,
            @Param("failureReason") String failureReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );


    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE PaymentAttempt a
           SET a.status = :failedStatus,
               a.failureReason = :failureReason,
               a.resolvedAt = :resolvedAt,
               a.updatedAt = :updatedAt,
               a.version = a.version + 1
         WHERE a.id = :attemptId
           AND a.status = :expectedStatus
        """)
    int markFailed(
            @Param("attemptId") UUID attemptId,
            @Param("expectedStatus") PaymentAttemptStatus expectedStatus,
            @Param("failedStatus") PaymentAttemptStatus failedStatus,
            @Param("failureReason") String failureReason,
            @Param("resolvedAt") LocalDateTime resolvedAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

}