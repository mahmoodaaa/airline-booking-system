package com.project.paymentservice.gateway.stripe;

import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.gateway.exception.GatewayAmbiguousException;
import com.project.paymentservice.gateway.exception.GatewayDefinitiveException;
import com.project.paymentservice.gateway.model.CheckoutRequest;
import com.project.paymentservice.gateway.model.CheckoutResult;
import com.project.paymentservice.config.StripeProperties;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StripePaymentGateway}.
 *
 * Relies on {@link StripeCheckoutClient} mock to avoid deep-chaining
 * the Stripe SDK (stripeClient.v1().checkout().sessions().create(...)).
 *
 * Covers:
 *   - Happy path: full field mapping and metadata
 *   - Amount/currency conversion
 *   - Provider idempotency key forwarding
 *   - Definitive failure mapping (CardException, InvalidRequestException, etc.)
 *   - Ambiguous failure mapping (ApiConnectionException, ApiException)
 *   - Incomplete / null session responses
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StripePaymentGateway — createCheckout()")
class StripePaymentGatewayTest {

    @Mock
    private StripeCheckoutClient stripeCheckoutClient;



    @Mock
    private StripeProperties stripeProperties;

    private StripeAmountConverter amountConverter;
    private StripePaymentGateway gateway;

    private static final String SUCCESS_URL = "http://localhost:5173/payment/success";
    private static final String CANCEL_URL  = "http://localhost:5173/payment/cancel";
    private static final long   EXPIRY_MIN  = 30L;

    @BeforeEach
    void setUp() {
        amountConverter = new StripeAmountConverter();
        gateway = new StripePaymentGateway(
                stripeCheckoutClient,
                stripeProperties,
                amountConverter
        );

        when(stripeProperties.getSuccessUrl()).thenReturn(SUCCESS_URL);
        when(stripeProperties.getCancelUrl()).thenReturn(CANCEL_URL);
        when(stripeProperties.getCheckoutExpiryMinutes()).thenReturn(EXPIRY_MIN);
    }


    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("Happy path — successful Stripe session creation")
    class HappyPath {

        @Test
        @DisplayName("Returns populated CheckoutResult from valid Stripe Session")
        void returnsCheckoutResultOnSuccess() throws Exception {

            CheckoutRequest request = validRequest("120.75", "USD");

            Session session = stripeSession("cs_test_abc", "pi_test_abc",
                    "https://checkout.stripe.com/test", epochPlusSec(1800));

            when(stripeCheckoutClient.createSession(any(), any())).thenReturn(session);

            CheckoutResult result = gateway.createCheckout(request);

            assertThat(result.providerCheckoutId()).isEqualTo("cs_test_abc");
            assertThat(result.providerPaymentId()).isEqualTo("pi_test_abc");
            assertThat(result.redirectUrl()).isEqualTo("https://checkout.stripe.com/test");
            assertThat(result.expiresAt()).isNotNull();
        }

        @Test
        @DisplayName("Null paymentIntent is allowed — returned as null providerPaymentId")
        void allowsNullPaymentIntent() throws Exception {

            CheckoutRequest request = validRequest("50.00", "USD");

            Session session = stripeSession("cs_test_xyz", null,
                    "https://checkout.stripe.com/test", epochPlusSec(1800));

            when(stripeCheckoutClient.createSession(any(), any())).thenReturn(session);

            CheckoutResult result = gateway.createCheckout(request);

            assertThat(result.providerCheckoutId()).isEqualTo("cs_test_xyz");
            assertThat(result.providerPaymentId()).isNull();
        }

        @Test
        @DisplayName("Null expiresAt from Stripe is returned as null in result")
        void allowsNullExpiresAt() throws Exception {

            CheckoutRequest request = validRequest("99.00", "USD");

            Session session = stripeSession("cs_test_nox", "pi_test_nox",
                    "https://checkout.stripe.com/test", null);

            when(stripeCheckoutClient.createSession(any(), any())).thenReturn(session);

            CheckoutResult result = gateway.createCheckout(request);

            assertThat(result.expiresAt()).isNull();
        }
    }


