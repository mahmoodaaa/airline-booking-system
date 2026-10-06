package com.project.paymentservice.service;

import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.entity.PaymentIdempotencyRecord;
import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import com.project.paymentservice.enums.RefundStatus;
import com.project.paymentservice.service.model.AttemptClaimResult;
import com.project.paymentservice.service.model.PaymentSuccessResult;
import com.project.paymentservice.enums.BookingConfirmationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles short-lived, durable database transactions
 * required by the payment initiation flow.
 *
 * IMPORTANT:
 * No network calls are allowed inside this service.
 *
 * The service exists to enforce:
 *
 *      DB CLAIM
 *          â†“
 *       COMMIT
 *          â†“
 *    NETWORK CALL
 *
 * This is the core COMMIT-BEFORE-NETWORK boundary.
 */
public interface PaymentTransactionService {

    /**
     * Claims the client Idempotency-Key or returns the already
     * existing claim for the same logical request.
     *
     * Semantics:
     *
     * no existing key
     *      -> create durable claim
     *
     * same key + same requestHash
     *      -> return existing claim
     *
     * same key + different requestHash
     *      -> ConflictException
     *
     * Concurrent first insertion must resolve to the DB winner
     * rather than incorrectly treating the loser as a business conflict.
     */
    PaymentIdempotencyRecord claimIdempotency(
            UUID userId,
            String idempotencyKeyHash,
            String requestHash,
            UUID bookingId
    );


    /**
     * Returns the single logical Payment for a Booking,
     * creating it when it does not yet exist.
     *
     * UNIQUE(booking_id) is the final protection against
     * concurrent first creation.
     *
     * Existing immutable financial snapshot must remain consistent
     * with the authoritative Booking context.
     */
    Payment getOrCreatePayment(
            UUID bookingId,
            UUID userId,
            BigDecimal amount,
            String currency
    );


    /**
     * Atomically decides which PaymentAttempt belongs to this
     * payment initiation command.
     *
     * Transaction flow:
     *
     * 1. Lock Payment using PESSIMISTIC_WRITE by paymentId.
     * 2. Load the IdempotencyRecord.
     * 3. If the IdempotencyRecord is already mapped to an Attempt,
     *    return that SAME Attempt. Never remap the key.
     * 4. Otherwise check for an active Attempt:
     *      INITIALIZING / OPEN / UNKNOWN.
     * 5. If an active Attempt exists for the same provider/method,
     *    bind this idempotency record to it.
     * 6. If an active Attempt exists for another provider/method,
     *    reject the competing initiation.
     * 7. If no active Attempt exists, create a new INITIALIZING
     *    Attempt and persist its provider idempotency key.
     *
     * createdNewAttempt=true means the caller owns the subsequent
     * provider network call after this transaction commits.
     */
    AttemptClaimResult claimAttemptForInitiation(
            UUID paymentId,
            UUID idempotencyRecordId,
            PaymentProvider provider,
            PaymentMethodType paymentMethod,
            LocalDateTime latestCheckoutStartAt
    );


    /**
     * Finalizes a successfully-created provider checkout/payment flow.
     *
     * Atomic transition:
     *
     * INITIALIZING -> OPEN
     *
     * The provider identifiers and redirect information must be
     * persisted in the same CAS update as the OPEN transition.
     *
     * providerPaymentId may be null when the provider has not
     * created/exposed the final financial object yet.
     */
    PaymentAttempt markAttemptOpen(
            UUID attemptId,
            String providerCheckoutId,
            String providerPaymentId,
            String redirectUrl,
            LocalDateTime providerExpiresAt
    );


    /**
     * Records an ambiguous provider-create outcome.
     *
     * Atomic transition:
     *
     * INITIALIZING -> UNKNOWN
     *
     * Used for timeout, connection reset, response loss, etc.
     *
     * UNKNOWN is NOT terminal and must not set resolvedAt.
     */
    PaymentAttempt markAttemptUnknown(UUID attemptId, String failureReason);


    /**
     * Records a definitive provider rejection during
     * payment-attempt initialization.
     *
     * Atomic transition:
     *
     * INITIALIZING -> FAILED
     *
     * FAILED is terminal for this Attempt and sets resolvedAt.
     */
    PaymentAttempt markAttemptFailed(UUID attemptId, String failureReason);


