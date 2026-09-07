package com.project.authservice.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JwtProvider {

    private final JwtConstant jwtConstant;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtConstant.getSecretKey().getBytes());
    }

    public String generateToken(Authentication auth) {
        return buildToken(auth, jwtConstant.getAccessTokenExpiration());
    }

    public String generateRefreshToken(Authentication auth) {
        return buildToken(auth, jwtConstant.getRefreshTokenExpiration());
    }

    private String buildToken(Authentication auth, long expirationTime) {

        com.project.authservice.entity.User userDetails =
                (com.project.authservice.entity.User) auth.getPrincipal();

        String role = userDetails.getRole().name();

        String userId = userDetails.getId().toString();

        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .claim("email", auth.getName())
                .claim("role", role)
                .signWith(getSigningKey())
                .compact();
    }

    public String getEmailFromJwtToken(String jwt) {
        return extractClaims(jwt).get("email", String.class);
    }

    public String getRoleFromJwtToken(String jwt) {
        return extractClaims(jwt).get("role", String.class);
    }

    public boolean isTokenExpired(String jwt) {
        Date expiration = extractClaims(jwt).getExpiration();
        return expiration.before(new Date());
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String populateAuthorities(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .map(role -> role.startsWith("ROLE_")
                        ? role.substring(5)
                        : role)
                .collect(Collectors.joining(","));
    }
}