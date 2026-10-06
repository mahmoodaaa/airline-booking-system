package com.project.bookingservice.scheduler;

import com.project.bookingservice.service.BookingExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCleanupJob {

    private final BookingExpirationService bookingExpirationService;

    /**
     * Runs after the previous execution completes.
     *
     * One sweep handles both:
     *   PENDING
     *   PAYMENT_PENDING
     *
     * Both use the same effective expiresAt deadline.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {

        log.debug("BookingCleanupJob triggered");

        bookingExpirationService.expireDueBookings();
    }
}