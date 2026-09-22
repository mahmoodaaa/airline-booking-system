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
public class BookingConfirmationOrchestratorImpl
        implements BookingConfirmationOrchestrator {

    private final BookingClient bookingClient;

    private final PaymentTransactionService paymentTransactionService;

    @Override
    public void confirmBooking(UUID bookingId, UUID paymentId) {

        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");


        // ========================================================
        // IMPORTANT
        //
        // There is intentionally NO @Transactional here.
        //
        // Financial truth was already committed before reaching
        // this orchestrator.
        // ========================================================


        try {

            // ====================================================
            // NETWORK CALL
            //
            // Completely outside Payment DB transaction.
            // ====================================================

            bookingClient.confirmBooking(bookingId, paymentId);


        } catch (BookingConfirmationRejectedException e) {

            // ====================================================
            // DEFINITIVE business rejection
            //
            // Examples:
            // EXPIRED / incompatible state / other payment won.
            //
            // Money remains SUCCEEDED.
            // Phase 11 will compensate with technical refund.
            // ====================================================

            log.warn("Booking definitively rejected payment confirmation. " + "bookingId={} paymentId={} status={} reason={}",
                    bookingId,
                    paymentId,
                    e.getStatus(),
                    e.getMessage()
            );


            persistRejectedWithoutThrowing(paymentId, e.getMessage());
            return;


        } catch (BookingIntegrationAmbiguousException e) {

            // ====================================================
            // UNKNOWN REMOTE OUTCOME
            //
            // Timeout / 5xx / connection loss / auth problem.
            //
            // NEVER mark REJECTED.
            // NEVER refund.
            //
            // Keep:
            //
            // Payment = SUCCEEDED
            // BookingConfirmationStatus = PENDING
            //
            // Reconciliation retries later.
            // ====================================================

            log.error(
                    "Booking confirmation outcome is ambiguous. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId,
                    e
            );


            persistAmbiguousErrorWithoutThrowing(paymentId, e.getMessage());


            return;


        } catch (RuntimeException e) {

            // ====================================================
            // SAFE DEFAULT
            //
            // Unexpected application/integration error is NOT proof
            // of a definitive Booking rejection.
            //
            // Therefore treat conservatively as ambiguous.
            // ====================================================

            log.error(
                    "CRITICAL unexpected error during Booking confirmation. " +
                            "Keeping confirmation PENDING. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId,
                    e
            );


            persistAmbiguousErrorWithoutThrowing(paymentId, "Unexpected Booking confirmation error: " + safeMessage(e));


            return;
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

            // ====================================================
            // Important distributed case:
            //
            // Booking may already be CONFIRMED,
            // but our local TX #2 failed.
            //
            // DO NOT fail the financial webhook.
            // DO NOT undo Payment SUCCEEDED.
            //
            // Leave local confirmation PENDING if the transaction
            // rolled back. Reconciliation will retry the same
            // idempotent Booking confirmation later.
            // ====================================================

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
        }
    }





    private void persistRejectedWithoutThrowing(UUID paymentId, String reason) {

        try {

            paymentTransactionService.markBookingRejected(paymentId, reason);


        } catch (RuntimeException persistenceError) {

            /*
             * Financial truth is already durable.
             *
             * Never propagate this back into the Stripe webhook path.
             * Reconciliation can retry Booking confirmation later
             * while local status remains PENDING.
             */

            log.error(
                    "CRITICAL failed to persist Booking REJECTED state. " +
                            "paymentId={}",
                    paymentId,
                    persistenceError
            );
        }
    }


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