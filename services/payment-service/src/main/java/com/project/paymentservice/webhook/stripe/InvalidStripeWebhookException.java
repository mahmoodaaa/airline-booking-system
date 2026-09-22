package com.project.paymentservice.webhook.stripe;

import com.project.common.exception.ApiBaseException;
import org.springframework.http.HttpStatus;

public class InvalidStripeWebhookException extends ApiBaseException {

    public InvalidStripeWebhookException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }

    public InvalidStripeWebhookException(
            String message,
            Throwable cause
    ) {
        super(message, HttpStatus.BAD_REQUEST);
        initCause(cause);
    }
}