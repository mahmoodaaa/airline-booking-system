package com.project.flightservice.security;

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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                Claims claims = jwtValidator.validateAndExtract(jwt);
                String userId = jwtValidator.extractUserId(claims);
                String role = jwtValidator.extractRole(claims);
                String type = jwtValidator.extractType(claims);

                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<SimpleGrantedAuthority> authorities;
                    
                    if ("SERVICE".equals(type)) {
                        authorities = List.of(new SimpleGrantedAuthority("SERVICE"));
                        log.debug("Authenticated service: {}", userId);
                    } else if (role != null) {
                        authorities = List.of(new SimpleGrantedAuthority(role));
                        log.debug("Authenticated user: {} with role: {}", userId, role);
                    } else {
                        authorities = List.of();
                    }

                    if (!authorities.isEmpty()) {
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userId, null, authorities
                        );
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            }
        } catch (JwtException e) {
            log.error("Cannot set user authentication: {}", e.getMessage());
            // We don't block the request here, we just don't set the authentication.
            // SecurityConfig will block it if the endpoint requires authentication.
        } catch (Exception e) {
            log.error("Unexpected error during JWT authentication: {}", e.getMessage());
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
