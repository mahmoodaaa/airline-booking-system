package com.project.paymentservice.gateway.exception;

/**
 * Thrown by a {@link com.project.paymentservice.gateway.PaymentGateway}
 * implementation when the outcome of the provider call is UNKNOWN.
 *
 * "Ambiguous" means we cannot determine whether the provider created
 * a payment/session or not. The provider truth is unresolved.
 *
 * Examples:
 *   - read timeout after sending the request (provider may have processed it)
 *   - connection reset mid-response (response was lost)
 *   - service unavailable with no response body
 *   - unexpected exception with no provider error code
 *
 * Caller response:
 *   PaymentTransactionService.markAttemptUnknown()
 *
 * IMPORTANT:
 *   Do NOT throw this when the provider returned a clear error response.
 *   A 4xx error with a provider error body is definitive. Use
 *   GatewayDefinitiveException for those cases.
 *
 *   UNKNOWN attempts must be resolved via reconciliation before
 *   any new attempt on the same Payment is allowed.
 */
public class GatewayAmbiguousException extends RuntimeException {

    public GatewayAmbiguousException(String message) {
        super(message);
    }

    public GatewayAmbiguousException(String message, Throwable cause) {
        super(message, cause);
    }
}
