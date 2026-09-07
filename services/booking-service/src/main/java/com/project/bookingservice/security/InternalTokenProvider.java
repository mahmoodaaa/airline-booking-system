package com.project.bookingservice.security;

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
 * booking-service against internal flight-service endpoints.
 *
 * Token claims:
 *   sub  = "booking-service"
 *   type = "SERVICE"
 *   exp  = now + 60 seconds (short-lived)
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
                .subject("booking-service")
                .claim("type", "SERVICE")
                .issuedAt(new Date(now))
                .expiration(new Date(now + SERVICE_TOKEN_TTL_MS))
                .signWith(secretKey)
                .compact();
    }
}
