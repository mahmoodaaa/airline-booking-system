package com.project.paymentservice.service.impl;

import com.project.paymentservice.client.BookingClient;
import com.project.paymentservice.client.exception.BookingConfirmationRejectedException;
import com.project.paymentservice.client.exception.BookingIntegrationAmbiguousException;
import com.project.paymentservice.service.PaymentTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingConfirmationOrchestratorImplTest {

    @Mock
    private BookingClient bookingClient;

    @Mock
    private PaymentTransactionService paymentTransactionService;

    private BookingConfirmationOrchestratorImpl orchestrator;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();


    @BeforeEach
    void setUp() {
        orchestrator = new BookingConfirmationOrchestratorImpl(bookingClient, paymentTransactionService);
    }


    // ============================================================
    // Happy path — remote SUCCESS -> CONFIRMED
    // ============================================================

    @Test
    void shouldMarkBookingConfirmedOnSuccess() {

        // BookingClient returns normally (void, no exception)
        doNothing().when(bookingClient).confirmBooking(bookingId, paymentId);

        orchestrator.confirmBooking(bookingId, paymentId);

        verify(bookingClient).confirmBooking(bookingId, paymentId);
        verify(paymentTransactionService).markBookingConfirmed(paymentId);
        verify(paymentTransactionService, never()).markBookingRejected(any(), any());
        verify(paymentTransactionService, never()).recordBookingConfirmationError(any(), any());
    }


    // ============================================================
    // Definitive rejection — BookingConfirmationRejectedException
    // -> REJECTED (no throw back to caller)
    // ============================================================

    @Test
    void shouldMarkBookingRejectedOnDefinitiveRejection() {

        BookingConfirmationRejectedException rejection =
                new BookingConfirmationRejectedException("Booking has expired", HttpStatus.CONFLICT);

        doThrow(rejection).when(bookingClient).confirmBooking(bookingId, paymentId);

        // Orchestrator must NOT throw — it swallows rejection and persists
        orchestrator.confirmBooking(bookingId, paymentId);

        verify(paymentTransactionService).markBookingRejected(eq(paymentId), anyString());
        verify(paymentTransactionService, never()).markBookingConfirmed(any());
        verify(paymentTransactionService, never()).recordBookingConfirmationError(any(), any());
    }


    // ============================================================
    // Ambiguous outcome — BookingIntegrationAmbiguousException
    // -> stays PENDING (recordBookingConfirmationError)
    // ============================================================

    @Test
    void shouldRecordAmbiguousErrorOnNetworkFailure() {

        BookingIntegrationAmbiguousException ambiguous =
                new BookingIntegrationAmbiguousException("Connection timed out");

        doThrow(ambiguous).when(bookingClient).confirmBooking(bookingId, paymentId);

        // Must NOT throw
        orchestrator.confirmBooking(bookingId, paymentId);

        verify(paymentTransactionService).recordBookingConfirmationError(eq(paymentId), anyString());
        verify(paymentTransactionService, never()).markBookingConfirmed(any());
        verify(paymentTransactionService, never()).markBookingRejected(any(), any());
    }


    // ============================================================
    // Unexpected RuntimeException -> treated as ambiguous (safe default)
    // ============================================================

    @Test
    void shouldTreatUnexpectedExceptionAsAmbiguous() {

        doThrow(new RuntimeException("unexpected NPE"))
                .when(bookingClient).confirmBooking(bookingId, paymentId);

        // Must NOT throw — safe default is PENDING not REJECTED
        orchestrator.confirmBooking(bookingId, paymentId);

        verify(paymentTransactionService).recordBookingConfirmationError(eq(paymentId), anyString());
        verify(paymentTransactionService, never()).markBookingRejected(any(), any());
        verify(paymentTransactionService, never()).markBookingConfirmed(any());
    }


    // ============================================================
    // TX #2 failure after remote SUCCESS
    // -> logs CRITICAL but does NOT throw (stays PENDING)
    // ============================================================

    @Test
    void shouldNotThrowWhenLocalFinalizationFailsAfterRemoteSuccess() {

        doNothing().when(bookingClient).confirmBooking(bookingId, paymentId);

        doThrow(new RuntimeException("DB connection lost"))
                .when(paymentTransactionService).markBookingConfirmed(paymentId);

        // Must NOT rethrow — webhook must still return 200
        orchestrator.confirmBooking(bookingId, paymentId);

        verify(paymentTransactionService).markBookingConfirmed(paymentId);
        // recordBookingConfirmationError is the fallback for this critical case
        verify(paymentTransactionService).recordBookingConfirmationError(eq(paymentId), anyString());
    }


    // ============================================================
    // Null args guard
    // ============================================================

    @Test
    void shouldThrowOnNullBookingId() {
        assertThrows(NullPointerException.class,
                () -> orchestrator.confirmBooking(null, paymentId));
    }

    @Test
    void shouldThrowOnNullPaymentId() {
        assertThrows(NullPointerException.class,
                () -> orchestrator.confirmBooking(bookingId, null));
    }
}
