package com.project.paymentservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtValidator jwtValidator;


    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {

        return "POST".equalsIgnoreCase(request.getMethod()) && "/api/webhooks/stripe".equals(request.getServletPath());
    }


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        try {

            String jwt = getJwtFromRequest(request);

            log.debug("Processing request: {} {}", request.getMethod(), request.getRequestURI());

            if (StringUtils.hasText(jwt)) {

                Claims claims = jwtValidator.validateAndExtract(jwt);

                String subject = jwtValidator.extractSubject(claims);

                String role = jwtValidator.extractRole(claims);

                if (subject != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                    List<SimpleGrantedAuthority> authorities = role != null
                                    ? List.of(new SimpleGrantedAuthority(role))
                                    : List.of();

                    if (!authorities.isEmpty()) {

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                        subject,
                                        null,
                                        authorities
                                );

                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);

                        SecurityContextHolder.setContext(context
                        );

                        log.debug("JWT authenticated — subject={} role={}", subject, role);

                    } else {
                        log.warn("JWT valid but no role found for subject={}", subject);
                    }
                }
            }

        } catch (JwtException e) {

            log.warn(
                    "JWT validation failed [{}]: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );

        } catch (Exception e) {

            log.error(
                    "Unexpected error during JWT authentication [{}]: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
        }


        filterChain.doFilter(request, response);
    }


    private String getJwtFromRequest(HttpServletRequest request) {

        String bearerToken = request.getHeader("Authorization");

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {

            return bearerToken.substring(7);
        }

        return null;
    }
}