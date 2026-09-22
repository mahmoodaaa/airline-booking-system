package com.project.paymentservice.config;

import com.project.paymentservice.security.JwtAuthenticationFilter;
import com.project.paymentservice.security.SecurityAccessDeniedHandler;
import com.project.paymentservice.security.SecurityAuthEntryPoint;
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
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        return http

                // =====================================================
                // Stateless REST API
                // =====================================================

                .csrf(AbstractHttpConfigurer::disable)

              .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))


                // =====================================================
                // Security exception handling
                // =====================================================

                .exceptionHandling(exception -> exception.authenticationEntryPoint(securityAuthEntryPoint)
                        .accessDeniedHandler(securityAccessDeniedHandler)
                )


                // =====================================================
                // Authorization
                // =====================================================

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error"
                        ).permitAll()


                        // =============================================
                        // PUBLIC — Stripe Webhook
                        //
                        // No CUSTOMER JWT.
                        //
                        // Authentication is Stripe signature
                        // verification inside webhook service.
                        // =============================================

                        .requestMatchers(HttpMethod.POST, "/api/webhooks/stripe").permitAll()


                        // =============================================
                        // CUSTOMER — initiate payment
                        // =============================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/payments"
                        ).hasAuthority("CUSTOMER")


                        // =============================================
                        // Everything else default deny
                        // =============================================

                        .anyRequest()
                        .denyAll()
                )


                // =====================================================
                // JWT Filter
                // =====================================================

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}