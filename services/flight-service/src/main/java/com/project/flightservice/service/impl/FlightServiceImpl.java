package com.project.flightservice.service.impl;

import com.project.common.exception.BadRequestException;
import com.project.common.exception.ConflictException;
import com.project.common.exception.RecordNotFoundException;
import com.project.flightservice.dto.request.FareClassRequest;
import com.project.flightservice.dto.request.FlightRequest;
import com.project.flightservice.dto.request.UpdateFlightStatusRequest;
import com.project.flightservice.dto.response.AvailabilityResponse;
import com.project.flightservice.dto.response.FlightResponse;
import com.project.flightservice.dto.response.FlightSearchResponse;
import com.project.flightservice.dto.response.SeatReservationResponse;
import com.project.flightservice.entity.Aircraft;
import com.project.flightservice.entity.Airport;
import com.project.flightservice.entity.FareClass;
import com.project.flightservice.entity.Flight;
import com.project.flightservice.enums.AircraftStatus;
import com.project.flightservice.enums.AirportStatus;
import com.project.flightservice.enums.FareClassType;
import com.project.flightservice.enums.FlightStatus;
import com.project.flightservice.mapper.FareClassMapper;
import com.project.flightservice.mapper.FlightMapper;
import com.project.flightservice.repository.AircraftRepository;
import com.project.flightservice.repository.AirportRepository;
import com.project.flightservice.repository.FareClassRepository;
import com.project.flightservice.repository.FlightRepository;
import com.project.flightservice.service.FlightService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightServiceImpl implements FlightService {

    private final FlightRepository flightRepository;
    private final AirportRepository airportRepository;
    private final AircraftRepository aircraftRepository;
    private final FareClassRepository fareClassRepository;
    private final FlightMapper flightMapper;
    private final FareClassMapper fareClassMapper;

    @Override
    @Transactional
    public FlightResponse createFlight(FlightRequest request) {
        if (flightRepository.existsByFlightNumber(request.getFlightNumber().toUpperCase())) {
            throw new ConflictException("Flight number " + request.getFlightNumber().toUpperCase() + " already exists");
        }

        Airport origin = airportRepository.findById(request.getOriginAirportId())
                .orElseThrow(() -> new RecordNotFoundException("Origin airport not found"));

        if (origin.getStatus() != AirportStatus.ACTIVE) {
            throw new BadRequestException("Origin airport must be active");
        }

        Airport destination = airportRepository.findById(request.getDestinationAirportId())
                .orElseThrow(() -> new RecordNotFoundException("Destination airport not found"));

        if (destination.getStatus() != AirportStatus.ACTIVE) {
            throw new BadRequestException("Destination airport must be active");
        }

        Aircraft aircraft = aircraftRepository.findById(request.getAircraftId())
                .orElseThrow(() -> new RecordNotFoundException("Aircraft not found"));

        if (aircraft.getStatus() != AircraftStatus.ACTIVE) {
            throw new BadRequestException("Aircraft must be active");
        }

        // Validate uniqueness of ClassType within the same request
        Set<FareClassType> uniqueClasses = new HashSet<>();
        for (FareClassRequest fareReq : request.getFares()) {
            if (!uniqueClasses.add(fareReq.getClassType())) {
                throw new ConflictException(
                        "Duplicate FareClassType provided for this flight: " + fareReq.getClassType());
            }
        }

        Flight flight = Flight.builder()
                .flightNumber(request.getFlightNumber().toUpperCase())
                .originAirport(origin)
                .destinationAirport(destination)
                .aircraft(aircraft)
                .departureTime(request.getDepartureTime())
                .arrivalTime(request.getArrivalTime())
                .status(FlightStatus.SCHEDULED)
                .build();

        for (FareClassRequest fareReq : request.getFares()) {
            FareClass fareClass = fareClassMapper.toEntity(fareReq, flight);
            flight.addFareClass(fareClass);
        }

        Flight savedFlight = flightRepository.save(flight);
        return flightMapper.toResponse(savedFlight);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightResponse getFlightById(UUID id) {
        Flight flight = findFlightEntityById(id);
        return flightMapper.toResponse(flight);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FlightResponse> getAllFlights(Pageable pageable) {
        return flightRepository.findAll(pageable)
                .map(flightMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlightSearchResponse> searchFlights(String origin, String destination, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        List<Flight> flights = flightRepository
                .findByOriginAirport_IataCodeAndDestinationAirport_IataCodeAndDepartureTimeBetween(
                        origin.toUpperCase(), destination.toUpperCase(), startOfDay, endOfDay);

        return flights.stream()
                .map(flightMapper::toSearchResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(UUID flightId) {
        Flight flight = findFlightEntityById(flightId);
        return AvailabilityResponse.builder()
                .flightId(flight.getId())
                .fares(flight.getFareClasses().stream()
                        .map(fareClassMapper::toResponse)
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    @Transactional
    public FlightResponse updateFlightStatus(UUID id, UpdateFlightStatusRequest request) {
        Flight flight = findFlightEntityById(id);
        flight.setStatus(request.getStatus());
        return flightMapper.toResponse(flightRepository.save(flight));
    }

    private Flight findFlightEntityById(UUID id) {
        return flightRepository.findById(id)
                .orElseThrow(() -> new RecordNotFoundException("Flight not found with ID: " + id));
    }

    @Override
    @Transactional
    public SeatReservationResponse reserveSeats(UUID flightId, UUID fareClassId, int count) {
        if (count <= 0) {
            throw new BadRequestException("Seat count must be greater than zero");
        }

        FareClass fareClass = fareClassRepository.findById(fareClassId)
                .orElseThrow(() -> new RecordNotFoundException("Fare class not found with ID: " + fareClassId));

        Flight flight = fareClass.getFlight();

        if (!flight.getId().equals(flightId)) {
            throw new BadRequestException("Fare class does not belong to the specified flight");
        }

        if (flight.getStatus() == FlightStatus.CANCELLED || flight.getStatus() == FlightStatus.COMPLETED) {
            throw new ConflictException("Flight is not available for booking");
        }

        if (!flight.getDepartureTime().isAfter(LocalDateTime.now())) {
            throw new ConflictException("Flight has already departed");
        }

        if (fareClass.getAvailableSeats() < count) {
            throw new ConflictException("Not enough seats available");
        }

        fareClass.setAvailableSeats(fareClass.getAvailableSeats() - count);
        fareClassRepository.save(fareClass);

        return fareClassMapper.toSeatReservationResponse(fareClass);
    }

    @Override
    @Transactional
    public void releaseSeats(UUID flightId, UUID fareClassId, int count) {
        if (count <= 0) {
            throw new BadRequestException("Seat count must be greater than zero");
        }

        FareClass fareClass = fareClassRepository.findById(fareClassId)
                .orElseThrow(() -> new RecordNotFoundException("Fare class not found with ID: " + fareClassId));

        if (!fareClass.getFlight().getId().equals(flightId)) {
            throw new BadRequestException("Fare class does not belong to the specified flight");
        }

        int updatedAvailableSeats = fareClass.getAvailableSeats() + count;

        if (updatedAvailableSeats > fareClass.getTotalSeats()) {
            log.error("Seat release exceeds capacity. fareClassId={}, available={}, total={}, count={}",
                    fareClassId, fareClass.getAvailableSeats(), fareClass.getTotalSeats(), count);
            throw new ConflictException("Seat release would exceed total seat capacity");
        }

        fareClass.setAvailableSeats(updatedAvailableSeats);
        fareClassRepository.save(fareClass);
    }
}
