package com.project.bookingservice.client.feign;

import com.project.bookingservice.security.InternalTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;

/**
 * Feign RequestInterceptor that injects a fresh SERVICE JWT on every request
 * to flight-service internal endpoints.
 *
 * NOT annotated with @Component — registered exclusively via FlightFeignConfig
 * so it is scoped to flight-service only.
 *
 * Token is generated fresh each call (60-second TTL) via InternalTokenProvider.
 */
@RequiredArgsConstructor
public class ServiceJwtRequestInterceptor implements RequestInterceptor {

    private final InternalTokenProvider internalTokenProvider;

    @Override
    public void apply(RequestTemplate template) {
        String token = internalTokenProvider.generateServiceToken();
        template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
