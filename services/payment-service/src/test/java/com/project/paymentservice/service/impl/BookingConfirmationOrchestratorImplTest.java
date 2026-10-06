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

    @Mock
    private BookingRejectedRefundService bookingRejectedRefundService;

    private BookingConfirmationOrchestratorImpl orchestrator;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();


    @BeforeEach
    void setUp() {
        orchestrator = new BookingConfirmationOrchestratorImpl(
                bookingClient,
                paymentTransactionService,
                bookingRejectedRefundService
        );
    }


    // ============================================================
    // Happy path — remote SUCCESS -> CONFIRMED
    // ============================================================

    @Test
    void shouldMarkBookingConfirmedOnSuccess() {

        doNothing().when(bookingClient).confirmBooking(bookingId, paymentId);

        orchestrator.confirmBooking(bookingId, paymentId);

        verify(bookingClient).confirmBooking(bookingId, paymentId);
        verify(paymentTransactionService).markBookingConfirmed(paymentId);
        verify(paymentTransactionService, never()).markBookingRejected(any(), any());
        verify(paymentTransactionService, never()).recordBookingConfirmationError(any(), any());
    }


    // ============================================================
    // DEFINITIVE REJECTION -> local REJECTED, no technical refund
    // ============================================================

    @Test
    void shouldMarkBookingRejectedOnDefinitiveRejection() {

        doThrow(new BookingConfirmationRejectedException(
                "Booking has already expired",
                HttpStatus.GONE
        )).when(bookingClient).confirmBooking(bookingId, paymentId);

        orchestrator.confirmBooking(bookingId, paymentId);

        verify(paymentTransactionService).markBookingRejected(eq(paymentId), anyString());
        verify(bookingRejectedRefundService).refund(paymentId);
        verify(paymentTransactionService, never()).markBookingConfirmed(any());
        verify(paymentTransactionService, never()).recordBookingConfirmationError(any(), any());
    }


    // ============================================================
    // AMBIGUOUS response -> throws exception for webhook retry
    // ============================================================

    @Test
    void shouldThrowAndPersistErrorOnAmbiguousIntegrationException() {

        doThrow(new BookingIntegrationAmbiguousException(
                "503 Service Unavailable"
        )).when(bookingClient).confirmBooking(bookingId, paymentId);

        assertThrows(BookingIntegrationAmbiguousException.class, () -> {
            orchestrator.confirmBooking(bookingId, paymentId);
        });

        verify(paymentTransactionService).recordBookingConfirmationError(eq(paymentId), anyString());
        verify(paymentTransactionService, never()).markBookingConfirmed(any());
        verify(paymentTransactionService, never()).markBookingRejected(any(), any());
    }


    // ============================================================
    // Unexpected RuntimeException -> treated as ambiguous, throws exception
    // ============================================================

    @Test
    void shouldThrowAndTreatUnexpectedExceptionAsAmbiguous() {

        doThrow(new RuntimeException("unexpected NPE"))
                .when(bookingClient).confirmBooking(bookingId, paymentId);

        assertThrows(RuntimeException.class, () -> {
            orchestrator.confirmBooking(bookingId, paymentId);
        });

        verify(paymentTransactionService).recordBookingConfirmationError(eq(paymentId), anyString());
        verify(paymentTransactionService, never()).markBookingRejected(any(), any());
        verify(paymentTransactionService, never()).markBookingConfirmed(any());
    }


    // ============================================================
    // TX #2 failure after remote SUCCESS -> throws exception for retry
    // ============================================================

    @Test
    void shouldThrowWhenLocalFinalizationFailsAfterRemoteSuccess() {

        doNothing().when(bookingClient).confirmBooking(bookingId, paymentId);

        doThrow(new RuntimeException("DB connection lost"))
                .when(paymentTransactionService).markBookingConfirmed(paymentId);

        assertThrows(RuntimeException.class, () -> {
            orchestrator.confirmBooking(bookingId, paymentId);
        });

        verify(paymentTransactionService).markBookingConfirmed(paymentId);
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
