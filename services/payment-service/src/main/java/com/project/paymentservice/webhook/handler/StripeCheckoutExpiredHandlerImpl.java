package com.project.paymentservice.webhook.handler;

import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.service.PaymentTransactionService;
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
public class StripeCheckoutExpiredHandlerImpl
        implements StripeCheckoutExpiredHandler {

    private static final String EXPECTED_EVENT_TYPE = "checkout.session.expired";

    private static final String ATTEMPT_ID_METADATA = "attempt_id";

    private final PaymentTransactionService paymentTransactionService;


    @Override
    public void handle(Event event) {

        // ========================================================
        // 1. Extract Checkout Session
        // ========================================================

        Session session = extractSession(event);


        // ========================================================
        // 2. Validate event/session
        // ========================================================

        validateExpiredSession(event, session);


        // ========================================================
        // 3. Extract Attempt correlation
        // ========================================================

        UUID attemptId = parseRequiredUuid(session.getMetadata(), ATTEMPT_ID_METADATA);


        // ========================================================
        // 4. Determine provider expiry time
        //
        // Prefer Session.expires_at because this represents the
        // actual provider session expiry.
        //
        // Fall back to Event.created defensively.
        // ========================================================

        LocalDateTime expiredAt = resolveExpiredAt(event, session);


        // ========================================================
        // 5. Apply provider truth
        //
        // INITIALIZING / OPEN / UNKNOWN -> EXPIRED
        //
        // SUCCEEDED must NEVER be downgraded.
        //
        // Payment remains unchanged.
        // Booking remains untouched.
        // ========================================================

        PaymentAttempt attempt = paymentTransactionService.markAttemptExpiredFromWebhook(
                                attemptId,
                                session.getId(),
                                expiredAt
                        );


        log.info("Stripe Checkout expiration processed. " + "eventId={} attemptId={} sessionId={} attemptStatus={}",
                event.getId(),
                attemptId,
                session.getId(),
                attempt.getStatus()
        );
    }


    // ============================================================
    // Extract Stripe Session
    // ============================================================

    private Session extractSession(Event event) {

        if (event == null) {
            throw new IllegalArgumentException("Stripe event must not be null");
        }

        StripeObject stripeObject = event.getDataObjectDeserializer()
                        .getObject()
                        .orElseThrow(() -> new IllegalStateException("Stripe webhook data object could not be deserialized"));


        if (!(stripeObject instanceof Session session)) {

            throw new IllegalStateException(
                    "Stripe checkout.session.expired event " + "does not contain a Checkout Session");
        }

        return session;
    }


    // ============================================================
    // Validate event
    // ============================================================

    private void validateExpiredSession(Event event, Session session) {

        if (!EXPECTED_EVENT_TYPE.equals(event.getType())) {

            throw new IllegalArgumentException(
                    "Unexpected Stripe event type for expired handler: " + event.getType()
            );
        }


        if (session.getId() == null || session.getId().isBlank()) {

            throw new IllegalStateException("Expired Stripe Checkout Session has no ID");
        }


        if (session.getMetadata() == null || session.getMetadata().isEmpty()) {

            throw new IllegalStateException(
                    "Expired Stripe Checkout Session has no correlation metadata"
            );
        }
    }


    // ============================================================
    // Resolve expiry timestamp
    // ============================================================

    private LocalDateTime resolveExpiredAt(Event event, Session session) {

        if (session.getExpiresAt() != null) {

            return LocalDateTime.ofEpochSecond(session.getExpiresAt(), 0, ZoneOffset.UTC);
        }


        if (event.getCreated() != null) {

            return LocalDateTime.ofEpochSecond(event.getCreated(), 0, ZoneOffset.UTC);
        }


        /*
         * Defensive last-resort fallback.
         */
        return LocalDateTime.now(ZoneOffset.UTC
        );
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

            throw new IllegalStateException(
                    "Stripe Checkout Session contains invalid UUID metadata: "
                            + key,
                    ex
            );
        }
    }
}