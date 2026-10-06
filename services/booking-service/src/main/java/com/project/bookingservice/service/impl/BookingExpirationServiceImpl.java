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
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingExpirationServiceImpl implements BookingExpirationService {

    private static final int BATCH_SIZE = 200;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final BookingRepository bookingRepository;
    private final BookingTransactionService bookingTransactionService;
    private final FlightClient flightClient;

    @Override
    public void expireDueBookings() {

        log.debug("Booking expiry sweep started");

        int totalCandidates = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {

            // IMPORTANT:
            // calculate current time on every batch, never as a singleton field.
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            List<Booking> dueBookings = bookingRepository.findDueBookings(
                            now,
                            PageRequest.of(0, BATCH_SIZE));

            if (dueBookings.isEmpty()) {
                break;
            }

            totalCandidates += dueBookings.size();

            for (Booking booking : dueBookings) {
                try {
                    expireOne(booking);
                } catch (Exception e) {
                    log.error(
                            "Failed to expire booking {} from status {} — skipping",
                            booking.getId(),
                            booking.getStatus(),
                            e
                    );
                }
            }

            // Everything that was due at this point fitted in this batch.
            if (dueBookings.size() < BATCH_SIZE) {
                break;
            }
        }

        log.debug("Booking expiry sweep completed — candidatesProcessed={}", totalCandidates);
    }

    private void expireOne(Booking booking) {

        BookingStatus fromStatus = booking.getStatus();

        if (fromStatus != BookingStatus.PENDING && fromStatus != BookingStatus.PAYMENT_PENDING) {

            log.debug("Booking {} is no longer expiry-eligible. status={}", booking.getId(), fromStatus);
            return;
        }

        // Atomic claim.
        // If confirmation/cancellation/another worker already won,
        // this returns false and no external release call occurs.
        boolean claimed = bookingTransactionService.transitionStatus(booking.getId(), fromStatus, BookingStatus.EXPIRING);

        if (!claimed) {
            log.debug("Booking {} was concurrently transitioned — skipping expiry",booking.getId());
            return;
        }

        try {

           flightClient.releaseSeats(booking.getFlightId(), booking.getFareClassId(), booking.getPassengerCount());

        } catch (Exception e) {

            /*
             * Outcome may be ambiguous.
             *
             * Do not pretend the release failed or succeeded.
             * Leave Booking in EXPIRING for reconciliation.
             */
            log.error(
                    "CRITICAL: releaseSeats outcome unresolved during expiry. " +
                            "bookingId={} flightId={} fareClassId={} count={}. " +
                            "Leaving Booking in EXPIRING.",
                    booking.getId(),
                    booking.getFlightId(),
                    booking.getFareClassId(),
                    booking.getPassengerCount(),
                    e
            );

            return;
        }

        boolean completed = bookingTransactionService.transitionStatus(
                booking.getId(),
                BookingStatus.EXPIRING,
                BookingStatus.EXPIRED
        );

        if (!completed) {

            log.error(
                    "CRITICAL: Seats released but Booking {} could not transition " +
                            "EXPIRING -> EXPIRED",
                    booking.getId()
            );

            throw new IllegalStateException("Booking expiry could not be finalized");
        }

        log.info(
                "Booking {} expired successfully from {}",
                booking.getId(),
                fromStatus
        );
    }
}