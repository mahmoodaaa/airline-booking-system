package com.project.bookingservice.service.impl;

import com.project.bookingservice.client.FlightClient;
import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.repository.BookingRepository;
import com.project.bookingservice.service.BookingTransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingExpirationServiceImpl.
 *
 * Verifies the scheduler's expiry logic:
 *  - PENDING → EXPIRING → EXPIRED (happy path)
 *  - Skip if transition already taken (cancel won the race)
 *  - Leave in EXPIRING if release fails (manual reconciliation)
 *  - Error in one booking does not stop processing others
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingExpirationServiceImpl Unit Tests")
class BookingExpirationServiceImplTest {

    @Mock BookingRepository bookingRepository;
    @Mock BookingTransactionService bookingTransactionService;
    @Mock FlightClient flightClient;

    @InjectMocks BookingExpirationServiceImpl expirationService;

    static final UUID BOOKING_ID    = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID FLIGHT_ID     = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID FARE_CLASS_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private Booking aPendingBooking() {
        return Booking.builder()
                .id(BOOKING_ID)
                .flightId(FLIGHT_ID)
                .fareClassId(FARE_CLASS_ID)
                .status(BookingStatus.PENDING)
                .passengerCount(2)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    @Test
    @DisplayName("happy path — PENDING → EXPIRING → release → EXPIRED")
    void whenPendingBookingExpired_shouldTransitionToExpired() {
        Booking booking = aPendingBooking();

        when(bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                eq(BookingStatus.PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(booking));
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doNothing().when(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2);
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.EXPIRING, BookingStatus.EXPIRED))
                .thenReturn(true);

        expirationService.expirePendingBookings();

        verify(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2);
        verify(bookingTransactionService).transitionStatus(BOOKING_ID, BookingStatus.EXPIRING, BookingStatus.EXPIRED);
    }

    @Test
    @DisplayName("user cancel won the race → skip expiry (no release, no EXPIRING transition)")
    void whenCancelAlreadyWon_shouldSkipWithoutRelease() {
        Booking booking = aPendingBooking();

        when(bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(booking));
        // Cancel already transitioned it
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(false);

        expirationService.expirePendingBookings();

        verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        verify(bookingTransactionService, never())
                .transitionStatus(eq(BOOKING_ID), eq(BookingStatus.EXPIRING), eq(BookingStatus.EXPIRED));
    }

    @Test
    @DisplayName("release fails during expiry → leave in EXPIRING state (manual reconciliation), EXPIRED NOT set")
    void whenReleaseFails_shouldLeaveInExpiringState() {
        Booking booking = aPendingBooking();

        when(bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(booking));
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doThrow(new RuntimeException("flight service down"))
                .when(flightClient).releaseSeats(any(), any(), anyInt());

        expirationService.expirePendingBookings();

        // Must NOT transition to EXPIRED — leave in EXPIRING for manual reconciliation
        verify(bookingTransactionService, never())
                .transitionStatus(eq(BOOKING_ID), eq(BookingStatus.EXPIRING), eq(BookingStatus.EXPIRED));
    }

    @Test
    @DisplayName("error in one booking → other bookings in same batch still processed")
    void whenOneBookingFails_shouldContinueProcessingOthers() {
        UUID bookingId2 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Booking failing = aPendingBooking();
        Booking other = Booking.builder()
                .id(bookingId2)
                .flightId(FLIGHT_ID)
                .fareClassId(FARE_CLASS_ID)
                .status(BookingStatus.PENDING)
                .passengerCount(1)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(failing, other));

        // First booking: transition OK, but release throws
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doThrow(new RuntimeException("flight down for first"))
                .when(flightClient).releaseSeats(eq(FLIGHT_ID), eq(FARE_CLASS_ID), eq(2));

        // Second booking: happy path
        when(bookingTransactionService.transitionStatus(bookingId2, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doNothing().when(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 1);
        when(bookingTransactionService.transitionStatus(bookingId2, BookingStatus.EXPIRING, BookingStatus.EXPIRED))
                .thenReturn(true);

        expirationService.expirePendingBookings();

        // Second booking was processed successfully despite first failing
        verify(bookingTransactionService)
                .transitionStatus(bookingId2, BookingStatus.EXPIRING, BookingStatus.EXPIRED);
    }

    @Test
    @DisplayName("no expired bookings → no releases, no transitions")
    void whenNoExpiredBookings_shouldDoNothing() {
        when(bookingRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                any(), any(), any()))
                .thenReturn(List.of());

        expirationService.expirePendingBookings();

        verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        verify(bookingTransactionService, never()).transitionStatus(any(), any(), any());
    }
}
