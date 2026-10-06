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
 *  - PENDING / PAYMENT_PENDING → EXPIRING → EXPIRED (happy path)
 *  - Skip if transition already taken (cancel/confirm won the race)
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

    private Booking aBooking(BookingStatus status) {
        return Booking.builder()
                .id(BOOKING_ID)
                .flightId(FLIGHT_ID)
                .fareClassId(FARE_CLASS_ID)
                .status(status)
                .passengerCount(2)
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    @Test
    @DisplayName("happy path — PENDING → EXPIRING → release → EXPIRED")
    void whenPendingBookingExpired_shouldTransitionToExpired() {
        Booking booking = aBooking(BookingStatus.PENDING);

        when(bookingRepository.findDueBookings(
                any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(booking));
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doNothing().when(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2);
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.EXPIRING, BookingStatus.EXPIRED))
                .thenReturn(true);

        expirationService.expireDueBookings();

        verify(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2);
        verify(bookingTransactionService).transitionStatus(BOOKING_ID, BookingStatus.EXPIRING, BookingStatus.EXPIRED);
    }

    @Test
    @DisplayName("happy path — PAYMENT_PENDING → EXPIRING → release → EXPIRED")
    void whenPaymentPendingBookingExpired_shouldTransitionToExpired() {
        Booking booking = aBooking(BookingStatus.PAYMENT_PENDING);

        when(bookingRepository.findDueBookings(
                any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(booking));
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PAYMENT_PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doNothing().when(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2);
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.EXPIRING, BookingStatus.EXPIRED))
                .thenReturn(true);

        expirationService.expireDueBookings();

        verify(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 2);
        verify(bookingTransactionService).transitionStatus(BOOKING_ID, BookingStatus.EXPIRING, BookingStatus.EXPIRED);
    }

    @Test
    @DisplayName("another transition won the race → skip expiry (no release, no EXPIRING transition)")
    void whenConcurrentTransitionAlreadyWon_shouldSkipWithoutRelease() {
        Booking booking = aBooking(BookingStatus.PENDING);

        when(bookingRepository.findDueBookings(
                any(), any()))
                .thenReturn(List.of(booking));
        // CAS failed
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(false);

        expirationService.expireDueBookings();

        verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        verify(bookingTransactionService, never())
                .transitionStatus(eq(BOOKING_ID), eq(BookingStatus.EXPIRING), eq(BookingStatus.EXPIRED));
    }

    @Test
    @DisplayName("release fails during expiry → leave in EXPIRING state (manual reconciliation), EXPIRED NOT set")
    void whenReleaseFails_shouldLeaveInExpiringState() {
        Booking booking = aBooking(BookingStatus.PAYMENT_PENDING);

        when(bookingRepository.findDueBookings(
                any(), any()))
                .thenReturn(List.of(booking));
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PAYMENT_PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doThrow(new RuntimeException("flight service down"))
                .when(flightClient).releaseSeats(any(), any(), anyInt());

        expirationService.expireDueBookings();

        // Must NOT transition to EXPIRED — leave in EXPIRING for manual reconciliation
        verify(bookingTransactionService, never())
                .transitionStatus(eq(BOOKING_ID), eq(BookingStatus.EXPIRING), eq(BookingStatus.EXPIRED));
    }

    @Test
    @DisplayName("error in one booking → other bookings in same batch still processed")
    void whenOneBookingFails_shouldContinueProcessingOthers() {
        UUID bookingId2 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Booking failing = aBooking(BookingStatus.PENDING);
        Booking other = Booking.builder()
                .id(bookingId2)
                .flightId(FLIGHT_ID)
                .fareClassId(FARE_CLASS_ID)
                .status(BookingStatus.PAYMENT_PENDING)
                .passengerCount(1)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(bookingRepository.findDueBookings(
                any(), any()))
                .thenReturn(List.of(failing, other));

        // First booking: transition OK, but release throws
        when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doThrow(new RuntimeException("flight down for first"))
                .when(flightClient).releaseSeats(eq(FLIGHT_ID), eq(FARE_CLASS_ID), eq(2));

        // Second booking: happy path
        when(bookingTransactionService.transitionStatus(bookingId2, BookingStatus.PAYMENT_PENDING, BookingStatus.EXPIRING))
                .thenReturn(true);
        doNothing().when(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 1);
        when(bookingTransactionService.transitionStatus(bookingId2, BookingStatus.EXPIRING, BookingStatus.EXPIRED))
                .thenReturn(true);

        expirationService.expireDueBookings();

        // Second booking was processed successfully despite first failing
        verify(bookingTransactionService)
                .transitionStatus(bookingId2, BookingStatus.EXPIRING, BookingStatus.EXPIRED);
    }

    @Test
    @DisplayName("no expired bookings → no releases, no transitions")
    void whenNoExpiredBookings_shouldDoNothing() {
        when(bookingRepository.findDueBookings(
                any(), any()))
                .thenReturn(List.of());

        expirationService.expireDueBookings();

        verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        verify(bookingTransactionService, never()).transitionStatus(any(), any(), any());
    }
}
