package com.project.paymentservice.service.impl;

import com.project.common.exception.BadRequestException;
import com.project.common.exception.ConflictException;
import com.project.common.exception.ServiceUnavailableException;
import com.project.paymentservice.client.BookingClient;
import com.project.paymentservice.client.dto.BookingPaymentContext;

import com.project.paymentservice.dto.request.PaymentInitiationRequest;
import com.project.paymentservice.dto.response.PaymentInitiationResponse;
import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.entity.PaymentIdempotencyRecord;

import com.project.paymentservice.gateway.PaymentGateway;
import com.project.paymentservice.gateway.PaymentGatewayResolver;
import com.project.paymentservice.gateway.exception.GatewayAmbiguousException;
import com.project.paymentservice.gateway.exception.GatewayDefinitiveException;
import com.project.paymentservice.gateway.model.CheckoutRequest;
import com.project.paymentservice.gateway.model.CheckoutResult;
import com.project.paymentservice.mapper.PaymentMapper;
import com.project.paymentservice.service.PaymentRequestHashService;
import com.project.paymentservice.service.PaymentService;
import com.project.paymentservice.service.PaymentTransactionService;
import com.project.paymentservice.service.model.AttemptClaimResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

        private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

        private final BookingClient bookingClient;

        private final PaymentRequestHashService hashService;

        private final PaymentTransactionService transactionService;

    private static final long CHECKOUT_WINDOW_MINUTES = 40;
    private static final long CHECKOUT_SAFETY_MARGIN_MINUTES = 1;

    private final PaymentGatewayResolver gatewayResolver;

        private final PaymentMapper paymentMapper;

        @Override
        public PaymentInitiationResponse initiatePayment(UUID userId, String idempotencyKey,
                        PaymentInitiationRequest request) {

                // ========================================================
                // 1. Validate application-level input
                // ========================================================

                validateInput(userId, idempotencyKey, request);

                String normalizedIdempotencyKey = idempotencyKey.trim();

                // ========================================================
                // 2. Generate deterministic hashes
                // ========================================================

                String idempotencyKeyHash = hashService.hashIdempotencyKey(normalizedIdempotencyKey);

                String requestHash = hashService.generateHash(
                                request.getBookingId(),
                                request.getProvider(),
                                request.getPaymentMethod());

                // ========================================================
                // 3. Resolve provider adapter
                //
                // No DB changes and no network call here.
                // We only verify that this provider has a configured gateway.
                // ========================================================

                PaymentGateway gateway = gatewayResolver.resolve(request.getProvider());

                // ========================================================
                // 4. Claim client idempotency FIRST
                //
                // MUST happen before any Booking mutation.
                //
                // If the same Idempotency-Key arrives with a different
                // request body, we catch the conflict here and never
                // touch the Booking.
                // ========================================================

                PaymentIdempotencyRecord idempotencyRecord = transactionService.claimIdempotency(
                                userId,
                                idempotencyKeyHash,
                                requestHash,
                                request.getBookingId());

                // ========================================================
                // 5. Fetch AUTHORITATIVE Booking payment context
                //
                // Happens AFTER idempotency claim so a conflicting key
                // never mutates the Booking state.
                //
                // Client never controls:
                // - userId
                // - amount
                // - currency
                // ========================================================

                BookingPaymentContext bookingContext = bookingClient.startPayment(request.getBookingId(), userId);

                validateBookingContext(userId, request.getBookingId(), bookingContext);

                // ========================================================
                // 6. Get/Create the single logical Payment
                //
                // One Booking -> one logical Payment.
                //
                // amount/currency come ONLY from Booking context.
                // ========================================================

                Payment payment = transactionService.getOrCreatePayment(
                                bookingContext.getBookingId(),
                                userId,
                                bookingContext.getTotalAmount(),
                                bookingContext.getCurrency());

                // ========================================================
                // 7. Claim or retrieve PaymentAttempt
                //
                // This transaction:
                // - locks Payment
                // - respects immutable idempotency mapping
                // - prevents two active attempts
                // - creates INITIALIZING when needed
                //
                // COMMIT BEFORE provider call.
                // ========================================================

                // Stripe checkout window + safety margin
                LocalDateTime latestCheckoutStartAt = bookingContext.getExpiresAt()
                        .minusMinutes(CHECKOUT_WINDOW_MINUTES + CHECKOUT_SAFETY_MARGIN_MINUTES);

                AttemptClaimResult claim = transactionService.claimAttemptForInitiation(
                                payment.getId(),
                                idempotencyRecord.getId(),
                                request.getProvider(),
                                request.getPaymentMethod(),
                                latestCheckoutStartAt);

                PaymentAttempt attempt = claim.attempt();

                // ========================================================
                // 8. Existing Attempt -> NEVER blindly call provider again
                // ========================================================

                if (!claim.createdNewAttempt()) {
                        return handleExistingAttempt(payment, attempt);
                }

                // ========================================================
                // At this point:
                //
                // PaymentAttempt = INITIALIZING
                // providerIdempotencyKey persisted
                // DB transaction COMMITTED
                //
                // NOW external provider call is safe.
                // ========================================================

                // ========================================================
                // 9. Build provider-neutral checkout request
                // ========================================================

                CheckoutRequest checkoutRequest = new CheckoutRequest(
                                payment.getId(),
                                attempt.getId(),
                                payment.getBookingId(),
                                payment.getAmount(),
                                payment.getCurrency(),
                                attempt.getPaymentMethod(),
                                attempt.getProviderIdempotencyKey(),
                                bookingContext.getExpiresAt());

                // ========================================================
                // 10. External provider call
                //
                // NO DATABASE TRANSACTION OPEN HERE.
                // ========================================================

                CheckoutResult checkoutResult;

                try {

                        checkoutResult = gateway.createCheckout(checkoutRequest);

                } catch (GatewayDefinitiveException ex) {

                        return handleDefinitiveGatewayFailure(attempt, ex);

                } catch (GatewayAmbiguousException ex) {

                        return handleAmbiguousGatewayFailure(attempt, ex);
                }

                // ========================================================
                // 11. Provider SUCCESS -> persist OPEN
                //
                // Important:
                // provider success is already KNOWN at this point.
                //
                // If local persistence fails:
                // DO NOT mark UNKNOWN.
                //
                // Provider truth = session exists.
                // Local INITIALIZING becomes reconciliation candidate.
                // ========================================================

                PaymentAttempt openAttempt;

                try {

                        openAttempt = transactionService.markAttemptOpen(
                                        attempt.getId(),
                                        checkoutResult.providerCheckoutId(),
                                        checkoutResult.providerPaymentId(),
                                        checkoutResult.redirectUrl(),
                                        checkoutResult.expiresAt());

                } catch (Exception ex) {

                        log.error(
                                        "CRITICAL: Provider checkout was created but local OPEN " +
                                                        "finalization failed. paymentId={} attemptId={} " +
                                                        "providerCheckoutId={}",
                                        payment.getId(),
                                        attempt.getId(),
                                        checkoutResult.providerCheckoutId(),
                                        ex);

                        throw new ServiceUnavailableException(
                                        "Payment checkout was created but could not be finalized locally");
                }

                // ========================================================
                // 12. Return provider-neutral API response
                // ========================================================

                log.info(
                                "Payment checkout initiated successfully. " +
                                                "paymentId={} attemptId={} provider={}",
                                payment.getId(),
                                openAttempt.getId(),
                                openAttempt.getProvider());

                return paymentMapper.toInitiationResponse(payment, openAttempt);
        }

        // ============================================================
        // Input validation
        // ============================================================

        private void validateInput(UUID userId, String idempotencyKey, PaymentInitiationRequest request) {

                if (userId == null) {
                        throw new IllegalArgumentException("userId must not be null");
                }

                if (request == null) {
                        throw new BadRequestException("Payment request is required");
                }

                if (idempotencyKey == null || idempotencyKey.isBlank()) {
                        throw new BadRequestException("Idempotency-Key header is required");
                }

                if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {

                        throw new BadRequestException(
                                        "Idempotency-Key must not exceed " + MAX_IDEMPOTENCY_KEY_LENGTH
                                                        + " characters");
                }

                if (request.getBookingId() == null) {
                        throw new BadRequestException("bookingId is required");
                }

                if (request.getProvider() == null) {
                        throw new BadRequestException("payment provider is required");
                }

                if (request.getPaymentMethod() == null) {
                        throw new BadRequestException("payment method is required");
                }
        }

        // ============================================================
        // Booking context validation
        // ============================================================

        private void validateBookingContext(UUID authenticatedUserId, UUID requestedBookingId,
                        BookingPaymentContext context) {

                if (context == null) {
                        throw new IllegalStateException(
                                        "Booking service returned an empty payment context");
                }

                if (context.getBookingId() == null || !requestedBookingId.equals(context.getBookingId())) {

                        throw new IllegalStateException(
                                        "Booking payment context contains an invalid bookingId");
                }

                if (context.getUserId() == null) {
                        throw new IllegalStateException(
                                        "Booking payment context contains no userId");
                }

                if (!authenticatedUserId.equals(context.getUserId())) {

                        throw new AccessDeniedException(
                                        "Booking does not belong to the authenticated user");
                }

                if (!"PAYMENT_PENDING".equals(context.getStatus())) {

                        throw new ConflictException(
                                        "Booking is not payable from status: "
                                                        + context.getStatus());
                }

                BigDecimal amount = context.getTotalAmount();

                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {

                        log.error(
                                        "CRITICAL: Booking {} returned invalid totalAmount={}",
                                        context.getBookingId(),
                                        amount);

                        throw new IllegalStateException(
                                        "Booking contains an invalid payment amount");
                }

                if (context.getCurrency() == null || context.getCurrency().isBlank()) {

                        throw new IllegalStateException(
                                        "Booking contains no payment currency");
                }

                if (context.getExpiresAt() == null) {
                        throw new IllegalStateException(
                                        "Booking payment context contains no effective expiry");
                }

                LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

                if (!context.getExpiresAt().isAfter(now)) {
                        throw new ConflictException(
                                        "Booking payment window has already expired");
                }
        }

        // ============================================================
        // Existing Attempt handling
        // ============================================================

        private PaymentInitiationResponse handleExistingAttempt(Payment payment, PaymentAttempt attempt) {

                if (attempt == null || attempt.getStatus() == null) {

                        throw new IllegalStateException(
                                        "Existing payment attempt is invalid");
                }

                return switch (attempt.getStatus()) {

                        case OPEN -> {

                                if (attempt.getRedirectUrl() == null || attempt.getRedirectUrl().isBlank()) {

                                        throw new IllegalStateException(
                                                        "OPEN payment attempt has no redirect URL");
                                }

                                if (attempt.getProviderExpiresAt() == null) {

                                        throw new IllegalStateException(
                                                        "OPEN payment attempt has no provider expiry");
                                }

                                LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

                                if (!attempt.getProviderExpiresAt().isAfter(now)) {

                                        /*
                                         * Stripe session has expired locally.
                                         *
                                         * Do not create another Checkout from this replay.
                                         * The provider/webhook must first resolve this Attempt to EXPIRED.
                                         * A later fresh initiation may create another Attempt if enough
                                         * Booking time remains.
                                         */
                                        throw new ConflictException(
                                                        "The payment session has expired.");
                                }

                                log.info(
                                                "Payment replay -- returning existing OPEN checkout. " +
                                                "paymentId={} attemptId={} providerExpiresAt={}",
                                                payment.getId(),
                                                attempt.getId(),
                                                attempt.getProviderExpiresAt());

                                yield paymentMapper.toInitiationResponse(payment, attempt);
                        }

                        case INITIALIZING -> throw new ConflictException(
                                        "Payment initiation is currently being processed");

                        case UNKNOWN -> throw new ServiceUnavailableException(
                                        "Payment provider outcome is unresolved; reconciliation is required");

                        case FAILED -> throw new ConflictException(
                                        "This payment attempt failed. A new payment attempt may be started if the booking window still has enough time.");

                        case EXPIRED -> throw new ConflictException(
                                        "This payment attempt has expired. A new payment attempt may be started if the booking window still has enough time.");

                        /*
                         * Same Idempotency-Key replay after the provider already
                         * confirmed success.
                         *
                         * Return the same logical payment/attempt rather than creating
                         * or redirecting to another attempt.
                         */
                        case SUCCEEDED ->
                                paymentMapper.toInitiationResponse(
                                                payment,
                                                attempt);
                };
        }

        // ============================================================
        // Definitive gateway failure
        // ============================================================

        private PaymentInitiationResponse handleDefinitiveGatewayFailure(
                        PaymentAttempt attempt,
                        GatewayDefinitiveException gatewayException) {

                log.error(
                                "Provider definitively rejected checkout creation. " +
                                                "attemptId={} provider={}",
                                attempt.getId(),
                                attempt.getProvider(),
                                gatewayException);

                try {

                        transactionService.markAttemptFailed(attempt.getId(),
                                        safeFailureReason(gatewayException.getMessage())

                        );

                } catch (Exception persistenceException) {

                        /*
                         * Provider definitively failed, so there is no unknown
                         * financial side-effect.
                         *
                         * The local INITIALIZING attempt may remain stale and can
                         * later be reconciled/cleaned up.
                         */
                        log.error(
                                        "CRITICAL: Provider definitively failed but local " +
                                                        "Attempt could not transition to FAILED. attemptId={}",
                                        attempt.getId(),
                                        persistenceException);
                }

                /*
                 * According to our current Failure Matrix B1 this is an internal /
                 * provider-configuration problem, not a client payment decline.
                 */
                throw new IllegalStateException(
                                "Payment provider rejected checkout creation", gatewayException);
        }

        // ============================================================
        // Ambiguous gateway failure
        // ============================================================

        private PaymentInitiationResponse handleAmbiguousGatewayFailure(
                        PaymentAttempt attempt,
                        GatewayAmbiguousException gatewayException) {

                log.error(
                                "CRITICAL: Provider checkout outcome is UNKNOWN. " +
                                                "attemptId={} provider={}",
                                attempt.getId(),
                                attempt.getProvider(),
                                gatewayException);

                try {

                        transactionService.markAttemptUnknown(
                                        attempt.getId(),
                                        safeFailureReason(
                                                        gatewayException.getMessage()));

                } catch (Exception persistenceException) {

                        /*
                         * We still MUST NOT retry/create a fresh provider operation here.
                         *
                         * Attempt may remain INITIALIZING and becomes a stale
                         * reconciliation candidate.
                         */
                        log.error(
                                        "CRITICAL: Could not persist UNKNOWN state. " +
                                                        "attemptId={}",
                                        attempt.getId(),
                                        persistenceException);
                }

                throw new ServiceUnavailableException(
                                "Payment provider outcome is uncertain and requires reconciliation");
        }

        // ============================================================
        // Persist only bounded operational reason text
        // ============================================================

        private String safeFailureReason(String message) {

                if (message == null || message.isBlank()) {
                        return "Provider operation failed";
                }

                return message.length() <= 500 ? message : message.substring(0, 500);
        }
}
