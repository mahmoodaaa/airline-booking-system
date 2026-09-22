package com.project.paymentservice.service.impl;

import com.project.paymentservice.service.StripeWebhookService;
import com.project.paymentservice.service.WebhookTransactionService;
import com.project.paymentservice.enums.WebhookProcessingDecision;
import com.project.paymentservice.webhook.handler.StripeCheckoutCompletedHandler;
import com.project.paymentservice.webhook.handler.StripeCheckoutExpiredHandler;
import com.project.paymentservice.webhook.stripe.StripeWebhookVerifier;
import com.stripe.model.Event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookServiceImpl implements StripeWebhookService {

    private static final String CHECKOUT_COMPLETED = "checkout.session.completed";

    private static final String CHECKOUT_EXPIRED = "checkout.session.expired";


    private final StripeWebhookVerifier stripeWebhookVerifier;

    private final WebhookTransactionService webhookTransactionService;

    private final StripeCheckoutCompletedHandler completedHandler;

    private final StripeCheckoutExpiredHandler expiredHandler;


    // ============================================================
    // Stripe Webhook Orchestration
    // ============================================================

    @Override
    public void processWebhook(byte[] rawBody, String signatureHeader) {

        // ========================================================
        // 1. Validate raw payload
        //
        // Important:
        // Never deserialize JSON before Stripe signature
        // verification.
        //
        // Signature verification must use the original payload.
        // ========================================================

        if (rawBody == null || rawBody.length == 0) {

            throw new IllegalArgumentException("Stripe webhook payload is empty");
        }

        String rawPayload = new String(rawBody, StandardCharsets.UTF_8);


        // ========================================================
        // 2. VERIFY FIRST
        //
        // Invalid / forged webhook must NEVER:
        //
        // - create StripeWebhookEvent
        // - touch Payment
        // - touch PaymentAttempt
        //
        // StripeWebhookVerifier throws
        // InvalidStripeWebhookException if invalid.
        // ========================================================

        Event event = stripeWebhookVerifier.verify(rawPayload, signatureHeader);


        // ========================================================
        // 3. Validate verified Stripe event identity
        // ========================================================

        String stripeEventId = event.getId();

        String eventType = event.getType();


        if (stripeEventId == null || stripeEventId.isBlank()) {

            throw new IllegalStateException("Verified Stripe event has no ID");
        }


        if (eventType == null || eventType.isBlank()) {
            throw new IllegalStateException("Verified Stripe event has no type");
        }


        // ========================================================
        // 4. Hash EXACT raw body
        //
        // Used as additional duplicate/integrity protection.
        //
        // Same Stripe event ID:
        //
        // same hash      -> normal duplicate
        // different hash -> CRITICAL identity mismatch
        // ========================================================

        String payloadHash = sha256Hex(rawBody);


        // ========================================================
        // 5. Durable webhook inbox registration
        //
        // REQUIRES_NEW:
        //
        // INSERT ... ON CONFLICT DO NOTHING
        //
        // commits before business processing begins.
        // ========================================================

        webhookTransactionService.registerEvent(stripeEventId, eventType, payloadHash);


        // ========================================================
        // 6. Claim exclusive processing ownership
        //
        // RECEIVED / FAILED
        //      ->
        // PROCESSING
        //
        // Only the worker that wins the CAS is allowed to execute
        // financial/business handling.
        // ========================================================

        WebhookProcessingDecision decision = webhookTransactionService.claimForProcessing(stripeEventId);


        // ========================================================
        // 7. Duplicate already fully handled
        // ========================================================

        if (decision == WebhookProcessingDecision.ALREADY_PROCESSED) {

            log.debug("Ignoring already processed Stripe webhook. " + "eventId={} type={}", stripeEventId, eventType);

            return;
        }


        // ========================================================
        // 8. Another worker currently owns it
        //
        // DO NOT execute financial logic concurrently.
        //
        // Return normally -> controller can respond 200.
        //
        // Stale PROCESSING events will later be handled by
        // reconciliation.
        // ========================================================

        if (decision == WebhookProcessingDecision.IN_PROGRESS) {

            log.debug("Stripe webhook is already being processed. " + "eventId={} type={}", stripeEventId, eventType);
            return;
        }


        // ========================================================
        // 9. This worker owns PROCESSING
        // ========================================================

        try {
            routeEvent(event);


            // ====================================================
            // Handler completed successfully.
            //
            // Important:
            //
            // For checkout.session.completed this means financial
            // truth has already been durably persisted by its own
            // REQUIRES_NEW transaction.
            //
            // Unsupported valid events also reach here and are
            // intentionally marked PROCESSED.
            // ====================================================

            webhookTransactionService.markProcessed(stripeEventId);


            log.info("Stripe webhook processed successfully. " + "eventId={} type={}", stripeEventId, eventType);

        } catch (RuntimeException processingException) {

            // ====================================================
            // 10. Processing failed
            //
            // Try to persist:
            //
            // PROCESSING -> FAILED
            //
            // Then propagate the ORIGINAL exception so controller
            // returns 5xx and Stripe can retry.
            // ====================================================

            try {

                webhookTransactionService.markFailed(stripeEventId, safeError(processingException));

            } catch (RuntimeException markFailedException) {

                /*
                 * Preserve the original business failure.
                 *
                 * Do not replace it with the secondary DB failure.
                 */
                log.error(
                        "CRITICAL: Stripe webhook processing failed and " +
                                "FAILED status could not be persisted. " +
                                "eventId={} type={}",
                        stripeEventId,
                        eventType,
                        markFailedException
                );
            }


            log.error(
                    "Stripe webhook processing failed. " +
                            "eventId={} type={}",
                    stripeEventId,
                    eventType,
                    processingException
            );


            throw processingException;
        }
    }


    // ============================================================
    // Routing
    // ============================================================

    private void routeEvent(Event event) {

        switch (event.getType()) {

            // ====================================================
            // Stripe confirms Checkout completed + paid
            //
            // Handler:
            //
            // validates Session
            // validates metadata
            // validates amount/currency
            // Attempt -> SUCCEEDED
            // Payment -> SUCCEEDED
            // BookingConfirmationStatus -> PENDING
            // ====================================================

            case CHECKOUT_COMPLETED -> completedHandler.handle(event);


            // ====================================================
            // Checkout Session expired without payment.
            //
            // Handler:
            //
            // Attempt -> EXPIRED
            //
            // Payment remains PENDING.
            // Booking untouched.
            // ====================================================

            case CHECKOUT_EXPIRED -> expiredHandler.handle(event);


            // ====================================================
            // Valid Stripe event we do not consume.
            //
            // Do NOT throw.
            //
            // If we throw, Stripe would keep retrying an event that
            // our application intentionally does not care about.
            //
            // So:
            //
            // valid + unsupported
            //      ->
            // no-op
            //      ->
            // PROCESSED
            // ====================================================

            default -> log.debug(
                            "Ignoring unsupported Stripe webhook event. " +
                                    "eventId={} type={}", event.getId(), event.getType());
        }
    }


    // ============================================================
    // SHA-256
    // ============================================================

    private String sha256Hex(byte[] payload) {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");


            byte[] hash = digest.digest(payload);


            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException ex) {

            /*
             * SHA-256 is mandatory in the Java runtime.
             * Missing it means JVM/runtime configuration failure.
             */
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable",
                    ex
            );
        }
    }


    // ============================================================
    // Safe persisted error
    // ============================================================

    private String safeError(RuntimeException exception) {

        String message = exception.getMessage();


        if (message == null || message.isBlank()) {

            return exception.getClass().getSimpleName();
        }


        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}