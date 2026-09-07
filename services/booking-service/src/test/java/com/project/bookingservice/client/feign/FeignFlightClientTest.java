package com.project.bookingservice.client.feign;

import com.project.bookingservice.client.dto.SeatOperationRequest;
import com.project.bookingservice.client.dto.SeatReservationResult;
import com.project.bookingservice.exception.FlightReservationRejectedException;
import com.project.common.exception.ServiceUnavailableException;
import com.project.common.response.ApiResponse;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FeignFlightClient (Adapter).
 *
 * Verifies exception translation behavior:
 *  - FlightReservationRejectedException propagates unchanged (definitive)
 *  - ServiceUnavailableException propagates unchanged (already translated by ErrorDecoder)
 *  - RetryableException → ServiceUnavailableException (timeout/connection)
 *  - DecodeException → ServiceUnavailableException (ambiguous 2xx)
 *  - null data in response → ServiceUnavailableException
 *  - Generic Exception → ServiceUnavailableException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeignFlightClient (Adapter) Unit Tests")
class FeignFlightClientTest {

    @Mock FlightFeignClient feignClient;

    @InjectMocks FeignFlightClient adapter;

    static final UUID FLIGHT_ID     = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID FARE_CLASS_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    // ══════════════════════════════════════════════════════════════
    // reserveSeats
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("reserveSeats()")
    class ReserveSeats {

        @Test
        @DisplayName("FlightReservationRejectedException (from ErrorDecoder) → propagated unchanged")
        void whenDefiniteRejection_shouldPropagateUnchanged() {
            FlightReservationRejectedException rejection =
                    new FlightReservationRejectedException("Not enough seats", HttpStatus.CONFLICT);

            when(feignClient.reserveSeats(any(), any(), any())).thenThrow(rejection);

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(FlightReservationRejectedException.class)
                    .hasMessage("Not enough seats");
        }

        @Test
        @DisplayName("ServiceUnavailableException (from ErrorDecoder 5xx) → propagated unchanged")
        void whenServiceUnavailableFromErrorDecoder_shouldPropagateUnchanged() {
            when(feignClient.reserveSeats(any(), any(), any()))
                    .thenThrow(new ServiceUnavailableException("Flight service error: 503 SERVICE_UNAVAILABLE"));

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class);
        }

        @Test
        @DisplayName("RetryableException (timeout/connection refused) → ServiceUnavailableException")
        void whenRetryableException_shouldWrapAsServiceUnavailable() {
            RetryableException timeout = mock(RetryableException.class);
            when(feignClient.reserveSeats(any(), any(), any())).thenThrow(timeout);

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("unavailable");
        }

        @Test
        @DisplayName("DecodeException (malformed 2xx body) → ServiceUnavailableException (ambiguous)")
        void whenDecodeException_shouldWrapAsServiceUnavailable() {
            DecodeException decodeEx = mock(DecodeException.class);
            when(feignClient.reserveSeats(any(), any(), any())).thenThrow(decodeEx);

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class)
                    .hasMessageContaining("unreadable");
        }

        @Test
        @DisplayName("null ApiResponse → ServiceUnavailableException (contract violation)")
        void whenResponseIsNull_shouldThrowServiceUnavailable() {
            when(feignClient.reserveSeats(any(), any(), any())).thenReturn(null);

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class);
        }

        @Test
        @DisplayName("ApiResponse with null data → ServiceUnavailableException (contract violation)")
        void whenResponseDataIsNull_shouldThrowServiceUnavailable() {
            ApiResponse<SeatReservationResult> response = ApiResponse.success(null);
            when(feignClient.reserveSeats(any(), any(), any())).thenReturn(response);

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class);
        }

        @Test
        @DisplayName("unexpected RuntimeException → ServiceUnavailableException")
        void whenUnexpectedException_shouldWrapAsServiceUnavailable() {
            when(feignClient.reserveSeats(any(), any(), any()))
                    .thenThrow(new RuntimeException("Something unexpected"));

            assertThatThrownBy(() -> adapter.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // releaseSeats
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("releaseSeats()")
    class ReleaseSeats {

        @Test
        @DisplayName("FlightReservationRejectedException → propagated unchanged")
        void whenDefiniteRejectionDuringRelease_shouldPropagateUnchanged() {
            FlightReservationRejectedException rejection =
                    new FlightReservationRejectedException("Release rejected", HttpStatus.CONFLICT);

            doThrow(rejection).when(feignClient).releaseSeats(any(), any(), any());

            assertThatThrownBy(() -> adapter.releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(FlightReservationRejectedException.class);
        }

        @Test
        @DisplayName("RetryableException during release → ServiceUnavailableException")
        void whenRetryableExceptionDuringRelease_shouldWrapAsServiceUnavailable() {
            RetryableException timeout = mock(RetryableException.class);
            doThrow(timeout).when(feignClient).releaseSeats(any(), any(), any());

            assertThatThrownBy(() -> adapter.releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class);
        }

        @Test
        @DisplayName("DecodeException during release → ServiceUnavailableException")
        void whenDecodeExceptionDuringRelease_shouldWrapAsServiceUnavailable() {
            DecodeException decodeEx = mock(DecodeException.class);
            doThrow(decodeEx).when(feignClient).releaseSeats(any(), any(), any());

            assertThatThrownBy(() -> adapter.releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2))
                    .isInstanceOf(ServiceUnavailableException.class);
        }
    }
}
