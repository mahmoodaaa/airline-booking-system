package com.project.bookingservice.client;

import com.project.bookingservice.client.dto.SeatReservationResult;

import java.util.UUID;

/**
 * Domain-facing contract for flight inventory operations.
 *
 * BookingServiceImpl depends ONLY on this interface.
 * The transport implementation (RestClient, Feign, etc.) is fully swappable
 * without touching any business logic.
 */
public interface FlightClient {

    /**
     * Reserve {@code count} seats on the given flight/fareClass.
     *
     * @throws com.project.bookingservice.exception.FlightReservationRejectedException
     *         if flight-service definitively rejects (400/404/409)
     * @throws com.project.common.exception.ServiceUnavailableException
     *         if outcome is ambiguous (5xx/timeout/auth-error)
     */
    SeatReservationResult reserveSeats(UUID flightId, UUID fareClassId, int count);

    /**
     * Release {@code count} seats back to the given flight/fareClass.
     *
     * @throws com.project.bookingservice.exception.FlightReservationRejectedException
     *         if flight-service definitively rejects the release
     * @throws com.project.common.exception.ServiceUnavailableException
     *         if outcome is ambiguous
     */
    void releaseSeats(UUID flightId, UUID fareClassId, int count);
}