package com.project.paymentservice.client.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BookingConfirmationRejectedException
        extends RuntimeException {

    private final HttpStatus status;

    public BookingConfirmationRejectedException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }
}