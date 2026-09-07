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
     * Runs every 60 seconds after previous execution completes.
     * fixedDelay ensures no overlap if expiry takes longer than 60s.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        log.debug("BookingCleanupJob triggered");
        bookingExpirationService.expirePendingBookings();
    }
}
