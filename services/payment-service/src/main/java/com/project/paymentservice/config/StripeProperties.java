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
     * Stripe Checkout Session lifetime (minutes).
     *
     * Booking payment window is currently 45 minutes.
     * Checkout is configured for 40 minutes, giving a nominal
     * ~5-minute Booking-side margin for provider processing,
     * webhook delivery and Booking confirmation.
     *
     * Actual margin may be slightly smaller due to application
     * and network processing before Stripe creates the Session.
     *
     * Stripe minimum: 30 minutes. Maximum: 24 hours.
     */
    private long checkoutExpiryMinutes = 40;
}