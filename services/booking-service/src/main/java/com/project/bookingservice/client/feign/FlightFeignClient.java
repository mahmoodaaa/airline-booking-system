package com.project.bookingservice.client.feign;

import com.project.bookingservice.client.dto.SeatOperationRequest;
import com.project.bookingservice.client.dto.SeatReservationResult;
import com.project.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Declarative Feign client for flight-service internal inventory endpoints.
 *
 * Configuration is scoped via FlightFeignConfig — NOT global —
 * so it does NOT affect any other Feign clients added in the future.
 *
 * Error handling is delegated to {@link FlightClientErrorDecoder}:
 *   4xx (400/404/409) → FlightReservationRejectedException
 *   401/403           → ServiceUnavailableException (internal auth failure)
 *   5xx               → ServiceUnavailableException (ambiguous outcome)
 */
@FeignClient(
        name = "flight-service",
        url = "${flight-service.url}",
        configuration = FlightFeignConfig.class
)
public interface FlightFeignClient {

    @PostMapping("/internal/flights/{flightId}/fare-classes/{fareClassId}/reserve")
    ApiResponse<SeatReservationResult> reserveSeats(
            @PathVariable UUID flightId,
            @PathVariable UUID fareClassId,
            @RequestBody SeatOperationRequest request
    );

    @PostMapping("/internal/flights/{flightId}/fare-classes/{fareClassId}/release")
    void releaseSeats(
            @PathVariable UUID flightId,
            @PathVariable UUID fareClassId,
            @RequestBody SeatOperationRequest request
    );
}
