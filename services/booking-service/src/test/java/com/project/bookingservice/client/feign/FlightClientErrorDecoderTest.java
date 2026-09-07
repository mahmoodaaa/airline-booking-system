package com.project.bookingservice.client.feign;

import com.project.bookingservice.exception.FlightReservationRejectedException;
import com.project.common.exception.ServiceUnavailableException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for FlightClientErrorDecoder.
 *
 * Verifies the HTTP status → domain exception mapping:
 *
 *   400 / 404 / 409  → FlightReservationRejectedException (with original status)
 *   401 / 403        → FlightReservationRejectedException (with 502 BAD_GATEWAY — internal auth failure)
 *   500 / 503 / 5xx  → ServiceUnavailableException (ambiguous outcome)
 *
 * Also verifies JSON message extraction from response body.
 */
@DisplayName("FlightClientErrorDecoder Unit Tests")
class FlightClientErrorDecoderTest {

    FlightClientErrorDecoder decoder;

    @BeforeEach
    void setup() {
        decoder = new FlightClientErrorDecoder(new ObjectMapper());
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private Response buildResponse(int statusCode, String body) {
        return Response.builder()
                .status(statusCode)
                .reason("")
                .request(Request.create(
                        Request.HttpMethod.POST,
                        "/internal/test",
                        Collections.emptyMap(),
                        null,
                        StandardCharsets.UTF_8,
                        new RequestTemplate()))
                .headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    private Response buildResponse(int statusCode) {
        return buildResponse(statusCode, "{}");
    }

    // ══════════════════════════════════════════════════════════════
    // 4xx — Definitive Rejections
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "HTTP {0} → FlightReservationRejectedException")
    @ValueSource(ints = {400, 404, 409})
    @DisplayName("4xx definitive codes → FlightReservationRejectedException with same status")
    void definitiveRejectionStatuses_shouldThrowFlightReservationRejectedException(int statusCode) {
        Response response = buildResponse(statusCode);

        Exception ex = decoder.decode("BookingServiceImpl#reserveSeats", response);

        assertThat(ex)
                .isInstanceOf(FlightReservationRejectedException.class);
        FlightReservationRejectedException rejected = (FlightReservationRejectedException) ex;
        assertThat(rejected.getStatus().value()).isEqualTo(statusCode);
    }

    @Test
    @DisplayName("400 response with JSON message → exception message extracted from body")
    void whenResponseHasJsonMessage_shouldExtractMessageFromBody() {
        String body = "{\"message\": \"Not enough available seats. Requested: 9, Available: 2\"}";
        Response response = buildResponse(409, body);

        Exception ex = decoder.decode("reserveSeats", response);

        assertThat(ex)
                .isInstanceOf(FlightReservationRejectedException.class)
                .hasMessageContaining("Not enough available seats");
    }

    @Test
    @DisplayName("400 response with non-JSON body → fallback message used")
    void whenResponseBodyIsNotJson_shouldUseFallbackMessage() {
        Response response = buildResponse(400, "not a json body");

        Exception ex = decoder.decode("reserveSeats", response);

        assertThat(ex)
                .isInstanceOf(FlightReservationRejectedException.class)
                .hasMessage("Flight reservation was rejected");
    }

    // ══════════════════════════════════════════════════════════════
    // 401 / 403 — Internal auth failure → 502
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "HTTP {0} → FlightReservationRejectedException with 502 status")
    @ValueSource(ints = {401, 403})
    @DisplayName("401/403 → FlightReservationRejectedException with 502 BAD_GATEWAY")
    void authFailureStatuses_shouldReturnRejectionWith502(int statusCode) {
        Response response = buildResponse(statusCode);

        Exception ex = decoder.decode("reserveSeats", response);

        assertThat(ex).isInstanceOf(FlightReservationRejectedException.class);
        FlightReservationRejectedException rejected = (FlightReservationRejectedException) ex;
        // IMPORTANT: must NOT expose 401/403 to customer — use 502 BAD_GATEWAY
        assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(rejected).hasMessageContaining("authentication failure");
    }

    // ══════════════════════════════════════════════════════════════
    // 5xx — Ambiguous Outcome
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "HTTP {0} → ServiceUnavailableException")
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("5xx → ServiceUnavailableException (ambiguous — do not delete claim)")
    void serverErrorStatuses_shouldThrowServiceUnavailableException(int statusCode) {
        Response response = buildResponse(statusCode);

        Exception ex = decoder.decode("reserveSeats", response);

        assertThat(ex).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("null response body → fallback message used, no NPE")
    void whenResponseBodyIsNull_shouldNotThrowNpe() {
        Response response = Response.builder()
                .status(409)
                .reason("")
                .request(Request.create(
                        Request.HttpMethod.POST, "/test",
                        Collections.emptyMap(), null,
                        StandardCharsets.UTF_8, new RequestTemplate()))
                .headers(Collections.emptyMap())
                .body((byte[]) null)
                .build();

        Exception ex = decoder.decode("reserveSeats", response);

        assertThat(ex)
                .isInstanceOf(FlightReservationRejectedException.class)
                .hasMessage("Flight reservation was rejected");
    }
}
