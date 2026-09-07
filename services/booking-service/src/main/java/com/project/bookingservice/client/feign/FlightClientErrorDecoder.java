package com.project.bookingservice.client.feign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.project.bookingservice.exception.FlightReservationRejectedException;
import com.project.common.exception.ServiceUnavailableException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.InputStream;

/**
 * Translates HTTP error responses from flight-service into domain exceptions.
 *
 * Registered exclusively via {@link FlightFeignConfig} — not as a global bean.
 *
 * Mapping:
 *   400 / 404 / 409  → FlightReservationRejectedException  (definitive — delete claim)
 *   401 / 403        → ServiceUnavailableException          (internal auth/config error)
 *   5xx / other      → ServiceUnavailableException          (ambiguous — FAILED + reconciliation)
 */
@Slf4j
@RequiredArgsConstructor
public class FlightClientErrorDecoder implements ErrorDecoder {

    /**
     * Spring-managed ObjectMapper — injected via @Bean method parameter in FlightFeignConfig.
     * Using the configured Spring mapper ensures JavaTimeModule and other customizations are applied.
     */
    private final ObjectMapper objectMapper;

    @Override
    public Exception decode(String methodKey, Response response) {

        HttpStatus status = HttpStatus.valueOf(response.status());
        String message = extractMessage(response, "Flight reservation was rejected");

        log.warn("flight-service returned {} for {}: {}", status, methodKey, message);

        // ─── Definitive rejections — reservation did NOT happen ──────────────
        // Booking service may safely delete its claim.
        if (status == HttpStatus.BAD_REQUEST            // 400 — invalid input
         || status == HttpStatus.NOT_FOUND              // 404 — flight/fareClass not found
         || status == HttpStatus.CONFLICT) {            // 409 — insufficient seats

            return new FlightReservationRejectedException(message, status);
        }

        // ─── Internal auth/config failure (401 / 403) ────────────────────────
        // Security rejected booking-service JWT before reaching the Controller.
        // Reserve did NOT happen (Security filters run before business logic).
        // This is a definitive rejection of the transaction.
        // We throw FlightReservationRejectedException so BookingService deletes the claim,
        // but we use 502 BAD_GATEWAY so the customer doesn't see an auth error.
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            log.error("CRITICAL: flight-service rejected booking-service JWT. " +
                      "Check SERVICE token config. method={} status={}", methodKey, status);
            return new FlightReservationRejectedException(
                    "Internal service authentication failure",
                    HttpStatus.BAD_GATEWAY
            );
        }

        // ─── 5xx / anything else = ambiguous outcome ─────────────────────────
        // Reserve MAY or MAY NOT have happened. Do not delete claim — transition to FAILED.
        return new ServiceUnavailableException(
                "Flight service error: " + status
        );
    }

    /**
     * Attempts to read the "message" field from the JSON response body.
     * Returns {@code fallback} if body is absent, not JSON, or field is missing.
     */
    private String extractMessage(Response response, String fallback) {
        if (response.body() == null) return fallback;
        try (InputStream body = response.body().asInputStream()) {
            JsonNode root = objectMapper.readTree(body);
            JsonNode msgNode = root.get("message");
            if (msgNode != null && !msgNode.isNull()) {
                return msgNode.asText();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
