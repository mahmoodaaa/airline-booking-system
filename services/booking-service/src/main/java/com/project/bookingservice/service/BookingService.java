package com.project.bookingservice.service;

import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.response.BookingResponse;
import com.project.bookingservice.dto.response.CreateBookingResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface BookingService {

    // ==============================
    // CUSTOMER use cases
    // ==============================

    CreateBookingResult createBooking(UUID userId, String idempotencyKey, BookingRequest request);

    BookingResponse getMyBookingById(UUID bookingId, UUID userId);

    Page<BookingResponse> getMyBookings(UUID userId, Pageable pageable);

    BookingResponse cancelBooking(UUID bookingId, UUID userId);

    // ==============================
    // ADMIN use cases
    // ==============================

    BookingResponse getBookingById(UUID bookingId);

    Page<BookingResponse> getAllBookings(Pageable pageable);

    // ==============================
    // INTERNAL (Payment Service)
    // ==============================

    com.project.bookingservice.dto.response.PaymentContextResponse getPaymentContext(UUID bookingId);

    void confirmPayment(UUID bookingId, String paymentId);
}