    /**
     * Applies verified provider payment success.
     *
     * This method is called only AFTER:
     *
     * 1. Stripe webhook signature is verified.
     * 2. checkout.session.completed is validated.
     * 3. payment / attempt / booking correlation is validated.
     *
     * The whole financial transition must be atomic.
     *
     * Expected behavior:
     *
     * Attempt:
     * OPEN / UNKNOWN / INITIALIZING -> SUCCEEDED
     *
     * Payment:
     * PENDING -> SUCCEEDED
     *
     * The first successful Attempt becomes the canonical
     * succeededAttemptId for the Payment.
     *
     * BookingConfirmationStatus becomes PENDING so Booking
     * confirmation can happen AFTER this transaction commits.
     *
     * NO NETWORK CALLS inside this method.
     */
    PaymentSuccessResult markPaymentSucceededFromWebhook(
            UUID paymentId,
            UUID attemptId,
            UUID bookingId,
            String providerCheckoutId,
            String providerPaymentId,
            long providerAmountTotal,
            String providerCurrency,
            LocalDateTime succeededAt
    );


    /**
     * Applies provider-confirmed Checkout expiration.
     *
     * INITIALIZING / OPEN / UNKNOWN -> EXPIRED.
     *
     * Payment remains PENDING because no financial success occurred.
     *
     * EXPIRED is terminal for this Attempt.
     * A later payment initiation may create a new Attempt only when:
     * - there is no other active/unresolved Attempt
     * - the Booking payment window still has enough remaining time
     *
     * This method does not expire the Booking or release inventory.
     */
    PaymentAttempt markAttemptExpiredFromWebhook(UUID attemptId, String providerCheckoutId, LocalDateTime expiredAt);


    /**
     * Persists successful Booking synchronization after the remote
     * Booking confirmation call returned successfully.
     *
     * Expected state:
     *
     * Payment.status = SUCCEEDED
     * BookingConfirmationStatus:
     *      PENDING -> CONFIRMED
     *
     * Idempotent:
     *      CONFIRMED -> CONFIRMED
     *
     * No network calls are allowed inside this method.
     */
    Payment markBookingConfirmed(UUID paymentId);


    /**
     * Persists a DEFINITIVE Booking rejection after money has
     * already succeeded.
     *
     * Examples:
     * - Booking expired
     * - Booking cancelled
     * - Booking definitively refuses this payment
     *
     * Expected transition:
     *
     * PENDING -> REJECTED
     *
     * Payment remains financially SUCCEEDED.
     * BookingConfirmationStatus becomes REJECTED.
     *
     * This represents an exceptional state where money was received
     * but the Booking could not be confirmed.
     * Operational/manual financial handling may be required.
     */
    Payment markBookingRejected(
            UUID paymentId,
            String reason
    );


    /**
     * Records an AMBIGUOUS / retryable Booking confirmation failure.
     *
     * Examples:
     * - timeout
     * - connection failure
     * - Booking Service 5xx
     * - response lost
     *
     * Important:
     *
     * BookingConfirmationStatus remains PENDING.
     *
     * We do NOT invent REJECTED when the remote outcome is unknown.
     * Reconciliation will retry the idempotent confirmation later.
     *
     * No network calls are allowed inside this method.
     */
    Payment recordBookingConfirmationError(
            UUID paymentId,
            String error
    );


    // =========================================================
    // Refund â€” booking-rejection compensation
    // =========================================================

    /**
     * Atomically claims a pending refund for a definitively rejected Booking.
     *
     * Eligible preconditions:
     *   Payment.status                == SUCCEEDED
     *   BookingConfirmationStatus     == REJECTED
     *   Payment.succeededAttemptId    != null
     *   Payment.refundStatus          == NOT_STARTED | UNKNOWN
     *
     * Transitions:
     *   NOT_STARTED / UNKNOWN  ->  PENDING
     *
     * Returns empty Optional when:
     *   - refundStatus == SUCCEEDED  (already done, no-op)
     *   - refundStatus == FAILED     (manual intervention needed)
     *   - refundStatus == PENDING    (concurrent refund in progress)
     *   - preconditions not met
     */
    Optional<Payment> claimRejectedBookingRefund(UUID paymentId);


    /**
     * Finalizes a successful Stripe refund.
     *
     * Transition: PENDING -> SUCCEEDED
     */
    Payment markRefundSucceeded(UUID paymentId, String providerRefundId);


    /**
     * Records an ambiguous refund outcome (timeout / connection lost).
     *
     * Transition: PENDING -> UNKNOWN
     *
     * The same idempotency key will be reused on the next retry.
     */
    Payment markRefundUnknown(UUID paymentId, String error);


    /**
     * Records a definitive refund failure from the provider.
     *
     * Transition: PENDING -> FAILED
     *
     * FAILED requires manual operational intervention.
     */
    Payment markRefundFailed(UUID paymentId, String error);


}
