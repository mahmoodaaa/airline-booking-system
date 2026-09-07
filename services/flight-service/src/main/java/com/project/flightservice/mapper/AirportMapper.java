package com.project.flightservice.mapper;

import com.project.flightservice.dto.request.AirportRequest;
import com.project.flightservice.dto.response.AirportResponse;
import com.project.flightservice.dto.response.SimpleAirportResponse;
import com.project.flightservice.entity.Airport;
import com.project.flightservice.enums.AirportStatus;
import org.springframework.stereotype.Component;

@Component
public class AirportMapper {

    public Airport toEntity(AirportRequest request) {
        if (request == null) return null;
        
        return Airport.builder()
                .iataCode(request.getIataCode().toUpperCase())
                .icaoCode(request.getIcaoCode() != null ? request.getIcaoCode().toUpperCase() : null)
                .name(request.getName())
                .city(request.getCity())
                .country(request.getCountry())
                .timezone(request.getTimezone())
                .status(AirportStatus.ACTIVE)
                .build();
    }

    public AirportResponse toResponse(Airport airport) {
        if (airport == null) return null;
        
        return AirportResponse.builder()
                .id(airport.getId())
                .iataCode(airport.getIataCode())
                .icaoCode(airport.getIcaoCode())
                .name(airport.getName())
                .city(airport.getCity())
                .country(airport.getCountry())
                .timezone(airport.getTimezone())
                .status(airport.getStatus())
                .createdAt(airport.getCreatedAt())
                .build();
    }

    public SimpleAirportResponse toSimpleResponse(Airport airport) {
        if (airport == null) return null;
        
        return SimpleAirportResponse.builder()
                .iataCode(airport.getIataCode())
                .city(airport.getCity())
                .build();
    }
}
