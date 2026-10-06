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
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;




import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;


@Slf4j
@Component
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {

    private static final long MIN_CHECKOUT_EXPIRY_MINUTES = 30;
    private static final long MAX_CHECKOUT_EXPIRY_MINUTES = 24 * 60;
    // Guard: Stripe checkout must finish this many seconds before the Booking deadline.
    private static final long BOOKING_DEADLINE_BUFFER_SECONDS = 60;

    private final StripeCheckoutClient stripeCheckoutClient;

    private final StripeRefundClient stripeRefundClient;

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
    // Full Refund (booking-rejection compensation)
    // ============================================================

    @Override
    public String refundFullPayment(String providerPaymentId, String idempotencyKey) {

        // --------------------------------------------------------
        // 1. Validate inputs before any network call.
        // --------------------------------------------------------

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new GatewayDefinitiveException("providerPaymentId is required for refund");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new GatewayDefinitiveException("idempotencyKey is required for refund");
        }


        // --------------------------------------------------------
        // 2. Build Stripe Refund params.
        //
        // Full refund: omit amount so Stripe uses the full captured amount.
        // --------------------------------------------------------

        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(providerPaymentId)
                .build();

        RequestOptions requestOptions = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();


        // --------------------------------------------------------
        // 3. Stripe network call — no DB transaction open here.
        // --------------------------------------------------------

        final Refund refund;

        try {

            refund = stripeRefundClient.createRefund(params, requestOptions);

        } catch (CardException |
                 InvalidRequestException |
                 AuthenticationException |
                 IdempotencyException ex) {

            log.warn(
                    "Stripe definitively rejected refund. " +
                    "providerPaymentId={} stripeException={}",
                    providerPaymentId,
                    ex.getClass().getSimpleName(),
                    ex
            );

            throw new GatewayDefinitiveException("Stripe definitively rejected refund", ex);

        } catch (ApiConnectionException | ApiException ex) {

            log.error(
                    "CRITICAL: Stripe refund outcome is uncertain. " +
                    "providerPaymentId={} stripeException={}",
                    providerPaymentId,
                    ex.getClass().getSimpleName(),
                    ex
            );

            throw new GatewayAmbiguousException("Stripe refund outcome is uncertain", ex);

        } catch (StripeException ex) {

            log.error(
                    "CRITICAL: Stripe refund outcome is uncertain. " +
                    "providerPaymentId={} stripeException={}",
                    providerPaymentId,
                    ex.getClass().getSimpleName(),
                    ex
            );

            throw new GatewayAmbiguousException("Stripe refund outcome is uncertain", ex);
        }


        // --------------------------------------------------------
        // 4. Map Stripe response.
        //
        // If Stripe returned success but the response is unusable,
        // treat as ambiguous: the refund may have been processed.
        // --------------------------------------------------------

        if (refund == null || refund.getId() == null || refund.getId().isBlank()) {

            log.error(
                    "CRITICAL: Stripe refund returned unusable response. " +
                    "providerPaymentId={}",
                    providerPaymentId
            );

            throw new GatewayAmbiguousException("Stripe refund returned an unusable response");
        }

        log.info(
                "Stripe refund created. providerPaymentId={} providerRefundId={}",
                providerPaymentId,
                refund.getId()
        );

        return refund.getId();
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


        // --------------------------------------------------------
        // Checkout session lifetime
        //
        // We use an INDEPENDENT configured window (checkoutExpiryMinutes)
        // rather than deriving from bookingExpiresAt.
        //
        // This avoids the "holdUntil - 5 min" race where processing delay
        // can push the computed expiry below Stripe's 30-minute minimum.
        //
        // bookingExpiresAt serves ONLY as an upper-bound safety guard:
        // if now + checkoutExpiryMinutes >= bookingExpiresAt, we reject
        // before hitting Stripe at all.
        // --------------------------------------------------------

        long nowEpoch = Instant.now().getEpochSecond();
        long configuredExpiryEpoch = nowEpoch + (stripeProperties.getCheckoutExpiryMinutes() * 60);

        if (request.bookingExpiresAt() != null) {
            long bookingDeadlineEpoch = request.bookingExpiresAt().toEpochSecond(ZoneOffset.UTC);

            if (configuredExpiryEpoch >= bookingDeadlineEpoch - BOOKING_DEADLINE_BUFFER_SECONDS) {
                throw new GatewayDefinitiveException(
                        "Stripe checkout window would overlap or exceed the Booking payment deadline. " +
                        "Remaining booking window is too short to start a new Checkout."
                );
            }
        }

        long minAllowedEpoch = nowEpoch + (MIN_CHECKOUT_EXPIRY_MINUTES * 60);
        long maxAllowedEpoch = nowEpoch + (MAX_CHECKOUT_EXPIRY_MINUTES * 60);
        long expiresAtEpochSeconds = Math.min(Math.max(configuredExpiryEpoch, minAllowedEpoch), maxAllowedEpoch);

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