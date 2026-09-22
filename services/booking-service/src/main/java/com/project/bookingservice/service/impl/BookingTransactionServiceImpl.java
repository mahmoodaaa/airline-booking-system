package com.project.bookingservice.service.impl;

import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.repository.BookingRepository;
import com.project.bookingservice.service.BookingTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Each method runs in its own REQUIRES_NEW transaction so DB commits
 * happen immediately — before any external (REST) calls.
 *
 * This is the key pattern that lets BookingServiceImpl keep REST calls
 * outside @Transactional boundaries.
 */
@Service
@RequiredArgsConstructor
public class BookingTransactionServiceImpl implements BookingTransactionService {

    private final BookingRepository bookingRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking createClaim(Booking booking) {
        return bookingRepository.saveAndFlush(booking);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking finalizeBooking(Booking booking) {
        return bookingRepository.saveAndFlush(booking);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteClaim(UUID bookingId) {
        bookingRepository.deleteById(bookingId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transitionStatus(UUID bookingId, BookingStatus expectedStatus, BookingStatus newStatus) {

        int updatedRows = bookingRepository.transitionStatus(
                bookingId,
                expectedStatus,
                newStatus,
                java.time.LocalDateTime.now()
        );

        return updatedRows == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean confirmPayment(UUID bookingId, UUID paymentId, LocalDateTime confirmedAt) {
        int updatedRows = bookingRepository.confirmPayment(bookingId, paymentId, confirmedAt, confirmedAt);
        return updatedRows > 0;
    }
}
