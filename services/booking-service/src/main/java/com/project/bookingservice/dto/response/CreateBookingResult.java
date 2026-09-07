package com.project.bookingservice.dto.response;

public record CreateBookingResult(
        BookingResponse booking,
        boolean created
) {}
