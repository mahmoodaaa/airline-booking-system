package com.project.bookingservice.client.feign;

import tools.jackson.databind.ObjectMapper;
import com.project.bookingservice.security.InternalTokenProvider;
import feign.Retryer;
import feign.Request;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * Feign configuration scoped ONLY to {@link FlightFeignClient}.
 *
 * ⚠️ INTENTIONALLY has NO @Configuration annotation.
 *
 * If @Configuration were present and this class were inside the component-scan
 * path, Spring would register it as a default Feign configuration applied to ALL
 * Feign clients — a well-known OpenFeign trap documented by Spring.
 * Without @Configuration, it is only applied via:
 *   @FeignClient(configuration = FlightFeignConfig.class)
 *
 * Bean dependencies (ObjectMapper, InternalTokenProvider) are injected as
 * @Bean method parameters — the standard Spring pattern when @Configuration
 * is absent.
 */
public class FlightFeignConfig {

    /**
     * ErrorDecoder: translates HTTP errors into domain exceptions.
     * Uses the Spring-managed ObjectMapper (with JavaTimeModule etc.)
     */
    @Bean
    public ErrorDecoder flightClientErrorDecoder(ObjectMapper objectMapper) {
        return new FlightClientErrorDecoder(objectMapper);
    }

    /**
     * RequestInterceptor: injects a fresh SERVICE JWT on every request.
     * Scoped to flight-service only — not global.
     */
    @Bean
    public feign.RequestInterceptor serviceJwtRequestInterceptor(
            InternalTokenProvider internalTokenProvider) {
        return new ServiceJwtRequestInterceptor(internalTokenProvider);
    }

    /**
     * Explicit NO RETRY.
     *
     * reserveSeats / releaseSeats are NOT idempotent operations.
     * Retrying after a timeout would risk double-reservation or double-release.
     *
     * Spring Cloud OpenFeign defaults to NEVER_RETRY, but we declare it
     * explicitly as an architectural decision and documentation.
     */
    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * Connect and read timeouts.
     *
     * These serve as code-level safety nets.
     * Values can be overridden via application.properties:
     *   spring.cloud.openfeign.client.config.flight-service.connectTimeout=5000
     *   spring.cloud.openfeign.client.config.flight-service.readTimeout=10000
     */
    @Bean
    public Request.Options options() {
        return new Request.Options(
                5, TimeUnit.SECONDS,   // connect timeout
                10, TimeUnit.SECONDS,  // read timeout
                false                  // followRedirects = false
        );
    }
}
