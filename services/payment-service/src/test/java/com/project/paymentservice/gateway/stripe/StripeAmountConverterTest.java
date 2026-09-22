package com.project.paymentservice.gateway.stripe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class StripeAmountConverterTest {

    private StripeAmountConverter converter;


    @BeforeEach
    void setUp() {
        converter = new StripeAmountConverter();
    }


    @Test
    void shouldConvertUsdToMinorUnits() {

        long result =
                converter.toMinorUnits(
                        new BigDecimal("150.25"),
                        "USD"
                );

        assertEquals(
                15025L,
                result
        );
    }


    @Test
    void shouldConvertEurToMinorUnits() {

        long result =
                converter.toMinorUnits(
                        new BigDecimal("99.50"),
                        "EUR"
                );

        assertEquals(
                9950L,
                result
        );
    }


    @Test
    void shouldConvertAedToMinorUnits() {

        long result =
                converter.toMinorUnits(
                        new BigDecimal("120.75"),
                        "AED"
                );

        assertEquals(
                12075L,
                result
        );
    }


    @Test
    void shouldConvertZeroDecimalCurrency() {

        long result =
                converter.toMinorUnits(
                        new BigDecimal("500"),
                        "JPY"
                );

        assertEquals(
                500L,
                result
        );
    }


    @Test
    void shouldRejectTooManyDecimalPlacesForUsd() {

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.toMinorUnits(
                        new BigDecimal("10.123"),
                        "USD"
                )
        );
    }


    @Test
    void shouldRejectDecimalAmountForJpy() {

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.toMinorUnits(
                        new BigDecimal("500.50"),
                        "JPY"
                )
        );
    }


    @Test
    void shouldRejectUnsupportedCurrency() {

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.toMinorUnits(
                        new BigDecimal("100"),
                        "XYZ"
                )
        );
    }


    @Test
    void shouldRejectZeroAmount() {

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.toMinorUnits(
                        BigDecimal.ZERO,
                        "USD"
                )
        );
    }


    @Test
    void shouldRejectNegativeAmount() {

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.toMinorUnits(
                        new BigDecimal("-10"),
                        "USD"
                )
        );
    }


    @Test
    void shouldNormalizeCurrencyForStripe() {

        assertEquals(
                "usd",
                converter.normalizeCurrency(
                        " USD "
                )
        );
    }
}