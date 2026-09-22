package com.project.paymentservice.gateway;

import com.project.paymentservice.gateway.exception.GatewayAmbiguousException;
import com.project.paymentservice.gateway.exception.GatewayDefinitiveException;
import com.project.paymentservice.gateway.model.CheckoutRequest;
import com.project.paymentservice.gateway.model.CheckoutResult;
import com.project.paymentservice.enums.PaymentProvider;

/**
 * Provider-neutral payment gateway abstraction.
 *
 * Implementations are responsible for translating between the internal
 * payment domain and a specific external payment provider API.
 *
 * CONTRACT:
 *
 *   This interface defines exactly three observable outcomes for
 *   {@link #createCheckout}:
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ Outcome           │ Signal                │ Caller maps │
 *   ├─────────────────────────────────────────────────────────┤
 *   │ Success           │ returns result        │ → SUCCEEDED │
 *   │ Definitive fail   │ GatewayDefinitive...  │ → FAILED    │
 *   │ Ambiguous / lost  │ GatewayAmbiguous...   │ → UNKNOWN   │
 *   └─────────────────────────────────────────────────────────┘
 *
 * IMPLEMENTATION RULES:
 *
 *   1. No @Transactional annotation on implementing classes.
 *      Implementations must NOT participate in any DB transaction.
 *
 *   2. Catch all provider SDK exceptions inside the implementation.
 *      Only GatewayDefinitiveException and GatewayAmbiguousException
 *      must leak out.
 *
 *   3. The providerIdempotencyKey inside requests is stable
 *      across retries. Forward it to the provider
 *      to enable provider-side deduplication.
 *
 *   4. Never modify any DB entity. That is the caller's responsibility
 *      via PaymentTransactionService / RefundTransactionService.
 */
public interface PaymentGateway {

    /**
     * Returns the provider this gateway handles.
     * Used by {@link PaymentGatewayResolver} to route requests.
     */
    PaymentProvider provider();

    /**
     * Creates a provider checkout/payment session.
     *
     * @param request provider-neutral checkout request
     * @return successful checkout result with redirect URL and provider identifiers
     * @throws GatewayDefinitiveException when the provider definitively rejects the request
     * @throws GatewayAmbiguousException  when the provider outcome is unknown
     *                                    (timeout, connection reset, lost response)
     */
    CheckoutResult createCheckout(CheckoutRequest request);
}
