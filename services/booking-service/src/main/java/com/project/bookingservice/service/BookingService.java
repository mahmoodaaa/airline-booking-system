package com.project.bookingservice.service;

import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.response.BookingResponse;
import com.project.bookingservice.dto.response.CreateBookingResult;
import com.project.bookingservice.dto.response.PaymentContextResponse;
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

    /**
     * Atomically transitions PENDING -> PAYMENT_PENDING and returns
     * the authoritative payment context.
     *
     * The existing expiresAt is replaced once with the protected
     * payment-window deadline.
     *
     * Idempotent:
     * if already PAYMENT_PENDING with expiresAt > now,
     * return the existing context without extending expiresAt.
     *
     * Throws if Booking is no longer eligible.
     */
    PaymentContextResponse startPayment(UUID bookingId, UUID userId);
    void confirmPayment(UUID bookingId, UUID paymentId);
}
