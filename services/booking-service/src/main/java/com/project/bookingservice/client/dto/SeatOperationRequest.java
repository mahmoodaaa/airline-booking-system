package com.project.bookingservice.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wraps the count sent to flight-service reserve/release endpoints.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatOperationRequest {
    private int count;
}
