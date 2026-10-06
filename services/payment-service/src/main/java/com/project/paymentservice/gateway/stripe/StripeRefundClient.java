package com.project.paymentservice.gateway.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the Stripe SDK for refund operations.
 * Exists purely to allow mocking in unit tests without deep-mocking
 * the StripeClient chain.
 */
@Component
@RequiredArgsConstructor
public class StripeRefundClient {

    private final StripeClient stripeClient;

    public com.stripe.model.Refund createRefund(
            RefundCreateParams params,
            RequestOptions requestOptions) throws StripeException {

        return stripeClient
                .v1()
                .refunds()
                .create(params, requestOptions);
    }
}
