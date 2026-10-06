package com.project.paymentservice.service.impl;

import com.project.paymentservice.client.BookingClient;
import com.project.paymentservice.client.exception.BookingConfirmationRejectedException;
import com.project.paymentservice.client.exception.BookingIntegrationAmbiguousException;
import com.project.paymentservice.service.BookingConfirmationOrchestrator;
import com.project.paymentservice.service.PaymentTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingConfirmationOrchestratorImpl implements BookingConfirmationOrchestrator {

    private final BookingClient bookingClient;

    private final PaymentTransactionService paymentTransactionService;

    private final BookingRejectedRefundService bookingRejectedRefundService;


    @Override
    public void confirmBooking(UUID bookingId, UUID paymentId) {

        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");


        try {

            // ====================================================
            // NETWORK CALL
            //
            // Completely outside Payment DB transaction.
            // ====================================================

            bookingClient.confirmBooking(bookingId, paymentId);


        } catch (BookingConfirmationRejectedException e) {

            log.error(
                    "CRITICAL: Payment succeeded but Booking definitively rejected confirmation. " +
                    "bookingId={} paymentId={} status={} reason={}. " +
                    "Triggering minimal booking-rejection refund.",
                    bookingId,
                    paymentId,
                    e.getStatus(),
                    e.getMessage()
            );

            paymentTransactionService.markBookingRejected(
                    paymentId,
                    e.getMessage()
            );

            bookingRejectedRefundService.refund(paymentId);

            return;


        } catch (BookingIntegrationAmbiguousException e) {

            log.error(
                    "Booking confirmation outcome is ambiguous. " +
                    "bookingId={} paymentId={}",
                    bookingId,
                    paymentId,
                    e
            );

            persistAmbiguousErrorWithoutThrowing(paymentId, e.getMessage());

            throw e;
        } catch (RuntimeException e) {

            log.error("CRITICAL unexpected error during Booking confirmation. " +
                            "Keeping confirmation PENDING. " +
                            "bookingId={} paymentId={}", bookingId, paymentId, e);

            persistAmbiguousErrorWithoutThrowing(paymentId, "Unexpected Booking confirmation error: " + safeMessage(e));

            throw e;
        }


        // ========================================================
        // Remote Booking confirmation returned SUCCESS
        //
        // Now persist local synchronization truth in a NEW,
        // short-lived DB transaction.
        // ========================================================

        try {
            paymentTransactionService.markBookingConfirmed(paymentId);

            log.info(
                    "Booking confirmation completed successfully. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId
            );

        } catch (RuntimeException e) {

            log.error(
                    "CRITICAL Booking confirmed remotely but local " +
                            "confirmation persistence failed. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId,
                    e
            );
            persistAmbiguousErrorWithoutThrowing(
                    paymentId,
                    "Booking confirmation succeeded remotely, "
                            + "but local finalization failed: "
                            + safeMessage(e)
            );
            throw e;
        }
    }

    // ============================================================
    private void persistAmbiguousErrorWithoutThrowing(UUID paymentId, String error) {

        try {

            paymentTransactionService.recordBookingConfirmationError(paymentId, error);


        } catch (RuntimeException persistenceError) {

            /*
             * Best-effort operational metadata only.
             *
             * Payment remains financially SUCCEEDED.
             * The PENDING state itself is enough for reconciliation
             * to discover this Payment later.
             */

            log.error(
                    "CRITICAL failed to persist Booking confirmation " + "error metadata. paymentId={}", paymentId, persistenceError);
        }
    }

    private String safeMessage(Throwable throwable) {

        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {

            return throwable == null
                    ? "unknown error"
                    : throwable.getClass().getSimpleName();}


        return throwable.getMessage();
    }
}