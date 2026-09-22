package com.project.paymentservice.webhook.handler;

import com.stripe.model.Event;

public interface StripeCheckoutExpiredHandler {

    void handle(Event event);
}