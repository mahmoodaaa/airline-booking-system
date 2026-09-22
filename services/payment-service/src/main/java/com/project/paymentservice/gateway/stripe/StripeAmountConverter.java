package com.project.paymentservice.gateway.stripe;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

@Component
public class StripeAmountConverter {

    /*
     * Currency -> number of decimal places used when converting
     * our BigDecimal amount into Stripe's integer minor units.
     *
     * We intentionally maintain an explicit allow-list rather than
     * accepting every ISO currency automatically.
     *
     * This gives us controlled provider support.
     */
    private static final Map<String, Integer> SUPPORTED_CURRENCIES =
            Map.of(
                    "USD", 2,
                    "EUR", 2,
                    "GBP", 2,
                    "AED", 2,

                    // Zero-decimal example.
                    "JPY", 0
            );


    /**
     * Converts a major-unit monetary amount to the integer amount
     * expected by Stripe.
     *
     * Examples:
     *
     * 150.25 USD -> 15025
     * 99.50 EUR  -> 9950
     * 120.75 AED -> 12075
     * 500 JPY    -> 500
     */
    public long toMinorUnits(BigDecimal amount, String currency) {

        if (amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }

        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);

        Integer fractionDigits = SUPPORTED_CURRENCIES.get(normalizedCurrency);


        if (fractionDigits == null) {
            throw new IllegalArgumentException("Unsupported Stripe currency: " + normalizedCurrency);
        }


        try {

            /*
             * Example:
             *
             * 150.25 USD
             *
             * fractionDigits = 2
             *
             * movePointRight(2)
             *
             * 15025
             */
            BigDecimal minorUnits = amount.movePointRight(fractionDigits);


            /*
             * IMPORTANT:
             *
             * longValueExact() prevents silent rounding.
             *
             * Example:
             *
             * USD 10.123
             *
             * movePointRight(2)
             * -> 1012.3
             *
             * longValueExact()
             * -> ArithmeticException
             *
             * Good.
             *
             * We do NOT silently charge 10.12 or 10.13.
             */
            return minorUnits.longValueExact();

        } catch (ArithmeticException ex) {

            throw new IllegalArgumentException("Invalid amount precision for currency: " + normalizedCurrency, ex);
        }
    }


    /**
     * Returns Stripe-style normalized currency code.
     *
     * Example:
     *
     * "usd" -> "usd"
     * " USD " -> "usd"
     *
     * Stripe typically expects lowercase currency codes.
     */
    public String normalizeCurrency(String currency) {

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }

        String normalized = currency.trim().toUpperCase(Locale.ROOT);

        if (!SUPPORTED_CURRENCIES.containsKey(normalized)) {

            throw new IllegalArgumentException("Unsupported Stripe currency: " + normalized);
        }


        return normalized.toLowerCase(Locale.ROOT);
    }
}