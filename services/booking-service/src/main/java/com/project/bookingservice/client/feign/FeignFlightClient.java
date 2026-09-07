package com.project.bookingservice.client.feign;

import com.project.bookingservice.client.FlightClient;
import com.project.bookingservice.client.dto.SeatOperationRequest;
import com.project.bookingservice.client.dto.SeatReservationResult;
import com.project.bookingservice.exception.FlightReservationRejectedException;
import com.project.common.exception.ServiceUnavailableException;
import com.project.common.response.ApiResponse;
import feign.RetryableException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Feign-based adapter that implements the domain-facing {@link FlightClient}.
 *
 * This layer is responsible for:
 * 1. Invoking the declarative {@link FlightFeignClient}.
 * 2. Catching Feign-specific exceptions and translating them into domain semantics.
 * 3. Ensuring that ambiguous outcomes (timeouts, 5xx, decode errors) consistently
 *    result in a ServiceUnavailableException, triggering reconciliation in the business layer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeignFlightClient implements FlightClient {

    private final FlightFeignClient feignClient;

    // ============================================================
    // Reserve Seats
    // ============================================================

    @Override
    public SeatReservationResult reserveSeats(UUID flightId, UUID fareClassId, int count) {

        log.info("Reserving {} seats — flightId={} fareClassId={}", count, flightId, fareClassId);

        try {
            ApiResponse<SeatReservationResult> response =
                    feignClient.reserveSeats(flightId, fareClassId, new SeatOperationRequest(count));

            if (response == null || response.getData() == null) {
                // HTTP 2xx succeeded but response contract is violated.
                // We must treat this as ambiguous since reserve MAY have happened.
                throw new IllegalStateException(
                        "Empty or invalid response from flight-service reserve"
                );
            }

            log.info("Reservation successful — flightId={} fareClassId={}", flightId, fareClassId);
            return response.getData();

        } catch (FlightReservationRejectedException e) {
            // Definitive rejection thrown by our ErrorDecoder (e.g., 400, 404, 409).
            // BookingServiceImpl will safely delete the claim.
            throw e;

        } catch (ServiceUnavailableException e) {
            // Ambiguous outcome or internal auth failure thrown by ErrorDecoder (e.g., 5xx, 401, 403).
            log.error("CRITICAL: Reserve outcome unknown or internal failure — flightId={} fareClassId={}", flightId, fareClassId);
            throw e;

        } catch (RetryableException e) {
            // Network timeout / connection refused.
            // Reserve MAY have reached the server and executed. Ambiguous outcome.
            log.error("CRITICAL: Reserve timeout/connection failure — flightId={} fareClassId={}", flightId, fareClassId, e);
            throw new ServiceUnavailableException("Flight service is currently unavailable");

        } catch (DecodeException e) {
            // A 2xx response was received but its body could not be decoded.
            // From Booking's perspective we cannot safely reconstruct the
            // authoritative result, so treat the outcome as ambiguous.
            log.error("CRITICAL: Reserve response malformed — flightId={} fareClassId={}", flightId, fareClassId, e);
            throw new ServiceUnavailableException("Flight service returned unreadable response");

        } catch (Exception e) {
            // Catch-all for any other unexpected errors (ambiguous outcome).
            log.error("CRITICAL: Unexpected error during reserve — flightId={} fareClassId={}", flightId, fareClassId, e);
            throw new ServiceUnavailableException("Flight service is currently unavailable");
        }
    }

    // ============================================================
    // Release Seats
    // ============================================================

    @Override
    public void releaseSeats(UUID flightId, UUID fareClassId, int count) {

        log.info("Releasing {} seats — flightId={} fareClassId={}", count, flightId, fareClassId);

        try {
            feignClient.releaseSeats(flightId, fareClassId, new SeatOperationRequest(count));
            log.info("Release successful — flightId={} fareClassId={}", flightId, fareClassId);

        } catch (FlightReservationRejectedException e) {
            // Flight explicitly rejected the release (e.g., availableSeats + count > totalSeats).
            // This is a definitive rejection of the release action.
            log.error("CRITICAL: Release rejected by flight-service. flightId={} fareClassId={} status={}",
                    flightId, fareClassId, e.getStatus());
            throw e;

        } catch (ServiceUnavailableException e) {
            // 5xx / 401 / 403
            log.error("CRITICAL: Release outcome unknown or internal failure — flightId={} fareClassId={}", flightId, fareClassId);
            throw e;

        } catch (RetryableException e) {
            // Network timeout / connection refused.
            log.error("CRITICAL: Release timeout/connection failure — flightId={} fareClassId={}", flightId, fareClassId, e);
            throw new ServiceUnavailableException("Flight service is currently unavailable");

        } catch (DecodeException e) {
            // Malformed response.
            log.error("CRITICAL: Release response malformed — flightId={} fareClassId={}", flightId, fareClassId, e);
            throw new ServiceUnavailableException("Flight service returned unreadable response");

        } catch (Exception e) {
            // Catch-all.
            log.error("CRITICAL: Unexpected error during release — flightId={} fareClassId={}", flightId, fareClassId, e);
            throw new ServiceUnavailableException("Flight service is currently unavailable");
        }
    }
}
