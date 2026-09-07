package com.project.flightservice.mapper;

import com.project.flightservice.dto.response.FlightResponse;
import com.project.flightservice.dto.response.FlightSearchResponse;
import com.project.flightservice.entity.Flight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FlightMapper {

    private final AirportMapper airportMapper;
    private final AircraftMapper aircraftMapper;
    private final FareClassMapper fareClassMapper;

    public FlightResponse toResponse(Flight flight) {
        if (flight == null) return null;

        return FlightResponse.builder()
                .id(flight.getId())
                .flightNumber(flight.getFlightNumber())
                .origin(airportMapper.toResponse(flight.getOriginAirport()))
                .destination(airportMapper.toResponse(flight.getDestinationAirport()))
                .aircraft(aircraftMapper.toResponse(flight.getAircraft()))
                .departureTime(flight.getDepartureTime())
                .arrivalTime(flight.getArrivalTime())
                .status(flight.getStatus())
                .fares(flight.getFareClasses().stream()
                        .map(fareClassMapper::toResponse)
                        .collect(Collectors.toList()))
                .createdAt(flight.getCreatedAt())
                .build();
    }

    public FlightSearchResponse toSearchResponse(Flight flight) {
        if (flight == null) return null;

        return FlightSearchResponse.builder()
                .id(flight.getId())
                .flightNumber(flight.getFlightNumber())
                .origin(airportMapper.toSimpleResponse(flight.getOriginAirport()))
                .destination(airportMapper.toSimpleResponse(flight.getDestinationAirport()))
                .departureTime(flight.getDepartureTime())
                .arrivalTime(flight.getArrivalTime())
                .status(flight.getStatus())
                .fares(flight.getFareClasses().stream()
                        .map(fareClassMapper::toResponse)
                        .collect(Collectors.toList()))
                .build();
    }
}
