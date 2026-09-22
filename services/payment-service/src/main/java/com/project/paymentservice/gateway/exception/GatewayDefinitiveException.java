package com.project.paymentservice.gateway.exception;

/**
 * Thrown by a {@link com.project.paymentservice.gateway.PaymentGateway}
 * implementation when the provider definitively rejects the checkout creation.
 *
 * "Definitive" means the provider returned a clear, non-ambiguous error:
 * the provider KNOWS this request failed and no payment/session was created.
 *
 * Examples:
 *   - unsupported currency for this account
 *   - invalid API configuration (bad key, wrong mode)
 *   - payment method not enabled on the provider account
 *   - amount below provider minimum
 *
 * Caller response:
 *   PaymentTransactionService.markAttemptFailed()
 *
 * IMPORTANT:
 *   Do NOT throw this for network errors, timeouts, or any scenario where
 *   the provider outcome is unknown. Use GatewayAmbiguousException for those.
 */
public class GatewayDefinitiveException extends RuntimeException {

    public GatewayDefinitiveException(String message) {
        super(message);
    }

    public GatewayDefinitiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
