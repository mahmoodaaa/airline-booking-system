package com.project.paymentservice.client.feign;

import com.project.paymentservice.security.InternalTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;

/**
 * Injects a short-lived SERVICE JWT into every outbound Feign call
 * so booking-service can authorise the request as coming from payment-service.
 *
 * Token claims (per contract):
 *   sub  = "payment-service"
 *   role = "SERVICE"
 *   type = "SERVICE"
 *   exp  = now + 60 seconds
 *
 * A new token is generated per request — no caching needed given the 60s TTL
 * and the fact that Feign calls are short-lived.
 *
 * NOT annotated with @Component — instantiated exclusively by BookingFeignConfig
 * to avoid being registered as a global Spring bean and accidentally applied
 * to future Feign clients that should not carry a SERVICE identity.
 */
@RequiredArgsConstructor
public class ServiceJwtRequestInterceptor implements RequestInterceptor {

    private final InternalTokenProvider tokenProvider;

    @Override
    public void apply(RequestTemplate template) {
        String token = tokenProvider.generateServiceToken();
        template.header("Authorization", "Bearer " + token);
    }
}
