package com.project.paymentservice.client.feign;

import com.project.paymentservice.security.InternalTokenProvider;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration scoped to BookingFeignClient only.
 *
 * Intentionally NOT annotated with @Configuration to prevent
 * Spring from component-scanning it as a global configuration.
 * It is referenced exclusively via BookingFeignClient.configuration.
 *
 * Beans defined here:
 *  - ServiceJwtRequestInterceptor  — attaches SERVICE JWT to every outbound call
 *  - BookingClientErrorDecoder     — maps booking-service HTTP errors to typed exceptions
 *  - Retryer.NEVER_RETRY           — disables Feign's built-in retry; all retry/reconciliation
 *                                    is handled explicitly by our own service layer
 */
public class BookingFeignConfig {

    @Bean
    public RequestInterceptor serviceJwtRequestInterceptor(
            InternalTokenProvider tokenProvider
    ) {
        return new ServiceJwtRequestInterceptor(tokenProvider);
    }

    @Bean
    public ErrorDecoder bookingClientErrorDecoder() {
        return new BookingClientErrorDecoder();
    }

    /**
     * Disable Feign automatic retries.
     *
     * After Stripe succeeds, ANY ambiguous outcome on the Booking side
     * must be persisted and reconciled explicitly — never silently retried
     * by the transport layer. Hidden retries would violate our
     * "COMMIT BEFORE NETWORK" principle and confuse idempotency tracking.
     */
    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
