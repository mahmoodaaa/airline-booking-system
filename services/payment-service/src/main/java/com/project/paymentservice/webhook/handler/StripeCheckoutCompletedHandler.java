package com.project.paymentservice.webhook.handler;

import com.stripe.model.Event;

public interface StripeCheckoutCompletedHandler {

    void handle(Event event);

}
