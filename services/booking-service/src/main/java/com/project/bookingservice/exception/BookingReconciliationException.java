package com.project.bookingservice.exception;

public class BookingReconciliationException extends RuntimeException {

    public BookingReconciliationException(String message) {
        super(message);
    }

    public BookingReconciliationException(String message, Throwable cause) {
        super(message, cause);
    }
}