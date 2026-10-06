package com.project.paymentservice.service.impl;

import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.gateway.PaymentGateway;
import com.project.paymentservice.gateway.PaymentGatewayResolver;
import com.project.paymentservice.gateway.exception.GatewayAmbiguousException;
import com.project.paymentservice.gateway.exception.GatewayDefinitiveException;
import com.project.paymentservice.repository.PaymentAttemptRepository;
import com.project.paymentservice.service.PaymentTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the minimal technical full-refund flow.
 *
 * Triggered ONLY when:
 *   Payment.status                == SUCCEEDED
 *   BookingConfirmationStatus     == REJECTED
 *
 * This is the narrow distributed-system compensation path for the race:
 *
 *   Booking window expires
 *     ↓
 *   Stripe payment succeeds (webhook)
 *     ↓
 *   Payment Service calls confirmBooking()
 *     ↓
 *   Booking Service definitively rejects (expired / cancelled)
 *     ↓
 *   Money was captured → must be returned automatically
 *
 * This class is NOT a generic refund engine:
 *   - No user-initiated cancellation refunds
 *   - No partial refunds
 *   - No admin refunds
 *   - No API / controller
 *   - No duplicate-success auto-refund
 *
 * Golden rule preserved (same as payment initiation):
 *   DB CLAIM → COMMIT → provider call → DB finalize
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingRejectedRefundService {

    private final PaymentTransactionService transactionService;

    private final PaymentAttemptRepository attemptRepository;

    private final PaymentGatewayResolver gatewayResolver;


    /**
     * Attempts a full refund for a payment whose Booking was definitively rejected.
     *
     * Idempotent: safe to call multiple times.
     * Returns silently when the refund is already done or not eligible.
     * Rethrows GatewayAmbiguousException so callers can log/propagate as needed.
     */
    public void refund(UUID paymentId) {

        // ========================================================
        // 1. Claim: NOT_STARTED / UNKNOWN -> PENDING
        //
        // COMMIT happens inside claimRejectedBookingRefund().
        // If claim returns empty, nothing to do.
        // ========================================================

        Optional<Payment> claimed = transactionService.claimRejectedBookingRefund(paymentId);

        if (claimed.isEmpty()) {
            return;
        }

        Payment payment = claimed.get();


        // ========================================================
        // 2. Resolve canonical successful attempt.
        //
        // We need the providerPaymentId (PaymentIntent ID)
        // to tell Stripe which charge to refund.
        // ========================================================

        UUID succeededAttemptId = payment.getSucceededAttemptId();

        PaymentAttempt attempt = attemptRepository
                .findById(succeededAttemptId)
                .orElseThrow(() -> new IllegalStateException(
                        "Canonical successful attempt not found: " + succeededAttemptId
                ));

        String providerPaymentId = attempt.getProviderPaymentId();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {

            log.error(
                    "CRITICAL: BookingRejectedRefundService: Canonical attempt {} has no providerPaymentId. " +
                    "Cannot issue refund for paymentId={}. Manual intervention required.",
                    succeededAttemptId,
                    paymentId
            );

            // Mark as FAILED so we don't loop indefinitely.
            transactionService.markRefundFailed(
                    paymentId,
                    "Canonical attempt has no providerPaymentId; manual refund required"
            );

            return;
        }


        // ========================================================
        // 3. Resolve gateway for the provider that captured money.
        // ========================================================

        PaymentGateway gateway = gatewayResolver.resolve(attempt.getProvider());


        // ========================================================
        // 4. Derive stable idempotency key.
        //
        // "booking-rejected-refund:{paymentId}" is deterministic
        // and never changes across retries for the same Payment.
        // This ensures provider-side deduplication is safe.
        // ========================================================

        String idempotencyKey = "booking-rejected-refund:" + paymentId;


        // ========================================================
        // 5. Provider network call — no DB transaction open here.
        // ========================================================

        try {

            String providerRefundId = gateway.refundFullPayment(providerPaymentId, idempotencyKey);


            // ====================================================
            // 6A. Success: PENDING -> SUCCEEDED
            // ====================================================

            transactionService.markRefundSucceeded(paymentId, providerRefundId);

            log.info(
                    "BookingRejectedRefundService: Refund completed. " +
                    "paymentId={} providerRefundId={}",
                    paymentId,
                    providerRefundId
            );


        } catch (GatewayAmbiguousException e) {

            // ====================================================
            // 6B. Unknown outcome: PENDING -> UNKNOWN
            //
            // Rethrow so the caller (BookingConfirmationOrchestrator)
            // can log and propagate for webhook retry.
            // The same idempotency key will be reused on next attempt.
            // ====================================================

            log.error(
                    "BookingRejectedRefundService: Refund outcome UNKNOWN. " +
                    "paymentId={} Will retry with same idempotency key.",
                    paymentId,
                    e
            );

            transactionService.markRefundUnknown(paymentId, e.getMessage());

            throw e;

        } catch (GatewayDefinitiveException e) {

            // ====================================================
            // 6C. Definitive failure: PENDING -> FAILED
            //
            // Manual operational intervention required.
            // Do NOT rethrow; this is a terminal state.
            // ====================================================

            log.error(
                    "CRITICAL: BookingRejectedRefundService: Refund definitively FAILED. " +
                    "paymentId={} Manual intervention required.",
                    paymentId,
                    e
            );

            transactionService.markRefundFailed(paymentId, e.getMessage());
        }
    }
}
