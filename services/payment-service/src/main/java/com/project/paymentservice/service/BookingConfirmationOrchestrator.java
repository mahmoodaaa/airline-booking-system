package com.project.paymentservice.service;

import java.util.UUID;

public interface BookingConfirmationOrchestrator {

    /**
     * @param bookingId the booking to confirm
     * @param paymentId the payment backing the confirmation
     */
    void confirmBooking(UUID bookingId, UUID paymentId);
}