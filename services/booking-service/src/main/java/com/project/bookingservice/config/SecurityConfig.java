package com.project.bookingservice.config;

import com.project.bookingservice.security.JwtAuthenticationFilter;
import com.project.bookingservice.security.SecurityAccessDeniedHandler;
import com.project.bookingservice.security.SecurityAuthEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityAuthEntryPoint securityAuthEntryPoint;
    private final SecurityAccessDeniedHandler securityAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(securityAuthEntryPoint)
                        .accessDeniedHandler(securityAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Swagger/OpenAPI - public
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()

                        // =====================================================
                        // CUSTOMER
                        // =====================================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/bookings"
                        ).hasAuthority("CUSTOMER")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/bookings/my",
                                "/api/bookings/my/**"
                        ).hasAuthority("CUSTOMER")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/bookings/*/cancel"
                        ).hasAuthority("CUSTOMER")

                        // =====================================================
                        // ADMIN
                        // =====================================================

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/bookings"
                        ).hasAuthority("ADMIN")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/bookings/*"
                        ).hasAuthority("ADMIN")

                        // =====================================================
                        // INTERNAL (Payment Service)
                        // =====================================================

                        .requestMatchers(
                                "/internal/bookings/**"
                        ).access((authentication, context) -> {
                            var currentAuth = authentication.get();
                            boolean isService = currentAuth.getAuthorities().stream().anyMatch(a -> "SERVICE".equals(a.getAuthority()));
                            boolean isPaymentService = "payment-service".equals(currentAuth.getName());
                            return new org.springframework.security.authorization.AuthorizationDecision(isService && isPaymentService);
                        })

                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
