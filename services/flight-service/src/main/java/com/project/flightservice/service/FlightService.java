package com.project.flightservice.service;

import com.project.flightservice.dto.request.FlightRequest;
import com.project.flightservice.dto.request.UpdateFlightStatusRequest;
import com.project.flightservice.dto.response.AvailabilityResponse;
import com.project.flightservice.dto.response.FlightResponse;
import com.project.flightservice.dto.response.FlightSearchResponse;
import com.project.flightservice.dto.response.SeatReservationResponse;
import com.project.flightservice.enums.FareClassType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FlightService {
    FlightResponse createFlight(FlightRequest request);
    FlightResponse getFlightById(UUID id);
    Page<FlightResponse> getAllFlights(Pageable pageable);
    List<FlightSearchResponse> searchFlights(String origin, String destination, LocalDate date);
    AvailabilityResponse getAvailability(UUID flightId);
    FlightResponse updateFlightStatus(UUID id, UpdateFlightStatusRequest request);
    SeatReservationResponse reserveSeats(UUID flightId, UUID fareClassId, int count);
    void releaseSeats(UUID flightId, UUID fareClassId, int count);
}
