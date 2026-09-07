package com.project.flightservice.dto.response;

import com.project.flightservice.enums.Currency;
import com.project.flightservice.enums.FareClassType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareClassResponse {
    private UUID id;
    private FareClassType classType;
    private BigDecimal price;
    private Currency currency;
    private Integer totalSeats;
    private Integer availableSeats;
}
