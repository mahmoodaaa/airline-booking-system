package com.project.flightservice.mapper;

import com.project.flightservice.dto.request.FareClassRequest;
import com.project.flightservice.dto.response.FareClassResponse;
import com.project.flightservice.dto.response.SeatReservationResponse;
import com.project.flightservice.entity.FareClass;
import com.project.flightservice.entity.Flight;
import org.springframework.stereotype.Component;

@Component
public class FareClassMapper {

    public FareClass toEntity(FareClassRequest request, Flight flight) {
        if (request == null) return null;

        return FareClass.builder()
                .flight(flight)
                .classType(request.getClassType())
                .price(request.getPrice())
                .currency(request.getCurrency())
                .totalSeats(request.getTotalSeats())
                .availableSeats(request.getTotalSeats()) // available = total upon creation
                .build();
    }

    public FareClassResponse toResponse(FareClass fareClass) {
        if (fareClass == null) return null;

        return FareClassResponse.builder()
                .id(fareClass.getId())
                .classType(fareClass.getClassType())
                .price(fareClass.getPrice())
                .currency(fareClass.getCurrency())
                .totalSeats(fareClass.getTotalSeats())
                .availableSeats(fareClass.getAvailableSeats())
                .build();
    }

    public SeatReservationResponse toSeatReservationResponse(FareClass fareClass) {
        if (fareClass == null || fareClass.getFlight() == null) return null;

        Flight flight = fareClass.getFlight();

        return SeatReservationResponse.builder()
                .flightId(flight.getId())
                .fareClassId(fareClass.getId())
                .flightNumber(flight.getFlightNumber())
                .originIata(flight.getOriginAirport().getIataCode())
                .destinationIata(flight.getDestinationAirport().getIataCode())
                .departureTime(flight.getDepartureTime())
                .arrivalTime(flight.getArrivalTime())
                .fareClassType(fareClass.getClassType())
                .price(fareClass.getPrice())
                .currency(fareClass.getCurrency())
                .availableSeats(fareClass.getAvailableSeats())
                .build();
    }
}
