package com.project.apigateway.filter;

import com.project.apigateway.security.JwtValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global JWT Authentication + Authorization Filter.
 *
 * Order of operations (matches plan §13):
 * 1. Strip any incoming X-User-Id / X-User-Role headers (anti-spoofing)
 * 2. Check if endpoint is public → forward immediately
 * 3. Read & validate JWT
 * 4. On invalid → 401
 * 5. Check role for ADMIN-only endpoints → 403 if insufficient
 * 6. Add trusted X-User-Id / X-User-Role headers
 * 7. Forward to downstream service
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtValidator jwtValidator;

    // ──────────────────────────────────────────────────────────────────────────
    // Public endpoints — no JWT required
    // ──────────────────────────────────────────────────────────────────────────
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh-token",
            "/api/auth/verify-email",
            "/api/flights/search"
    );

    // Paths that need JWT but allow any valid role (CUSTOMER or ADMIN)
    // Everything else with a pattern below is ADMIN-only
    private static final List<String> ADMIN_ONLY_PREFIXES = List.of(
            "/api/airports",
            "/api/aircraft"
    );

    // These flight paths are ADMIN-only for mutating methods (POST/PUT/DELETE/PATCH)
    private static final String FLIGHTS_ADMIN_PATH = "/api/flights";

    // Internal trusted header names
    private static final String HEADER_USER_ID   = "X-User-Id";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    @Override
    public int getOrder() {
        return -1; // run before any other filter
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path   = request.getPath().value();
        String method = request.getMethod().name();

        // ── STEP 1: Strip spoofed identity headers from the client ──────────
        ServerHttpRequest sanitisedRequest = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_ROLE);
                })
                .build();
        exchange = exchange.mutate().request(sanitisedRequest).build();

        // ── STEP 2: Public endpoint check ────────────────────────────────────
        if (isPublicEndpoint(path, method)) {
            return chain.filter(exchange);
        }

        // ── STEP 3: Read Authorization header ────────────────────────────────
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Missing or malformed Authorization header for path: {}", path);
            return unauthorised(exchange.getResponse());
        }

        String token = authHeader.substring(7);

        // ── STEP 4: Validate JWT ──────────────────────────────────────────────
        Claims claims;
        try {
            claims = jwtValidator.validateAndExtract(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT for path {}: {}", path, e.getMessage());
            return unauthorised(exchange.getResponse());
        }

        String userId = jwtValidator.extractUserId(claims);
        String role   = jwtValidator.extractRole(claims);

        // ── STEP 5: Role-based authorisation ─────────────────────────────────
        if (requiresAdmin(path, method) && !"ADMIN".equals(role)) {
            log.debug("Access denied for role={} path={} method={}", role, path, method);
            return forbidden(exchange.getResponse());
        }

        // ── STEP 6: Add trusted identity headers ─────────────────────────────
        ServerHttpRequest enrichedRequest = exchange.getRequest().mutate()
                .header(HEADER_USER_ID,   userId != null ? userId : "")
                .header(HEADER_USER_ROLE, role   != null ? role   : "")
                .build();

        log.debug("JWT OK → userId={} role={} path={}", userId, role, path);
        return chain.filter(exchange.mutate().request(enrichedRequest).build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isPublicEndpoint(String path, String method) {
        // Exact public paths (e.g. /api/auth/login, /api/flights/search)
        for (String pub : PUBLIC_PATHS) {
            if (path.equals(pub) || path.startsWith(pub + "?")) {
                return true;
            }
        }

        // GET on /api/flights — explicitly allow specific sub-paths instead of broad matching
        if ("GET".equalsIgnoreCase(method) && path.startsWith(FLIGHTS_ADMIN_PATH)) {
            // Matches /api/flights/{uuid}
            if (path.matches("^/api/flights/[0-9a-fA-F\\-]+$")) {
                return true;
            }
            // Matches /api/flights/{uuid}/availability
            if (path.matches("^/api/flights/[0-9a-fA-F\\-]+/availability$")) {
                return true;
            }
            return false;
        }

        return false;
    }

    private boolean requiresAdmin(String path, String method) {
        // All methods on airports & aircraft are admin-only
        for (String adminPrefix : ADMIN_ONLY_PREFIXES) {
            if (path.startsWith(adminPrefix)) {
                return true;
            }
        }

        if (path.startsWith(FLIGHTS_ADMIN_PATH)) {
            String remainder = path.substring(FLIGHTS_ADMIN_PATH.length());
            boolean isExactFlightsList = remainder.isEmpty() || remainder.equals("/");
            boolean isMutatingMethod = List.of("POST", "PUT", "PATCH", "DELETE").contains(method.toUpperCase());

            // Admin-only: mutating methods (POST/PUT/PATCH/DELETE) OR exact GET /api/flights list
            return isMutatingMethod || (isExactFlightsList && "GET".equalsIgnoreCase(method));
        }

        return false;
    }

    private Mono<Void> unauthorised(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    private Mono<Void> forbidden(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }
}
