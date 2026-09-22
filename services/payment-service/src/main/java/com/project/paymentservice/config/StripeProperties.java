package com.project.paymentservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {

    /**
     * Stripe secret API key.
     *
     * Example:
     * sk_test_...
     */
    private String secretKey;

    /**
     * Stripe webhook signing secret.
     *
     * Used later for Stripe-Signature verification.
     */
    private String webhookSecret;

    /**
     * Frontend URL Stripe redirects to after Checkout success.
     */
    private String successUrl;

    /**
     * Frontend URL Stripe redirects to when Checkout is cancelled.
     */
    private String cancelUrl;

    /**
     * Stripe Checkout Session lifetime.
     *
     * Current Sprint 5 default: 30 minutes.
     */
    private long checkoutExpiryMinutes = 30;
}