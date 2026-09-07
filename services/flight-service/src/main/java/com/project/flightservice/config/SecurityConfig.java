package com.project.flightservice.config;

import com.project.flightservice.security.JwtAuthenticationFilter;
import com.project.flightservice.security.SecurityAccessDeniedHandler;
import com.project.flightservice.security.SecurityAuthEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityAuthEntryPoint authEntryPoint;
    private final SecurityAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger / OpenAPI
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // Public Flights API
                .requestMatchers(HttpMethod.GET, "/api/flights").hasAuthority("ADMIN")  // list all flights — Admin-only
                .requestMatchers(HttpMethod.GET, "/api/flights/search").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/flights/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/flights/{id}/availability").permitAll()

                // Admin Flights API
                .requestMatchers(HttpMethod.POST, "/api/flights/**").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/flights/**").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/flights/**").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/flights/**").hasAuthority("ADMIN")

                // Admin Airports & Aircraft API
                .requestMatchers("/api/airports/**").hasAuthority("ADMIN")
                .requestMatchers("/api/aircraft/**").hasAuthority("ADMIN")

                // Internal API for Booking Service
                .requestMatchers("/internal/**").access((authentication, context) -> {
                    var currentAuth = authentication.get();
                    boolean isService = currentAuth.getAuthorities().stream().anyMatch(a -> "SERVICE".equals(a.getAuthority()));
                    boolean isBookingService = "booking-service".equals(currentAuth.getName());
                    return new org.springframework.security.authorization.AuthorizationDecision(isService && isBookingService);
                })

                // Any other request must be authenticated
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
