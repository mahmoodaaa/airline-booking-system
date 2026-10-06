package com.project.paymentservice.gateway;

import com.project.paymentservice.gateway.exception.GatewayAmbiguousException;
import com.project.paymentservice.gateway.exception.GatewayDefinitiveException;
import com.project.paymentservice.gateway.model.CheckoutRequest;
import com.project.paymentservice.gateway.model.CheckoutResult;

import com.project.paymentservice.enums.PaymentProvider;

/**
 * Provider-neutral payment gateway abstraction.
 *
 * createCheckout has three outcomes:
 *
 * SUCCESS
 * → returns CheckoutResult
 *
 * DEFINITIVE FAILURE
 * → GatewayDefinitiveException
 *
 * AMBIGUOUS OUTCOME
 * → GatewayAmbiguousException
 *
 * refundFullPayment follows the same three-outcome contract.
 *
 * Provider network calls must run outside DB transactions.
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


    /**
     * Issues a full refund for a previously captured payment.
     *
     * The same {@code idempotencyKey} must be used on every retry
     * so the provider deduplicates repeated calls safely.
     *
     * @param providerPaymentId provider's PaymentIntent / charge identifier
     * @param idempotencyKey    stable deterministic key; never regenerated across retries
     * @return providerRefundId from the provider
     * @throws GatewayDefinitiveException when the provider definitively rejects the refund
     * @throws GatewayAmbiguousException  when the outcome is unknown
     */
    String refundFullPayment(String providerPaymentId, String idempotencyKey);

}

