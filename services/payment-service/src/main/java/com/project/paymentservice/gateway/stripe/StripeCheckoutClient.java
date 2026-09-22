package com.project.paymentservice.gateway.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A thin wrapper around the Stripe SDK.
 * Exists purely to allow mocking in unit tests without deep-mocking
 * the StripeClient chain (stripeClient.v1().checkout().sessions().create(...)).
 */
@Component
@RequiredArgsConstructor
public class StripeCheckoutClient {

        private final StripeClient stripeClient;

        public Session createSession(
                        SessionCreateParams params,
                        RequestOptions requestOptions) throws StripeException {

                return stripeClient
                                .v1()
                                .checkout()
                                .sessions()
                                .create(params, requestOptions);
        }
}
