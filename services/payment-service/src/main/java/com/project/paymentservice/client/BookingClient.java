package com.project.paymentservice.client;

import com.project.common.response.ApiResponse;
import com.project.paymentservice.client.dto.BookingPaymentContext;
import com.project.paymentservice.client.exception.BookingConfirmationRejectedException;
import com.project.paymentservice.client.exception.BookingIntegrationAmbiguousException;
import com.project.paymentservice.client.exception.BookingIntegrationDefinitiveException;
import com.project.paymentservice.client.feign.BookingFeignClient;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingClient {

    private final BookingFeignClient feignClient;


    public BookingPaymentContext getPaymentContext(UUID bookingId) {

        try {
            ApiResponse<BookingPaymentContext> response = feignClient.getPaymentContext(bookingId);

            if (response == null || response.getData() == null) {

                throw new BookingIntegrationAmbiguousException(
                        "Booking service returned an empty payment context");
            }
            return response.getData();


        } catch (BookingIntegrationDefinitiveException | BookingIntegrationAmbiguousException e) {

            throw e;


        } catch (RetryableException e) {

            throw new BookingIntegrationAmbiguousException("Booking service communication outcome is uncertain", e);


        } catch (FeignException e) {

            throw new BookingIntegrationAmbiguousException("Unexpected Booking service communication failure", e);
        }
    }


    public void confirmBooking(UUID bookingId, UUID paymentId) {

        log.info(
                "Confirming booking. bookingId={} paymentId={}",
                bookingId,
                paymentId
        );


        try {

            ApiResponse<Void> response =
                    feignClient.confirmBooking(
                            bookingId,
                            paymentId
                    );


            /*
             * A null HTTP/body mapping result is not proof
             * that Booking rejected the command.
             *
             * Remote outcome is therefore ambiguous.
             */
            if (response == null) {

                throw new BookingIntegrationAmbiguousException(
                        "Booking service returned a null response during confirmation"
                );
            }


            log.info(
                    "Booking confirmation call succeeded. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId
            );


        } catch (BookingConfirmationRejectedException e) {

            /*
             * Explicit business rejection.
             *
             * Safe for the orchestrator to persist:
             *
             * PENDING -> REJECTED
             */
            throw e;


        } catch (BookingIntegrationAmbiguousException e) {
            throw e;


        } catch (BookingIntegrationDefinitiveException e) {

            /*
             * Defensive fallback.
             *
             * confirmBooking() should receive the more specific
             * BookingConfirmationRejectedException from the decoder.
             *
             * A generic definitive exception here means our exception
             * classification is not what we expected.
             *
             * DO NOT refund based on that uncertainty.
             */
            log.error(
                    "CRITICAL: Unexpected generic definitive Booking exception " +
                            "during payment confirmation. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId,
                    e
            );


            throw new BookingIntegrationAmbiguousException(
                    "Booking confirmation produced an unclassified integration result", e);


        } catch (RetryableException e) {

            log.error(
                    "Booking confirmation network outcome is uncertain. " +
                            "bookingId={} paymentId={}",
                    bookingId,
                    paymentId,
                    e
            );


            throw new BookingIntegrationAmbiguousException(
                    "Booking confirmation communication outcome is uncertain", e);


        } catch (FeignException e) {

            log.error(
                    "Unexpected Feign error while confirming Booking. " +
                            "bookingId={} paymentId={} status={}",
                    bookingId,
                    paymentId,
                    e.status(),
                    e
            );


            throw new BookingIntegrationAmbiguousException("Unexpected Booking confirmation communication failure", e
            );
        }
    }
}