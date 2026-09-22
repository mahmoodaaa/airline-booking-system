package com.project.paymentservice.gateway.stripe;

import com.project.paymentservice.config.StripeProperties;
import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import com.project.paymentservice.gateway.PaymentGateway;
import com.project.paymentservice.gateway.exception.GatewayAmbiguousException;
import com.project.paymentservice.gateway.exception.GatewayDefinitiveException;
import com.project.paymentservice.gateway.model.CheckoutRequest;
import com.project.paymentservice.gateway.model.CheckoutResult;


import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.checkout.Session;
import com.stripe.exception.StripeException;


import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;




import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {

    private static final long MIN_CHECKOUT_EXPIRY_MINUTES = 30;
    private static final long MAX_CHECKOUT_EXPIRY_MINUTES = 24 * 60;

    private final StripeCheckoutClient stripeCheckoutClient;
    private final StripeProperties stripeProperties;
    private final StripeAmountConverter amountConverter;


    @Override
    public PaymentProvider provider() {
        return PaymentProvider.STRIPE;
    }


    @Override
    public CheckoutResult createCheckout(CheckoutRequest request) {

        // ========================================================
        // 1. Validate local/provider-neutral input
        //
        // No Stripe request has been sent yet.
        // Any failure here is DEFINITIVE: no provider side effect.
        // ========================================================

        validateRequest(request);
        validateConfiguration();


        // ========================================================
        // 2. Convert our money representation to Stripe format
        //
        // Example:
        // 120.75 AED -> 12075
        // "AED"      -> "aed"
        // ========================================================

        final long unitAmount;
        final String stripeCurrency;

        try {
            unitAmount = amountConverter.toMinorUnits(
                            request.amount(),
                            request.currency());

            stripeCurrency = amountConverter.normalizeCurrency(request.currency());

        } catch (IllegalArgumentException ex) {

            /*
             * Stripe was NOT called.
             *
             * Therefore the outcome is definitely "not created",
             * never UNKNOWN.
             */
            throw new GatewayDefinitiveException(
                    "Invalid amount or currency for Stripe checkout",
                    ex
            );
        }


        // ========================================================
        // 3. Build Stripe Checkout Session parameters
        //
        // Still NO network call.
        // ========================================================

        SessionCreateParams params = buildSessionParams(request, unitAmount, stripeCurrency);


        // ========================================================
        // 4. Provider-side idempotency
        //
        // This key was generated and persisted while the Attempt
        // was INITIALIZING, BEFORE reaching this gateway.
        //
        // Same Attempt -> same Stripe idempotency key.
        // ========================================================

        RequestOptions requestOptions = RequestOptions.builder()
                .setIdempotencyKey(request.providerIdempotencyKey())
                        .build();


        // ========================================================
        // 5. Stripe network call
        //
        // IMPORTANT:
        // PaymentServiceImpl has no open DB transaction here.
        // ========================================================

        final Session session;

        try {

            session = stripeCheckoutClient.createSession(
                    params,
                    requestOptions
            );

        } catch (CardException |
                 InvalidRequestException |
                 AuthenticationException |
                 IdempotencyException ex) {

            throw new GatewayDefinitiveException(
                    "Stripe definitively rejected checkout creation",
                    ex
            );
        } catch (ApiConnectionException | ApiException ex) {

            throw ambiguousFailure(request, ex);

        } catch (StripeException ex) {

            throw ambiguousFailure(request, ex);
        }

        return mapCheckoutResult(session, request);

    }


    // ============================================================
    // Stripe Session parameters
    // ============================================================

    private SessionCreateParams buildSessionParams(
            CheckoutRequest request,
            long unitAmount,
            String stripeCurrency
    ) {

        // --------------------------------------------------------
        // Product shown inside Stripe-hosted Checkout
        // --------------------------------------------------------

        SessionCreateParams.LineItem.PriceData.ProductData
                productData = SessionCreateParams.LineItem.PriceData.ProductData
                        .builder()
                        .setName("Airline Booking " + request.bookingId())
                        .build();


        // --------------------------------------------------------
        // Inline Stripe Price
        //
        // We intentionally do not create permanent Stripe Product
        // or Price objects for each airline booking.
        // --------------------------------------------------------

        SessionCreateParams.LineItem.PriceData
                priceData = SessionCreateParams.LineItem.PriceData
                        .builder()
                        .setCurrency(stripeCurrency)
                        .setUnitAmount(unitAmount)
                        .setProductData(productData)
                        .build();


        // --------------------------------------------------------
        // One logical booking payment
        // quantity = 1
        // --------------------------------------------------------

        SessionCreateParams.LineItem lineItem =
                SessionCreateParams.LineItem
                        .builder()
                        .setQuantity(1L)
                        .setPriceData(priceData)
                        .build();


        // --------------------------------------------------------
        // Metadata copied to the PaymentIntent too.
        //
        // Helpful later for:
        //
        // webhook correlation
        // reconciliation
        // operational debugging
        // --------------------------------------------------------

        SessionCreateParams.PaymentIntentData paymentIntentData =
                SessionCreateParams.PaymentIntentData
                        .builder()
                        .putMetadata("payment_id", request.paymentId().toString())
                        .putMetadata("attempt_id", request.attemptId().toString())
                        .putMetadata("booking_id", request.bookingId().toString())
                        .build();


        long expiresAtEpochSeconds = Instant.now()
                        .plus(stripeProperties.getCheckoutExpiryMinutes(), ChronoUnit.MINUTES)
                        .getEpochSecond();


        return SessionCreateParams.builder()
                // one-time payment
                .setMode(SessionCreateParams.Mode.PAYMENT)

                // Sprint 5 scope = CARD only
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)

                .setSuccessUrl(stripeProperties.getSuccessUrl())
                .setCancelUrl(stripeProperties.getCancelUrl())

                /*
                 * Useful human/provider-side correlation field.
                 *
                 * We use logical Payment ID rather than exposing
                 * an internal provider-specific ID.
                 */
                .setClientReferenceId(request.paymentId().toString())

                .setExpiresAt(expiresAtEpochSeconds)

                .addLineItem(lineItem)

                // Session metadata
                .putMetadata("payment_id", request.paymentId().toString())
                .putMetadata("attempt_id",request.attemptId().toString())
                .putMetadata("booking_id", request.bookingId().toString())

                // PaymentIntent metadata
                .setPaymentIntentData(paymentIntentData)
                .build();
    }


    // ============================================================
    // Stripe response -> provider-neutral result
    // ============================================================

    private CheckoutResult mapCheckoutResult(Session session, CheckoutRequest request) {

        /*
         * Stripe call already returned successfully.
         *
         * If the response is unusable, we cannot classify it
         * as definitive failure because Stripe may already have
         * created the Session.
         */

        if (session == null) {

            log.error(
                    "CRITICAL: Stripe returned null Session. " +
                            "paymentId={} attemptId={}",
                    request.paymentId(),
                    request.attemptId()
            );

            throw new GatewayAmbiguousException(
                    "Stripe checkout was created but returned an unusable response"
            );
        }


        if (session.getId() == null || session.getId().isBlank()) {

            log.error(
                    "CRITICAL: Stripe Session returned without an ID. " +
                            "paymentId={} attemptId={}",
                    request.paymentId(),
                    request.attemptId()
            );

            throw new GatewayAmbiguousException(
                    "Stripe checkout returned an invalid session response"
            );
        }


        if (session.getUrl() == null || session.getUrl().isBlank()) {

            log.error(
                    "CRITICAL: Stripe Session {} has no checkout URL. " +
                            "paymentId={} attemptId={}",
                    session.getId(),
                    request.paymentId(),
                    request.attemptId()
            );

            throw new GatewayAmbiguousException(
                    "Stripe checkout returned no redirect URL"
            );
        }


        LocalDateTime expiresAt =
                toUtcLocalDateTime(
                        session.getExpiresAt()
                );


        log.info(
                "Stripe Checkout Session created. " +
                        "paymentId={} attemptId={} sessionId={}",
                request.paymentId(),
                request.attemptId(),
                session.getId()
        );


        return new CheckoutResult(
                session.getId(),
                session.getPaymentIntent(),
                session.getUrl(),
                expiresAt
        );
    }


    // ============================================================
    // Local validation
    // ============================================================

    private void validateRequest(CheckoutRequest request) {

        if (request == null) {
            throw new GatewayDefinitiveException(
                    "Checkout request must not be null"
            );
        }

        if (request.paymentId() == null) {
            throw new GatewayDefinitiveException(
                    "paymentId is required"
            );
        }

        if (request.attemptId() == null) {
            throw new GatewayDefinitiveException(
                "attemptId is required"
            );
        }

        if (request.bookingId() == null) {
            throw new GatewayDefinitiveException("bookingId is required"
            );
        }

        if (request.paymentMethod() != PaymentMethodType.CARD) {

            throw new GatewayDefinitiveException("Stripe gateway currently supports CARD only");
        }

        if (request.providerIdempotencyKey() == null || request.providerIdempotencyKey().isBlank()) {

            throw new GatewayDefinitiveException("Stripe provider idempotency key is required");
        }
    }


    private void validateConfiguration() {

        if (stripeProperties.getSuccessUrl() == null || stripeProperties.getSuccessUrl().isBlank()) {

            throw new GatewayDefinitiveException("Stripe success URL is not configured");
        }

        if (stripeProperties.getCancelUrl() == null || stripeProperties.getCancelUrl().isBlank()) {

            throw new GatewayDefinitiveException("Stripe cancel URL is not configured");
        }

        long expiryMinutes = stripeProperties.getCheckoutExpiryMinutes();

        if (expiryMinutes < MIN_CHECKOUT_EXPIRY_MINUTES || expiryMinutes > MAX_CHECKOUT_EXPIRY_MINUTES) {

            throw new GatewayDefinitiveException("Stripe checkout expiry must be between "
                            + MIN_CHECKOUT_EXPIRY_MINUTES + " and " + MAX_CHECKOUT_EXPIRY_MINUTES + " minutes");
        }
    }


    // ============================================================
    // Exception translation
    // ============================================================

    private GatewayDefinitiveException definitiveFailure(CheckoutRequest request, StripeException exception) {

        log.warn("Stripe definitively rejected checkout creation. " +
                        "paymentId={} attemptId={} stripeException={}",
                request.paymentId(),
                request.attemptId(),
                exception.getClass().getSimpleName(),
                exception
        );

        /*
         * IMPORTANT:
         *
         * We intentionally do NOT put exception.getMessage()
         * inside the Gateway exception message.
         *
         * PaymentServiceImpl may persist this message as
         * failureReason.
         *
         * Keep persisted reason sanitized/provider-neutral.
         */
        return new GatewayDefinitiveException("Stripe definitively rejected checkout creation", exception);
    }


    private GatewayAmbiguousException ambiguousFailure(CheckoutRequest request, StripeException exception) {

        log.error(
                "CRITICAL: Stripe checkout creation outcome is uncertain. " +
                        "paymentId={} attemptId={} stripeException={}",
                request.paymentId(),
                request.attemptId(),
                exception.getClass().getSimpleName(),
                exception
        );

        return new GatewayAmbiguousException("Stripe checkout outcome is uncertain", exception);
    }


    // ============================================================
    // Stripe epoch seconds -> UTC LocalDateTime
    // ============================================================

    private LocalDateTime toUtcLocalDateTime(Long epochSeconds) {

        if (epochSeconds == null) {
            return null;
        }

        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }
}