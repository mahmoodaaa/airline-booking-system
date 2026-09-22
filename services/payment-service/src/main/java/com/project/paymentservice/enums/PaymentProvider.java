package com.project.paymentservice.enums;

/**
 * Supported payment providers.
 * Additional providers (e.g., PAYPAL, MEPS) can be added here in the future
 * without altering the core payment domain model.
 */
public enum PaymentProvider {
    STRIPE,
    PAYPAL,
    MEPS
}
