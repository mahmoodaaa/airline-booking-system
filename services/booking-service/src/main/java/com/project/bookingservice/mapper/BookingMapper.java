package com.project.bookingservice.mapper;

import com.project.bookingservice.dto.request.PassengerRequest;
import com.project.bookingservice.dto.response.BookingResponse;
import com.project.bookingservice.dto.response.PassengerResponse;
import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.entity.BookingPassenger;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class BookingMapper {

    // =========================
    // Entity -> Response
    // =========================

    public BookingResponse toResponse(Booking booking) {
        if (booking == null) {
            return null;
        }

        return BookingResponse.builder()
                .id(booking.getId())
                .userId(booking.getUserId())
                .flightId(booking.getFlightId())
                .fareClassId(booking.getFareClassId())
                .flightNumber(booking.getFlightNumber())
                .originIata(booking.getOriginIata())
                .destinationIata(booking.getDestinationIata())
                .departureTime(booking.getDepartureTime())
                .arrivalTime(booking.getArrivalTime())
                .fareClassType(booking.getFareClassType())
                .priceAtBooking(booking.getPriceAtBooking())
                .totalAmount(booking.getTotalAmount())
                .currency(booking.getCurrency())
                .paymentId(booking.getPaymentId())
                .confirmedAt(booking.getConfirmedAt())
                .status(booking.getStatus())
                .expiresAt(booking.getExpiresAt())
                .createdAt(booking.getCreatedAt())
                .updatedAt(booking.getUpdatedAt())
                .passengers(
                        booking.getPassengers() == null
                                ? java.util.List.of()
                                : booking.getPassengers()
                                        .stream()
                                        .map(this::toPassengerResponse)
                                        .collect(Collectors.toList()))
                .build();
    }

    // =========================
    // Passenger Entity -> Response
    // =========================

    public PassengerResponse toPassengerResponse(
            BookingPassenger passenger) {

        if (passenger == null) {
            return null;
        }

        return PassengerResponse.builder()
                .id(passenger.getId())
                .firstName(passenger.getFirstName())
                .lastName(passenger.getLastName())
                .passportNumber(passenger.getPassportNumber())
                .dateOfBirth(passenger.getDateOfBirth())
                .nationality(passenger.getNationality())
                .build();
    }

    // =========================
    // Passenger Request -> Entity
    // =========================

    public BookingPassenger toPassengerEntity(
            PassengerRequest request) {

        if (request == null) {
            return null;
        }

        return BookingPassenger.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .passportNumber(request.getPassportNumber())
                .dateOfBirth(request.getDateOfBirth())
                .nationality(
                        request.getNationality() != null ? request.getNationality().toUpperCase() : null)
                .build();
    }
}
