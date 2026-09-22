package com.project.paymentservice.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Generates short-lived Service JWTs used to authenticate
 * payment-service against internal booking-service endpoints.
 *
 * Token claims:
 *   sub  = "payment-service"
 *   role = "SERVICE"
 *   type = "SERVICE"
 *   exp  = now + 60 seconds (short-lived; a new token is minted per Feign request)
 */
@Component
public class InternalTokenProvider {

    private static final long SERVICE_TOKEN_TTL_MS = 60_000L; // 60 seconds

    private final SecretKey secretKey;

    public InternalTokenProvider(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateServiceToken() {
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .subject("payment-service")
                .claim("role", "SERVICE")
                .claim("type", "SERVICE")
                .issuedAt(new Date(now))
                .expiration(new Date(now + SERVICE_TOKEN_TTL_MS))
                .signWith(secretKey)
                .compact();
    }
}
