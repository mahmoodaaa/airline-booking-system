package com.project.paymentservice.webhook.handler;

import com.project.paymentservice.service.PaymentTransactionService;
import com.project.paymentservice.service.BookingConfirmationOrchestrator;
import com.project.paymentservice.service.model.PaymentSuccessResult;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeCheckoutCompletedHandlerImpl implements StripeCheckoutCompletedHandler {

    private static final String PAYMENT_MODE = "payment";
    private static final String PAID_STATUS = "paid";

    private static final String PAYMENT_ID_METADATA = "payment_id";
    private static final String ATTEMPT_ID_METADATA = "attempt_id";
    private static final String BOOKING_ID_METADATA = "booking_id";

    private final PaymentTransactionService paymentTransactionService;
    private final BookingConfirmationOrchestrator bookingConfirmationOrchestrator;

    @Override
    public void handle(Event event) {

        // ========================================================
        // 1. Extract Stripe Checkout Session
        // ========================================================

        Session session = extractSession(event);

        validateCompletedSession(event, session);


        // ========================================================
        // 3. Extract our correlation metadata
        // ========================================================

        Map<String, String> metadata = session.getMetadata();

        UUID paymentId = parseRequiredUuid(metadata, PAYMENT_ID_METADATA);

        UUID attemptId = parseRequiredUuid(metadata, ATTEMPT_ID_METADATA);

        UUID bookingId = parseRequiredUuid(metadata, BOOKING_ID_METADATA);


        // ========================================================
        // 4. Additional correlation:
        //
        // client_reference_id was created from Payment.id.
        // If present it MUST match metadata.payment_id.
        // ========================================================

        String clientReferenceId = session.getClientReferenceId();

        if (clientReferenceId != null && !clientReferenceId.isBlank() &&
                !clientReferenceId.equals(paymentId.toString())) {

            log.error(
                    "CRITICAL Stripe Checkout client reference mismatch. " +
                            "eventId={} sessionId={} paymentId={} clientReferenceId={}",
                    event.getId(),
                    session.getId(),
                    paymentId,
                    clientReferenceId
            );

            throw new IllegalStateException(
                    "Stripe Checkout client reference does not match Payment"
            );
        }


        // ========================================================
        // 5. Provider financial identifier
        //
        // For payment-mode Checkout this is the PaymentIntent ID.
        // ========================================================

        String providerPaymentId = session.getPaymentIntent();

        if (providerPaymentId == null || providerPaymentId.isBlank()) {

            throw new IllegalStateException(
                    "Paid Stripe Checkout Session has no PaymentIntent"
            );
        }


        // ========================================================
        // 6. Determine provider success time
        //
        // Stripe Event.created is epoch seconds.
        //
        // We use it as the provider-observed success timestamp.
        // ========================================================

        LocalDateTime succeededAt = event.getCreated() != null
                    ? LocalDateTime.ofEpochSecond(event.getCreated(), 0, ZoneOffset.UTC)
                        : LocalDateTime.now(ZoneOffset.UTC);


        // ========================================================
        // 7. Apply provider financial truth
        //
        // SHORT DB TX.
        //
        // Inside:
        //
        // Attempt -> SUCCEEDED
        // Payment -> SUCCEEDED
        // succeededAttemptId assigned once
        // BookingConfirmationStatus -> PENDING
        //
        // COMMIT occurs before any Booking HTTP call.
        // ========================================================

        PaymentSuccessResult result = paymentTransactionService
                .markPaymentSucceededFromWebhook(
                        paymentId, 
                        attemptId, 
                        bookingId, 
                        session.getId(), 
                        providerPaymentId, 
                        session.getAmountTotal(), 
                        session.getCurrency(), 
                        succeededAt
                );


        // ============================================================
        // 1. Non-canonical financial success
        //
        // Stripe says money was received by another Attempt,
        // while this logical Payment already has another canonical
        // successful Attempt.
        //
        // Do NOT confirm Booking again.
        //
        // Phase 11 owns technical compensation/refund.
        // ============================================================

        if (!result.canonicalSuccess()) {

            log.error(
                    "CRITICAL non-canonical Stripe financial success detected. " +
                            "paymentId={} bookingId={} attemptId={}. " +
                            "Booking confirmation will NOT be triggered.",
                    result.paymentId(),
                    result.bookingId(),
                    result.attemptId()
            );

            return;
        }


        // ============================================================
        // 2. Canonical historical success but Booking confirmation
        //    is no longer eligible.
        //
        // Primary example:
        //
        // Payment = REFUNDED
        // old checkout.session.completed replay arrives.
        //
        // Financial history remains valid, but we MUST NOT attempt
        // to confirm the Booking again.
        // ============================================================

        if (!result.bookingConfirmationEligible()) {

            log.info(
                    "Skipping Booking confirmation for canonical financial " +
                            "success because confirmation is no longer eligible. " +
                            "paymentId={} bookingId={} attemptId={}",
                    result.paymentId(),
                    result.bookingId(),
                    result.attemptId()
            );

            return;
        }


        // ============================================================
        // 3. Financial truth is durable
        //
        // IMPORTANT:
        //
        // markPaymentSucceededFromWebhook() uses REQUIRES_NEW.
        //
        // Therefore when execution reaches here:
        //
        // Attempt = SUCCEEDED
        // Payment = SUCCEEDED
        // BookingConfirmationStatus = PENDING
        //
        // TX #1 IS ALREADY COMMITTED.
        //
        // Booking confirmation now happens OUTSIDE that transaction.
        // ============================================================

        log.info(
                "Canonical Stripe financial success committed. " +
                        "Triggering Booking confirmation orchestration. " +
                        "paymentId={} bookingId={} attemptId={}",
                result.paymentId(),
                result.bookingId(),
                result.attemptId()
        );

        bookingConfirmationOrchestrator.confirmBooking(
                result.bookingId(),
                result.paymentId()
        );
    }


    // ============================================================
    // Stripe Event -> Checkout Session
    // ============================================================

    private Session extractSession(Event event) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Stripe event must not be null"
            );
        }

        StripeObject stripeObject = event.getDataObjectDeserializer().getObject()
                .orElseThrow(() -> new IllegalStateException("Stripe webhook data object could not be deserialized"));


        if (!(stripeObject instanceof Session session)) {

            throw new IllegalStateException(
                    "Stripe checkout.session.completed does not contain a Checkout Session"
            );
        }


        return session;
    }


    // ============================================================
    // Basic Session validation
    // ============================================================

    private void validateCompletedSession(Event event, Session session) {

        if (!"checkout.session.completed".equals(event.getType())) {

            throw new IllegalArgumentException(
                    "Unexpected Stripe event type for completed handler: "
                            + event.getType()
            );
        }


        if (session.getId() == null || session.getId().isBlank()) {
            throw new IllegalStateException("Stripe Checkout Session has no ID");}


        /*
         * Sprint 5 only supports one-time payment Checkout.
         */
        if (!PAYMENT_MODE.equals(session.getMode())) {

            throw new IllegalStateException(
                    "Stripe Checkout Session is not in payment mode"
            );
        }


        /*
         * checkout.session.completed by itself is not enough.
         *
         * For our CARD-only flow we require:
         *
         * payment_status = paid
         */
        if (!PAID_STATUS.equals(session.getPaymentStatus())) {

            throw new IllegalStateException(
                    "Stripe Checkout Session completed without paid payment status"
            );
        }


        if (session.getMetadata() == null || session.getMetadata().isEmpty()) {

            throw new IllegalStateException("Stripe Checkout Session has no payment correlation metadata");
        }


        /*
         * amount_total / currency will be validated against our
         * immutable Payment snapshot in the next small step.
         *
         * Still reject obviously malformed Stripe data here.
         */

        if (session.getAmountTotal() == null || session.getAmountTotal() <= 0) {

            throw new IllegalStateException(
                    "Stripe Checkout Session has invalid amount_total"
            );
        }


        if (session.getCurrency() == null || session.getCurrency().isBlank()) {

            throw new IllegalStateException(
                    "Stripe Checkout Session has no currency"
            );
        }
    }


    // ============================================================
    // Metadata UUID parser
    // ============================================================

    private UUID parseRequiredUuid(Map<String, String> metadata, String key) {

        String value = metadata.get(key);

        if (value == null || value.isBlank()) {

            throw new IllegalStateException("Stripe Checkout Session is missing metadata: " + key);
        }


        try {

            return UUID.fromString(value);

        } catch (IllegalArgumentException ex) {

            throw new IllegalStateException("Stripe Checkout Session contains invalid UUID metadata: " + key, ex
            );
        }
    }
}