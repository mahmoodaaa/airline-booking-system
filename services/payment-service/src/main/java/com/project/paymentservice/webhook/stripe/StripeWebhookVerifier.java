package com.project.paymentservice.webhook.stripe;

import com.project.paymentservice.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripeWebhookVerifier {

    private final StripeProperties stripeProperties;

    public Event verify(String rawPayload, String signatureHeader) {

        if (rawPayload == null || rawPayload.isBlank()) {
            throw new InvalidStripeWebhookException("Stripe webhook payload is empty");
        }

        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidStripeWebhookException("Stripe-Signature header is missing");
        }

        String webhookSecret = stripeProperties.getWebhookSecret();

        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("Stripe webhook secret is not configured");
        }

        try {

            return Webhook.constructEvent(rawPayload, signatureHeader, webhookSecret);

        } catch (SignatureVerificationException ex) {

            log.warn("Rejected Stripe webhook because signature verification failed");

            throw new InvalidStripeWebhookException("Invalid Stripe webhook signature", ex);

        } catch (RuntimeException ex) {

            log.warn("Rejected Stripe webhook because payload is invalid");

            throw new InvalidStripeWebhookException("Invalid Stripe webhook payload", ex);
        }
    }
}