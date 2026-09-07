package com.project.bookingservice.service.impl;

import com.project.bookingservice.client.FlightClient;
import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.repository.BookingRepository;
import com.project.bookingservice.service.BookingExpirationService;
import com.project.bookingservice.service.BookingTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingExpirationServiceImpl implements BookingExpirationService {

    private static final int BATCH_SIZE = 100;

    private final BookingRepository bookingRepository;
    private final BookingTransactionService bookingTransactionService;
    private final FlightClient flightClient;

    @Override
    public void expirePendingBookings() {
        log.debug("Expiry job started");

        List<Booking> expired = bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                BookingStatus.PENDING,
                LocalDateTime.now(),
                PageRequest.of(0, BATCH_SIZE));

        log.info("Found {} expired PENDING bookings", expired.size());

        for (Booking booking : expired) {
            try {
                expireOne(booking);
            } catch (Exception e) {
                log.error("Failed to expire booking {} — skipping: {}",
                        booking.getId(), e.getMessage());
            }
        }
    }

    private void expireOne(Booking booking) {
        // Atomic claim: PENDING → EXPIRING (blocks concurrent cancel)
        boolean claimed = bookingTransactionService.transitionStatus(
                booking.getId(), BookingStatus.PENDING, BookingStatus.EXPIRING);

        if (!claimed) {
            // User cancel already won the race
            log.debug("Booking {} already transitioned — skipping expiry", booking.getId());
            return;
        }

        int count = booking.getPassengerCount();

        try {
            flightClient.releaseSeats(
                    booking.getFlightId(),
                    booking.getFareClassId(),
                    count);
        } catch (Exception e) {
            // Seats could not be released — leave in EXPIRING for manual reconciliation
            log.error("CRITICAL: releaseSeats failed during expiry for booking {} " +
                    "flightId={} fareClassId={} count={} — leaving in EXPIRING state",
                    booking.getId(), booking.getFlightId(), booking.getFareClassId(), count);
            return;
        }

        // EXPIRING → EXPIRED
        boolean completed = bookingTransactionService.transitionStatus(
                booking.getId(), BookingStatus.EXPIRING, BookingStatus.EXPIRED);

        if (!completed) {
            log.error("CRITICAL: Seats released but booking {} could not finalize to EXPIRED", booking.getId());
            throw new IllegalStateException("Booking expiry could not be finalized");
        }

        log.info("Booking {} expired", booking.getId());
    }
}
