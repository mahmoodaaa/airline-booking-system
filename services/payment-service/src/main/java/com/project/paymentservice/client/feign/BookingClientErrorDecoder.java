package com.project.paymentservice.client.feign;

import com.project.paymentservice.client.exception.BookingConfirmationRejectedException;
import com.project.paymentservice.client.exception.BookingIntegrationAmbiguousException;
import com.project.paymentservice.client.exception.BookingIntegrationDefinitiveException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class BookingClientErrorDecoder implements ErrorDecoder {

    private final ErrorDecoder defaultDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {

        int rawStatus = response.status();

        HttpStatus status = HttpStatus.resolve(rawStatus);


        if (status == null) {
            return defaultDecoder.decode(methodKey, response);
        }


        // ========================================================
        // Booking confirmation semantics
        // ========================================================

        if (isConfirmBookingCall(methodKey)) {

            return decodeConfirmationFailure(
                    status,
                    methodKey,
                    response
            );
        }


        // ========================================================
        // Other Booking integration calls
        //
        // Example:
        // getPaymentContext(...)
        // ========================================================

        if (status == HttpStatus.NOT_FOUND
                || status == HttpStatus.CONFLICT
                || status == HttpStatus.GONE
                || status == HttpStatus.UNPROCESSABLE_ENTITY) {

            log.warn("Booking integration definitively rejected request. " + "status={} method={}", status.value(), methodKey);

            return new BookingIntegrationDefinitiveException(
                    "Booking integration request was definitively rejected " + "(status=" + status.value() + ")");
        }


        // ========================================================
        // Internal authentication/configuration problem
        //
        // This is NOT proof that the Booking business operation
        // itself was rejected.
        // ========================================================

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {

            log.error(
                    "CRITICAL: Booking-service rejected internal SERVICE JWT. " +
                            "status={} method={}",
                    status.value(),
                    methodKey
            );

            return new BookingIntegrationAmbiguousException(
                    "Booking-service internal authentication failed"
            );
        }


        // ========================================================
        // Downstream server failure
        // ========================================================

        if (status.is5xxServerError()) {

            log.warn(
                    "Booking-service returned server error. " +
                            "Outcome is ambiguous. status={} method={}",
                    status.value(),
                    methodKey
            );

            return new BookingIntegrationAmbiguousException(
                    "Booking-service returned server error: "
                            + status.value()
            );
        }


        /*
         * Anything we have not explicitly classified:
         *
         * Do not invent definitive business meaning.
         */
        return defaultDecoder.decode(methodKey, response);
    }


    private Exception decodeConfirmationFailure(
            HttpStatus status,
            String methodKey,
            Response response
    ) {

        // ========================================================
        // DEFINITIVE confirmation rejection
        // ========================================================

        if (status == HttpStatus.NOT_FOUND
                || status == HttpStatus.CONFLICT
                || status == HttpStatus.GONE
                || status == HttpStatus.UNPROCESSABLE_ENTITY) {

            log.warn(
                    "Booking definitively rejected payment confirmation. " +
                            "status={} method={}",
                    status.value(),
                    methodKey
            );

            return new BookingConfirmationRejectedException(
                    confirmationMessage(status),
                    status
            );
        }


        // ========================================================
        // Infrastructure/auth failures are NOT refund evidence
        // ========================================================

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {

            log.error(
                    "CRITICAL: Booking confirmation could not be authenticated. " +
                            "status={} method={}",
                    status.value(),
                    methodKey
            );

            return new BookingIntegrationAmbiguousException(
                    "Booking confirmation authentication failed"
            );
        }


        // ========================================================
        // 5xx -> ambiguous
        // ========================================================

        if (status.is5xxServerError()) {

            return new BookingIntegrationAmbiguousException(
                    "Booking confirmation outcome is ambiguous because " +
                            "booking-service returned "
                            + status.value()
            );
        }


        return defaultDecoder.decode(methodKey, response);
    }


    private boolean isConfirmBookingCall(String methodKey) {

        return methodKey != null && methodKey.contains("#confirmBooking(");
    }


    private String confirmationMessage(HttpStatus status) {

        return switch (status) {

            case NOT_FOUND ->
                    "Booking no longer exists and cannot be confirmed";

            case CONFLICT ->
                    "Booking rejected payment confirmation because of a state conflict";

            case GONE ->
                    "Booking has expired and cannot be confirmed";

            case UNPROCESSABLE_ENTITY ->
                    "Booking is not in a confirmable state";

            default ->
                    "Booking rejected payment confirmation";
        };
    }
}