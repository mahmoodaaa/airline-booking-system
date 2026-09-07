package com.project.flightservice.dto.request;

import com.project.flightservice.enums.Currency;
import com.project.flightservice.enums.FareClassType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareClassRequest {

    @NotNull
    private FareClassType classType;

    @NotNull
    @Positive
    private BigDecimal price;

    @NotNull
    private Currency currency;

    @NotNull
    @Positive
    private Integer totalSeats;
}