    // =========================================================================
    // Amount / currency conversion
    // =========================================================================

    @Nested
    @DisplayName("Amount and currency conversion")
    class AmountConversion {

        @Test
        @DisplayName("120.75 USD → 12075 sent to Stripe")
        void convertsUsdToMinorUnits() throws Exception {

            CheckoutRequest request = validRequest("120.75", "USD");
            stubSessionSuccess();

            gateway.createCheckout(request);

            ArgumentCaptor<SessionCreateParams> captor =
                    ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(stripeCheckoutClient).createSession(captor.capture(), any());

            SessionCreateParams params = captor.getValue();
            SessionCreateParams.LineItem lineItem = params.getLineItems().get(0);
            assertThat(lineItem.getPriceData().getUnitAmount()).isEqualTo(12075L);
            assertThat(lineItem.getPriceData().getCurrency()).isEqualTo("usd");
        }

        @Test
        @DisplayName("500 JPY → 500 (zero-decimal currency)")
        void convertsJpyZeroDecimal() throws Exception {

            CheckoutRequest request = validRequest("500", "JPY");
            stubSessionSuccess();

            gateway.createCheckout(request);

            ArgumentCaptor<SessionCreateParams> captor =
                    ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(stripeCheckoutClient).createSession(captor.capture(), any());

            SessionCreateParams params = captor.getValue();
            SessionCreateParams.LineItem lineItem = params.getLineItems().get(0);
            assertThat(lineItem.getPriceData().getUnitAmount()).isEqualTo(500L);
            assertThat(lineItem.getPriceData().getCurrency()).isEqualTo("jpy");
        }

        @Test
        @DisplayName("Unsupported currency throws GatewayDefinitiveException — Stripe not called")
        void throwsDefinitiveOnUnsupportedCurrency() {

            CheckoutRequest request = validRequest("100.00", "XYZ");

            assertThatThrownBy(() -> gateway.createCheckout(request))
                    .isInstanceOf(GatewayDefinitiveException.class)
                    .hasMessageContaining("Invalid amount or currency");
        }

        @Test
        @DisplayName("Amount with extra precision throws GatewayDefinitiveException")
        void throwsDefinitiveOnHighPrecisionAmount() {

            // USD only allows 2 decimal places → 10.123 has 3
            CheckoutRequest request = validRequest("10.123", "USD");

            assertThatThrownBy(() -> gateway.createCheckout(request))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }
    }


    // =========================================================================
    // Provider idempotency key
    // =========================================================================

    @Nested
    @DisplayName("Provider idempotency key forwarding")
    class IdempotencyKeyForwarding {

        @Test
        @DisplayName("providerIdempotencyKey is forwarded as Stripe RequestOptions idempotency key")
        void forwardsIdempotencyKeyToStripe() throws Exception {

            String idemKey = "provider-stable-key-999";
            CheckoutRequest request = new CheckoutRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new BigDecimal("80.00"),
                    "USD",
                    PaymentMethodType.CARD,
                    idemKey
            );

            stubSessionSuccess();

            gateway.createCheckout(request);

            ArgumentCaptor<RequestOptions> captor =
                    ArgumentCaptor.forClass(RequestOptions.class);
            verify(stripeCheckoutClient).createSession(any(), captor.capture());

            assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(idemKey);
        }
    }


    // =========================================================================
    // Metadata
    // =========================================================================

    @Nested
    @DisplayName("Session and PaymentIntent metadata")
    class Metadata {

        @Test
        @DisplayName("paymentId, attemptId, bookingId are set in Session metadata")
        void setsSessionMetadata() throws Exception {

            UUID paymentId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();

            CheckoutRequest request = new CheckoutRequest(
                    paymentId, attemptId, bookingId,
                    new BigDecimal("200.00"), "USD",
                    PaymentMethodType.CARD, "idem-key"
            );

            stubSessionSuccess();
            gateway.createCheckout(request);

            ArgumentCaptor<SessionCreateParams> captor =
                    ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(stripeCheckoutClient).createSession(captor.capture(), any());

            SessionCreateParams params = captor.getValue();
            assertThat(params.getMetadata()).containsEntry("payment_id", paymentId.toString());
            assertThat(params.getMetadata()).containsEntry("attempt_id", attemptId.toString());
            assertThat(params.getMetadata()).containsEntry("booking_id", bookingId.toString());
        }

        @Test
        @DisplayName("paymentId is set as clientReferenceId")
        void setsClientReferenceId() throws Exception {

            UUID paymentId = UUID.randomUUID();
            CheckoutRequest request = new CheckoutRequest(
                    paymentId, UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("75.00"), "USD",
                    PaymentMethodType.CARD, "idem-key"
            );

            stubSessionSuccess();
            gateway.createCheckout(request);

            ArgumentCaptor<SessionCreateParams> captor =
                    ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(stripeCheckoutClient).createSession(captor.capture(), any());

            assertThat(captor.getValue().getClientReferenceId())
                    .isEqualTo(paymentId.toString());
        }
    }


    // =========================================================================
    // Definitive failures → GatewayDefinitiveException
    // =========================================================================

    @Nested
    @DisplayName("Definitive Stripe exceptions → GatewayDefinitiveException")
    class DefinitiveFailures {

        @Test
        @DisplayName("CardException → GatewayDefinitiveException")
        void cardException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new CardException("card declined", "req_1", "card_declined",
                            "card_declined", null, null, 402, null));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }

        @Test
        @DisplayName("InvalidRequestException → GatewayDefinitiveException")
        void invalidRequestException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new InvalidRequestException("Invalid param", "amount", "req_1",
                            null, 400, null));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }

        @Test
        @DisplayName("AuthenticationException → GatewayDefinitiveException")
        void authenticationException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new AuthenticationException("Invalid API key", "req_1", null, 401));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }

        @Test
        @DisplayName("PermissionException → GatewayDefinitiveException")
        void permissionException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new PermissionException("Permission denied", "req_1", null, 403));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }

        @Test
        @DisplayName("IdempotencyException → GatewayDefinitiveException")
        void idempotencyException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new IdempotencyException("Idempotency error", "req_1", null, 400));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }

        @Test
        @DisplayName("RateLimitException → GatewayDefinitiveException")
        void rateLimitException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new RateLimitException("Too many requests", "req_1", null, null, 429, null));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class);
        }
    }


    // =========================================================================
    // Ambiguous failures → GatewayAmbiguousException
    // =========================================================================

    @Nested
    @DisplayName("Ambiguous Stripe exceptions → GatewayAmbiguousException")
    class AmbiguousFailures {

        @Test
        @DisplayName("ApiConnectionException → GatewayAmbiguousException")
        void apiConnectionException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new ApiConnectionException("Connection refused"));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayAmbiguousException.class);
        }

        @Test
        @DisplayName("Generic ApiException (e.g. 500 from Stripe) → GatewayAmbiguousException")
        void apiException() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any()))
                    .thenThrow(new com.stripe.exception.ApiException("Internal error", "req_1",
                            null, 500, null));

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayAmbiguousException.class);
        }
    }


    // =========================================================================
    // Incomplete session responses
    // =========================================================================

    @Nested
    @DisplayName("Incomplete / null Session responses → GatewayAmbiguousException")
    class IncompleteSessionResponses {

        @Test
        @DisplayName("Null Session → GatewayAmbiguousException")
        void nullSession() throws Exception {
            when(stripeCheckoutClient.createSession(any(), any())).thenReturn(null);

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayAmbiguousException.class)
                    .hasMessageContaining("unusable response");
        }

        @Test
        @DisplayName("Session with null id → GatewayAmbiguousException")
        void sessionWithNullId() throws Exception {
            Session session = stripeSession(null, "pi_x",
                    "https://checkout.stripe.com/x", epochPlusSec(1800));

            when(stripeCheckoutClient.createSession(any(), any())).thenReturn(session);

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayAmbiguousException.class)
                    .hasMessageContaining("invalid session response");
        }

        @Test
        @DisplayName("Session with null URL → GatewayAmbiguousException")
        void sessionWithNullUrl() throws Exception {
            Session session = stripeSession("cs_test_x", "pi_x", null, epochPlusSec(1800));

            when(stripeCheckoutClient.createSession(any(), any())).thenReturn(session);

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayAmbiguousException.class)
                    .hasMessageContaining("no redirect URL");
        }
    }


    // =========================================================================
    // Local validation (no Stripe call)
    // =========================================================================

    @Nested
    @DisplayName("Local validation — Stripe is never called")
    class LocalValidation {

        @Test
        @DisplayName("Non-CARD payment method → GatewayDefinitiveException")
        void nonCardPaymentMethod() {
            CheckoutRequest request = new CheckoutRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("100"), "USD",
                    PaymentMethodType.WALLET,
                    "idem-key"
            );

            assertThatThrownBy(() -> gateway.createCheckout(request))
                    .isInstanceOf(GatewayDefinitiveException.class)
                    .hasMessageContaining("CARD only");
        }

        @Test
        @DisplayName("Blank providerIdempotencyKey → GatewayDefinitiveException")
        void blankIdempotencyKey() {
            CheckoutRequest request = new CheckoutRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("100"), "USD",
                    PaymentMethodType.CARD,
                    "   "
            );

            assertThatThrownBy(() -> gateway.createCheckout(request))
                    .isInstanceOf(GatewayDefinitiveException.class)
                    .hasMessageContaining("idempotency key");
        }

        @Test
        @DisplayName("Blank successUrl config → GatewayDefinitiveException")
        void blankSuccessUrl() {
            when(stripeProperties.getSuccessUrl()).thenReturn("  ");

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class)
                    .hasMessageContaining("success URL");
        }

        @Test
        @DisplayName("Checkout expiry below minimum → GatewayDefinitiveException")
        void expiryBelowMin() {
            when(stripeProperties.getCheckoutExpiryMinutes()).thenReturn(10L); // below 30

            assertThatThrownBy(() -> gateway.createCheckout(validRequest("100", "USD")))
                    .isInstanceOf(GatewayDefinitiveException.class)
                    .hasMessageContaining("expiry");
        }
    }


    // =========================================================================
    // Helpers
    // =========================================================================

    private CheckoutRequest validRequest(String amount, String currency) {
        return new CheckoutRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal(amount),
                currency,
                PaymentMethodType.CARD,
                "provider-idem-key-" + UUID.randomUUID()
        );
    }

    private Session stripeSession(String id, String paymentIntent,
                                  String url, Long expiresAt) {
        Session s = new Session();
        s.setId(id);
        s.setPaymentIntent(paymentIntent);
        s.setUrl(url);
        s.setExpiresAt(expiresAt);
        return s;
    }

    private long epochPlusSec(long seconds) {
        return Instant.now().plusSeconds(seconds).getEpochSecond();
    }

    /** Stubs a minimal valid Stripe session so happy-path tests don't need to repeat boilerplate. */
    private void stubSessionSuccess() throws Exception {
        Session session = stripeSession(
                "cs_test_ok", "pi_test_ok",
                "https://checkout.stripe.com/ok",
                epochPlusSec(1800)
        );
        when(stripeCheckoutClient.createSession(any(SessionCreateParams.class),
                any(RequestOptions.class))).thenReturn(session);
    }
}
