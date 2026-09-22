package com.project.paymentservice.gateway.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Successful provider checkout/payment session result.
 *
 * Returned by {@link com.project.paymentservice.gateway.PaymentGateway#createCheckout}
 * when the provider successfully creates the session.
 *
 * All fields are provider-neutral names. The calling layer persists them
 * via {@code PaymentTransactionService.markAttemptOpen()}.
 *
 * Design notes:
 *
 *   - providerCheckoutId identifies the checkout/session object at the provider.
 *     For Stripe this is the Checkout Session ID (cs_...).
 *
 *   - providerPaymentId identifies the underlying financial object at the provider.
 *     For Stripe this is the PaymentIntent ID (pi_...).
 *     May be null if the provider does not expose it at session-creation time.
 *
 *   - redirectUrl is the URL the customer should be sent to.
 *     Must never be null on a successful result.
 *
 *   - expiresAt is when the provider session expires.
 *     May be null if the provider does not expose an expiry.
 */
public record CheckoutResult(
        String providerCheckoutId,
        String providerPaymentId,
        String redirectUrl,
        LocalDateTime expiresAt
) {

    public CheckoutResult {
        Objects.requireNonNull(
                providerCheckoutId, "providerCheckoutId must not be null");

        Objects.requireNonNull(
                redirectUrl, "redirectUrl must not be null");
    }
}
