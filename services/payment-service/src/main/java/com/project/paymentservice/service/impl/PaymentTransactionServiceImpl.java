package com.project.paymentservice.service.impl;

import com.project.common.exception.ConflictException;
import com.project.common.exception.RecordNotFoundException;
import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.entity.PaymentIdempotencyRecord;
import com.project.paymentservice.enums.*;
import com.project.paymentservice.repository.PaymentAttemptRepository;
import com.project.paymentservice.repository.PaymentIdempotencyRecordRepository;
import com.project.paymentservice.repository.PaymentRepository;
import com.project.paymentservice.service.PaymentTransactionService;
import com.project.paymentservice.service.model.AttemptClaimResult;
import com.project.paymentservice.service.model.PaymentSuccessResult;
import com.project.paymentservice.gateway.stripe.StripeAmountConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentTransactionServiceImpl implements PaymentTransactionService {

    private static final Set<PaymentAttemptStatus> ACTIVE_ATTEMPT_STATUSES =
            EnumSet.of(
                    PaymentAttemptStatus.INITIALIZING,
                    PaymentAttemptStatus.OPEN,
                    PaymentAttemptStatus.UNKNOWN
            );

    private final PaymentIdempotencyRecordRepository idempotencyRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    
    private final StripeAmountConverter stripeAmountConverter;


    // =========================================================
    // Idempotency
    // =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIdempotencyRecord claimIdempotency(
            UUID userId,
            String idempotencyKeyHash,
            String requestHash,
            UUID bookingId
    ) {

        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKeyHash, "idempotencyKeyHash must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        Objects.requireNonNull(bookingId, "bookingId must not be null");

        Optional<PaymentIdempotencyRecord> existing = idempotencyRepository
                .findByUserIdAndIdempotencyKeyHash(userId, idempotencyKeyHash);

        if (existing.isPresent()) {
            return validateIdempotencyReplay(existing.get(), requestHash);
        }

        UUID recordId = UUID.randomUUID();

        int inserted = idempotencyRepository.insertIfAbsent(
                recordId,
                userId,
                idempotencyKeyHash,
                requestHash,
                bookingId
        );

        if (inserted == 1) {
            return idempotencyRepository.findById(recordId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency claim was inserted but cannot be reloaded"
                    ));
        }

        // Another request won the UNIQUE race.
        PaymentIdempotencyRecord winner =
                idempotencyRepository
                        .findByUserIdAndIdempotencyKeyHash(
                                userId,
                                idempotencyKeyHash
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency winner missing after concurrent insert"
                        ));

        return validateIdempotencyReplay(
                winner,
                requestHash
        );
    }


    private PaymentIdempotencyRecord validateIdempotencyReplay(
            PaymentIdempotencyRecord record,
            String incomingRequestHash
    ) {

        if (!record.getRequestHash().equals(incomingRequestHash)) {
            throw new ConflictException(
                    "The same Idempotency-Key was already used with a different request"
            );
        }

        return record;
    }


    // =========================================================
    // Logical Payment
    // =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment getOrCreatePayment(UUID bookingId, UUID userId, BigDecimal amount, String currency) {

        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        Optional<Payment> existing = paymentRepository.findByBookingId(bookingId);

        if (existing.isPresent()) {
            Payment payment = existing.get();

            assertPaymentSnapshotMatches(payment, userId, amount, currency);

            return payment;
        }

        UUID paymentId = UUID.randomUUID();

        paymentRepository.insertIfAbsent(paymentId, bookingId, userId, amount, currency);

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalStateException("Payment missing after get-or-create operation"));

        assertPaymentSnapshotMatches(payment, userId, amount, currency);

        return payment;
    }


    private void assertPaymentSnapshotMatches(Payment payment, UUID userId, BigDecimal amount, String currency) {

        boolean sameUser = payment.getUserId().equals(userId);

        boolean sameAmount = payment.getAmount().compareTo(amount) == 0;

        boolean sameCurrency = payment.getCurrency().equalsIgnoreCase(currency);

        if (!sameUser || !sameAmount || !sameCurrency) {
            log.error("CRITICAL Payment snapshot mismatch paymentId={} bookingId={}",
                    payment.getId(),
                    payment.getBookingId()
            );

            throw new IllegalStateException("Existing Payment financial snapshot does not match Booking context");
        }
    }


    // =========================================================
    // Attempt Claim
    // =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttemptClaimResult claimAttemptForInitiation(
            UUID paymentId,
            UUID idempotencyRecordId,
            PaymentProvider provider,
            PaymentMethodType paymentMethod
    ) {

        // ============================================================
        // 1. Lock the logical Payment
        //
        // From this point until COMMIT, no concurrent transaction
        // can make another attempt-creation decision for this Payment.
        // ============================================================

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new RecordNotFoundException("Payment not found: " + paymentId));


        // ============================================================
        // 2. Load the client idempotency record
        // ============================================================

        PaymentIdempotencyRecord idempotencyRecord = idempotencyRepository.findById(idempotencyRecordId)
                        .orElseThrow(() -> new IllegalStateException("Idempotency record not found: " + idempotencyRecordId));


        // ============================================================
        // 3. Idempotency record must never belong to another Payment
        //
        // If paymentId is still null, this record has not been bound yet.
        // That is normal for a newly claimed Idempotency-Key.
        // ============================================================

        if (idempotencyRecord.getPaymentId() != null && !idempotencyRecord.getPaymentId().equals(paymentId)) {

            log.error(
                    "CRITICAL: Idempotency record {} is linked to payment {} " +
                            "but was used with payment {}",
                    idempotencyRecord.getId(),
                    idempotencyRecord.getPaymentId(),
                    paymentId
            );

            throw new IllegalStateException("Idempotency record is already linked to another Payment");
        }


        // ============================================================
        // 4. FINANCIAL TERMINAL-STATE GUARD
        //
        // IMPORTANT:
        //
        // Once the logical Payment has succeeded, we must NEVER create
        // another provider attempt merely because the caller supplied
        // a new Idempotency-Key.
        //
        // This protects against:
        //
        // Payment = SUCCEEDED
        // Booking = still PENDING
        // new client Idempotency-Key
        // -> accidental second provider charge
        //
        // The ONLY allowed case is a legitimate replay of an
        // Idempotency-Key that was already mapped to the canonical
        // successful attempt.
        // ============================================================

        if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.REFUNDED) {

            UUID succeededAttemptId = payment.getSucceededAttemptId();


            // --------------------------------------------------------
            // Terminal Payment without canonical successful attempt
            // means our persisted financial state is inconsistent.
            // --------------------------------------------------------

            if (succeededAttemptId == null) {

                log.error("CRITICAL: Terminal Payment has no succeededAttemptId. " +
                          "paymentId={} status={}", payment.getId(), payment.getStatus());

                throw new IllegalStateException("Terminal Payment has no canonical successful attempt");
            }


            // --------------------------------------------------------
            // Legitimate replay:
            //
            // The current Idempotency-Key had ALREADY been associated
            // with the canonical successful attempt.
            //
            // K1 ─┐
            //     ├── A1 -> SUCCEEDED
            // K2 ─┘
            //
            // Both K1 and K2 are legitimate replays if both were
            // already mapped to A1 before/while it succeeded.
            // --------------------------------------------------------

            if (succeededAttemptId.equals(idempotencyRecord.getAttemptId())) {

                PaymentAttempt successfulAttempt = attemptRepository.findById(succeededAttemptId)
                                .orElseThrow(() -> new IllegalStateException("Canonical successful attempt is missing: " + succeededAttemptId));


                assertAttemptBelongsToPayment(successfulAttempt, payment);


                /*
                 * Defensive check.
                 *
                 * claimIdempotency() already protects the requestHash,
                 * but we still enforce provider/method consistency here.
                 */
                assertAttemptMatchesRequest(successfulAttempt, provider, paymentMethod);


                log.debug(
                        "Idempotent replay of canonical successful attempt. " + "paymentId={} attemptId={} status={}",
                        payment.getId(),
                        successfulAttempt.getId(),
                        payment.getStatus()
                );


                return new AttemptClaimResult(successfulAttempt, false);
            }


            // --------------------------------------------------------
            // Every other key is rejected.
            //
            // Includes:
            //
            // - brand-new Idempotency-Key
            // - key mapped to an old FAILED attempt
            // - key mapped to an old EXPIRED attempt
            // - any non-canonical attempt
            //
            // DO NOT bind the new key to the successful attempt.
            // DO NOT create another Attempt.
            // --------------------------------------------------------

            log.warn(
                    "Rejected new payment attempt for terminal Payment. " +
                            "paymentId={} status={} idempotencyRecordId={}",
                    payment.getId(),
                    payment.getStatus(),
                    idempotencyRecord.getId()
            );

            throw new ConflictException("This booking has already been paid successfully; " + "a new payment attempt is not allowed");
        }


        // ============================================================
        // 5. Existing idempotency mapping is immutable
        //
        // Same Idempotency-Key must always resolve to the exact same
        // PaymentAttempt.
        //
        // Never remap:
        //
        // K1 -> A1
        //
        // into:
        //
        // K1 -> A2
        // ============================================================

        if (idempotencyRecord.getAttemptId() != null) {
            PaymentAttempt mappedAttempt = attemptRepository.findById(idempotencyRecord.getAttemptId())
                            .orElseThrow(() -> new IllegalStateException("Idempotency record points to a missing attempt: " + idempotencyRecord.getAttemptId()));


            assertAttemptBelongsToPayment(mappedAttempt, payment);


            assertAttemptMatchesRequest(mappedAttempt, provider, paymentMethod);

            return new AttemptClaimResult(mappedAttempt, false);
        }


        // ============================================================
        // 6. Payment must still be financially eligible
        //
        // Current expected non-terminal state is PENDING.
        //
        // This default-deny check protects us if another PaymentStatus
        // is introduced later and someone forgets to update this flow.
        // ============================================================

        if (payment.getStatus() != PaymentStatus.PENDING) {

            log.error(
                    "Payment {} cannot create a new attempt from status {}",
                    payment.getId(),
                    payment.getStatus()
            );

            throw new ConflictException("Payment is not eligible for a new payment attempt");
        }


        // ============================================================
        // 7. Look for an unresolved active Attempt
        //
        // Must happen while Payment row is still locked.
        //
        // Active:
        //
        // INITIALIZING
        // OPEN
        // UNKNOWN
        //
        // We must not create another provider operation while any of
        // these states exists.
        // ============================================================

        Optional<PaymentAttempt> activeAttempt = attemptRepository.findActiveAttempt(paymentId, ACTIVE_ATTEMPT_STATUSES);


        if (activeAttempt.isPresent()) {

            PaymentAttempt attempt = activeAttempt.get();


            // --------------------------------------------------------
            // Another active provider/method already owns this Payment.
            //
            // Example:
            //
            // STRIPE + CARD = OPEN
            //
            // incoming:
            //
            // PAYPAL + WALLET
            //
            // Don't allow multiple simultaneously payable channels.
            // --------------------------------------------------------

            if (attempt.getProvider() != provider || attempt.getPaymentMethod() != paymentMethod) {

                throw new ConflictException("Another payment attempt is already active "
                                + "for this booking using a different "
                                + "provider or payment method"
                );
            }


            // --------------------------------------------------------
            // Same provider + same payment method.
            //
            // Bind this new client Idempotency-Key to the existing
            // unresolved Attempt rather than creating another one.
            // --------------------------------------------------------

            bindIdempotencyRecord(idempotencyRecord, payment, attempt);


            return new AttemptClaimResult(attempt, false);
        }


        // ============================================================
        // 8. No active Attempt
        //
        // Create the durable INITIALIZING claim.
        //
        // providerIdempotencyKey is generated ONCE and persisted before
        // any external provider network call.
        // ============================================================

        PaymentAttempt attempt = PaymentAttempt.builder()
                        .payment(payment)
                        .provider(provider)
                        .paymentMethod(paymentMethod)
                        .providerIdempotencyKey(UUID.randomUUID().toString())
                        .status(PaymentAttemptStatus.INITIALIZING)
                        .build();


        attempt = attemptRepository.saveAndFlush(attempt);


        // ============================================================
        // 9. Permanently bind this client Idempotency-Key
        //    to this Payment + Attempt
        // ============================================================

        bindIdempotencyRecord(idempotencyRecord, payment, attempt);


        // ============================================================
        // 10. Return ownership of provider initiation
        //
        // true means:
        //
        // THIS request created the durable INITIALIZING attempt.
        //
        // Therefore PaymentServiceImpl is allowed to call:
        //
        // PaymentGateway.createCheckout(...)
        //
        // AFTER this REQUIRES_NEW transaction commits.
        // ============================================================

        return new AttemptClaimResult(attempt, true);
    }

    private void bindIdempotencyRecord(
            PaymentIdempotencyRecord record,
            Payment payment,
            PaymentAttempt attempt
    ) {

        if (record.getAttemptId() != null) {
            throw new IllegalStateException(
                    "Idempotency record must never be remapped to another attempt"
            );
        }

        record.setPaymentId(payment.getId());
        record.setAttemptId(attempt.getId());

        idempotencyRepository.saveAndFlush(record);
    }


    private void assertAttemptBelongsToPayment(PaymentAttempt attempt, Payment payment) {

        if (!attempt.getPayment().getId().equals(payment.getId())) {
            throw new IllegalStateException(
                    "Mapped PaymentAttempt belongs to another Payment"
            );
        }
    }


    private void assertAttemptMatchesRequest(PaymentAttempt attempt, PaymentProvider provider,
                                                                   PaymentMethodType paymentMethod)
    {

        if (attempt.getProvider() != provider || attempt.getPaymentMethod() != paymentMethod) {

            throw new ConflictException("The idempotent payment request does not match "
                                      + "the previously mapped provider or payment method"
            );
        }
    }


    // =========================================================
    // Provider Session Created
    // =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt markAttemptOpen(
            UUID attemptId,
            String providerCheckoutId,
            String providerPaymentId,
            String redirectUrl,
            LocalDateTime providerExpiresAt
    ) {

        LocalDateTime now = LocalDateTime.now();

        int updated = attemptRepository.markOpen(
                attemptId,
                PaymentAttemptStatus.INITIALIZING,
                PaymentAttemptStatus.OPEN,
                providerCheckoutId,
                providerPaymentId,
                redirectUrl,
                providerExpiresAt,
                now
        );

        PaymentAttempt current = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new RecordNotFoundException("Payment attempt not found: " + attemptId));


        // ============================================================
        // 1. Normal happy path
        //
        // INITIALIZING -> OPEN
        // ============================================================

        if (updated == 1) {
            return current;
        }


        // ============================================================
        // 2. Idempotent replay
        //
        // markAttemptOpen() may be called again for the same provider
        // Checkout Session.
        //
        // OPEN + same providerCheckoutId = safe replay.
        // ============================================================

        if (current.getStatus() == PaymentAttemptStatus.OPEN && Objects.equals(current.getProviderCheckoutId(), providerCheckoutId)) {

            /*
             * If both PaymentIntent IDs are known,
             * they must represent the same provider operation.
             */
            if (providerPaymentId != null && current.getProviderPaymentId() != null
                    && !Objects.equals(current.getProviderPaymentId(), providerPaymentId)) {

                log.error(
                        "CRITICAL OPEN attempt has different providerPaymentId. " +
                                "attemptId={} storedProviderPaymentId={} incomingProviderPaymentId={}",
                        attemptId,
                        current.getProviderPaymentId(),
                        providerPaymentId
                );

                throw new IllegalStateException(
                        "OPEN attempt has a different providerPaymentId"
                );
            }

            return current;
        }


        // ============================================================
        // 3. Webhook won the race
        //
        // Possible sequence:
        //
        // Thread A:
        // Stripe createCheckout() succeeds
        //
        // Thread B:
        // Stripe immediately sends checkout.session.completed
        // webhook marks Attempt -> SUCCEEDED
        //
        // Thread A:
        // tries INITIALIZING -> OPEN
        // CAS updates 0 rows
        //
        // This is NOT an error.
        //
        // Provider financial truth is newer and stronger.
        //
        // NEVER:
        // SUCCEEDED -> OPEN
        // ============================================================

        if (current.getStatus() == PaymentAttemptStatus.SUCCEEDED
                && Objects.equals(
                current.getProviderCheckoutId(),
                providerCheckoutId
        )) {

            /*
             * Same defensive provider identity validation.
             */
            if (providerPaymentId != null
                    && current.getProviderPaymentId() != null
                    && !Objects.equals(
                    current.getProviderPaymentId(),
                    providerPaymentId
            )) {

                log.error(
                        "CRITICAL SUCCEEDED attempt has different providerPaymentId. " +
                                "attemptId={} storedProviderPaymentId={} incomingProviderPaymentId={}",
                        attemptId,
                        current.getProviderPaymentId(),
                        providerPaymentId
                );

                throw new IllegalStateException(
                        "SUCCEEDED attempt has a different providerPaymentId"
                );
            }


            log.info(
                    "Webhook won OPEN finalization race. " +
                            "Attempt is already SUCCEEDED. attemptId={} providerCheckoutId={}",
                    attemptId,
                    providerCheckoutId
            );


            /*
             * Do NOT downgrade:
             *
             * SUCCEEDED -> OPEN
             *
             * Just return the newer provider truth.
             */
            return current;
        }


        // ============================================================
        // 4. Unexpected state
        //
        // Examples:
        //
        // FAILED
        // EXPIRED
        // UNKNOWN
        //
        // We cannot safely pretend the Checkout creation finalization
        // succeeded locally.
        // ============================================================

        log.error(
                "CRITICAL markAttemptOpen CAS lost unexpectedly. " +
                        "attemptId={} currentStatus={} " +
                        "storedProviderCheckoutId={} incomingProviderCheckoutId={}",
                attemptId,
                current.getStatus(),
                current.getProviderCheckoutId(),
                providerCheckoutId
        );


        throw new IllegalStateException(
                "Payment attempt can no longer transition to OPEN"
        );
    }


    // =========================================================
    // Ambiguous Provider Outcome
    // =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt markAttemptUnknown(UUID attemptId, String failureReason) {

        LocalDateTime now = LocalDateTime.now();

        attemptRepository.markUnknown(
                attemptId,
                PaymentAttemptStatus.INITIALIZING,
                PaymentAttemptStatus.UNKNOWN,
                failureReason,
                now
        );

        PaymentAttempt current = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new RecordNotFoundException("Payment attempt not found: " + attemptId));

        // CAS may lose because reconciliation/provider truth
        // already moved the attempt forward.
        // Never overwrite a newer provider truth.
        if (current.getStatus() == PaymentAttemptStatus.INITIALIZING) {

            throw new IllegalStateException("Attempt remained INITIALIZING after UNKNOWN CAS");
        }

        return current;
    }


    // =========================================================
    // Definitive Provider Failure
    // =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt markAttemptFailed(UUID attemptId, String failureReason) {

        LocalDateTime now = LocalDateTime.now();

        int updated = attemptRepository.markFailed(
                attemptId,
                PaymentAttemptStatus.INITIALIZING,
                PaymentAttemptStatus.FAILED,
                failureReason,
                now,
                now
        );

        PaymentAttempt current = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new RecordNotFoundException("Payment attempt not found: " + attemptId));

        if (updated == 1 || current.getStatus() == PaymentAttemptStatus.FAILED) {

            return current;
        }

        log.error(
                "CRITICAL definitive provider failure could not transition attempt. "
                        + "attemptId={}, currentStatus={}",
                attemptId,
                current.getStatus()
        );

        throw new IllegalStateException(
                "Payment attempt is no longer eligible for FAILED transition"
        );
    }

    // =========================================================
// Verified Provider Financial Success
// =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentSuccessResult markPaymentSucceededFromWebhook(
            UUID paymentId,
            UUID attemptId,
            UUID bookingId,
            String providerCheckoutId,
            String providerPaymentId,
            long providerAmountTotal,
            String providerCurrency,
            LocalDateTime succeededAt
    ) {

        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(succeededAt, "succeededAt must not be null");

        if (providerCheckoutId == null || providerCheckoutId.isBlank()) {
            throw new IllegalArgumentException(
                    "providerCheckoutId must not be blank"
            );
        }

        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "providerPaymentId must not be blank"
            );
        }


        // ========================================================
        // 1. Lock logical Payment
        //
        // Every financial-success decision for this Payment is now
        // serialized until this transaction commits.
        // ========================================================

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new RecordNotFoundException("Payment not found: " + paymentId)
        );


        // ========================================================
        // 2. Validate Booking correlation
        // ========================================================

        if (!payment.getBookingId().equals(bookingId)) {

            log.error(
                    "CRITICAL webhook booking mismatch. " +
                            "paymentId={} storedBookingId={} incomingBookingId={}",
                    paymentId,
                    payment.getBookingId(),
                    bookingId
            );

            throw new IllegalStateException(
                    "Webhook booking does not match Payment"
            );
        }

        // ========================================================
        // Validate Stripe financial amount + currency
        //
        // Signed webhook proves that Stripe sent the event.
        //
        // It does NOT mean we should blindly accept whatever
        // amount/currency appears in it.
        //
        // Our immutable Payment snapshot remains authoritative.
        // ========================================================

        if (providerAmountTotal <= 0) {

            log.error(
                    "CRITICAL Stripe webhook contains invalid amount. " +
                            "paymentId={} providerAmountTotal={}",
                    paymentId,
                    providerAmountTotal
            );

            throw new IllegalStateException(
                    "Stripe webhook contains invalid payment amount"
            );
        }


        if (providerCurrency == null || providerCurrency.isBlank()) {

            log.error(
                    "CRITICAL Stripe webhook contains no currency. paymentId={}",
                    paymentId
            );

            throw new IllegalStateException(
                    "Stripe webhook contains no payment currency"
            );
        }


        // ========================================================
        // Convert our authoritative Payment amount into the exact
        // integer representation used by Stripe.
        //
        // Example:
        //
        // Payment.amount = 150.25 USD
        //
        // expectedMinorUnits = 15025
        // ========================================================

        long expectedAmountTotal =
                stripeAmountConverter.toMinorUnits(
                        payment.getAmount(),
                        payment.getCurrency()
                );


        // ========================================================
        // Normalize currencies using the SAME Stripe rules.
        //
        // Payment: "USD" -> "usd"
        // Stripe : "usd" -> "usd"
        // ========================================================

        String expectedCurrency =
                stripeAmountConverter.normalizeCurrency(
                        payment.getCurrency()
                );

        String actualCurrency =
                stripeAmountConverter.normalizeCurrency(
                        providerCurrency
                );


        // ========================================================
        // Amount MUST match exactly.
        //
        // NEVER tolerate:
        // 10000 vs 9999
        // 10000 vs 10001
        //
        // Money comparison is exact.
        // ========================================================

        if (expectedAmountTotal != providerAmountTotal) {

            log.error(
                    "CRITICAL Stripe payment amount mismatch. " +
                            "paymentId={} expectedMinorUnits={} actualMinorUnits={}",
                    paymentId,
                    expectedAmountTotal,
                    providerAmountTotal
            );

            throw new IllegalStateException(
                    "Stripe payment amount does not match Payment snapshot"
            );
        }


        // ========================================================
        // Currency MUST match exactly after normalization.
        // ========================================================

        if (!expectedCurrency.equals(actualCurrency)) {

            log.error(
                    "CRITICAL Stripe payment currency mismatch. " +
                            "paymentId={} expectedCurrency={} actualCurrency={}",
                    paymentId,
                    expectedCurrency,
                    actualCurrency
            );

            throw new IllegalStateException(
                    "Stripe payment currency does not match Payment snapshot"
            );
        }


        // ========================================================
        // 3. Load Attempt
        // ========================================================

        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new RecordNotFoundException("Payment attempt not found: " + attemptId));


        // ========================================================
        // 4. Attempt must belong to this Payment
        // ========================================================

        assertAttemptBelongsToPayment(attempt, payment);


        // ========================================================
        // 5. Sprint 5 supports Stripe webhook financial truth
        // ========================================================

        if (attempt.getProvider() != PaymentProvider.STRIPE) {

            log.error(
                    "CRITICAL Stripe webhook correlated to non-Stripe attempt. " +
                            "paymentId={} attemptId={} provider={}",
                    paymentId,
                    attemptId,
                    attempt.getProvider()
            );

            throw new IllegalStateException(
                    "Stripe webhook does not match PaymentAttempt provider"
            );
        }


        // ========================================================
        // 6. Validate provider Checkout identity
        //
        // Session ID is our strongest provider correlation.
        //
        // If local value is already present, it MUST match.
        // If missing because webhook won the race against markAttemptOpen(),
        // we may safely fill it from the verified webhook.
        // ========================================================

        if (attempt.getProviderCheckoutId() != null && !Objects.equals(attempt.getProviderCheckoutId(), providerCheckoutId)) {

            log.error(
                    "CRITICAL providerCheckoutId mismatch. " +
                            "paymentId={} attemptId={} stored={} incoming={}",
                    paymentId,
                    attemptId,
                    attempt.getProviderCheckoutId(),
                    providerCheckoutId
            );

            throw new IllegalStateException(
                    "Webhook Checkout Session does not match PaymentAttempt"
            );
        }


        // ========================================================
        // 7. Validate provider financial object identity
        // ========================================================

        if (attempt.getProviderPaymentId() != null && !Objects.equals(attempt.getProviderPaymentId(), providerPaymentId)) {

            log.error(
                    "CRITICAL providerPaymentId mismatch. " +
                            "paymentId={} attemptId={} stored={} incoming={}",
                    paymentId,
                    attemptId,
                    attempt.getProviderPaymentId(),
                    providerPaymentId
            );

            throw new IllegalStateException(
                    "Webhook PaymentIntent does not match PaymentAttempt"
            );
        }


        // ========================================================
        // 8. Persist provider references if webhook arrived first
        // ========================================================

        if (attempt.getProviderCheckoutId() == null) {
            attempt.setProviderCheckoutId(providerCheckoutId);
        }

        if (attempt.getProviderPaymentId() == null) {
            attempt.setProviderPaymentId(providerPaymentId);
        }


        // ========================================================
        // 9. Stripe financial truth wins
        //
        // The signed + validated provider webhook says money moved.
        //
        // Local unresolved/stale states must not contradict
        // provider financial truth.
        // ========================================================

        if (attempt.getStatus() != PaymentAttemptStatus.SUCCEEDED) {

            log.info(
                    "Provider confirmed payment success. " + "paymentId={} attemptId={} previousStatus={}", paymentId, attemptId, attempt.getStatus());

            attempt.setStatus(PaymentAttemptStatus.SUCCEEDED
            );

            attempt.setFailureReason(null);

            attempt.setResolvedAt(succeededAt);

            attemptRepository.saveAndFlush(attempt);
        }


        // ========================================================
        // 10. Decide canonical financial success
        //
        // Payment row lock guarantees that only one transaction
        // can make this decision at a time.
        // ========================================================

        UUID canonicalAttemptId =
                payment.getSucceededAttemptId();


        // ========================================================
        // FIRST SUCCESS
        //
        // Payment:
        // PENDING -> SUCCEEDED
        //
        // This Attempt becomes canonical.
        // ========================================================

        if (payment.getStatus() == PaymentStatus.PENDING) {

            if (canonicalAttemptId != null) {

                log.error(
                        "CRITICAL PENDING Payment already has succeededAttemptId. " +
                                "paymentId={} succeededAttemptId={}",
                        paymentId,
                        canonicalAttemptId
                );

                throw new IllegalStateException(
                        "PENDING Payment already has canonical successful attempt"
                );
            }


            payment.setStatus(PaymentStatus.SUCCEEDED);

            payment.setSucceededAttemptId(attemptId);

            payment.setSucceededAt(succeededAt);

            payment.setBookingConfirmationStatus(BookingConfirmationStatus.PENDING);

            payment.setBookingConfirmationLastError(null);


            paymentRepository.saveAndFlush(payment);


            log.info("Payment succeeded with canonical attempt. " + "paymentId={} attemptId={} bookingId={}", paymentId, attemptId, bookingId);


            return new PaymentSuccessResult(paymentId, bookingId, attemptId, true, true);
        }


        // ========================================================
        // IDEMPOTENT SUCCESS REPLAY
        //
        // Same canonical Attempt delivered again.
        // ========================================================

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {

            if (canonicalAttemptId == null) {

                log.error("CRITICAL SUCCEEDED Payment has no succeededAttemptId. " + "paymentId={}", paymentId);

                throw new IllegalStateException("SUCCEEDED Payment has no canonical successful attempt");
            }


            if (canonicalAttemptId.equals(attemptId)) {

                log.debug(
                        "Idempotent webhook replay for canonical successful attempt. " + "paymentId={} attemptId={}", paymentId, attemptId);


                return new PaymentSuccessResult(paymentId, bookingId, attemptId, true, true);
            }


            // ====================================================
            // SECOND REAL FINANCIAL SUCCESS
            //
            // Important:
            //
            // Do NOT overwrite succeededAttemptId.
            // Do NOT mark this Attempt FAILED.
            //
            // Stripe says this Attempt also received money.
            //
            // Later Phase 11:
            // technical refund for this non-canonical success.
            // ====================================================

            log.error(
                    "CRITICAL DUPLICATE FINANCIAL SUCCESS. " +
                            "paymentId={} canonicalAttemptId={} incomingAttemptId={}",
                    paymentId,
                    canonicalAttemptId,
                    attemptId
            );


            return new PaymentSuccessResult(paymentId, bookingId, attemptId, false, false);

        }


        // ========================================================
        // REFUNDED Payment
        //
        // We do not rewrite historical payment state here.
        // Refund-specific behavior will be finalized in Phase 11.
        // ========================================================

        if (payment.getStatus() == PaymentStatus.REFUNDED) {

            log.error(
                    "CRITICAL provider success received for already REFUNDED Payment. " +
                            "paymentId={} attemptId={} canonicalAttemptId={}",
                    paymentId,
                    attemptId,
                    canonicalAttemptId
            );

            /*
             * Financial truth for the Attempt was already persisted above.
             *
             * Do not change Payment back to SUCCEEDED.
             */

            boolean canonical = Objects.equals(canonicalAttemptId, attemptId);

            return new PaymentSuccessResult(
                    paymentId,
                    bookingId,
                    attemptId,
                    canonical,
                    false
            );
        }


        throw new IllegalStateException(
                "Unsupported Payment status during webhook success: "
                        + payment.getStatus()
        );
    }


// =========================================================
// Provider-confirmed Checkout Expiration
// =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt markAttemptExpiredFromWebhook(
            UUID attemptId,
            String providerCheckoutId,
            LocalDateTime expiredAt
    ) {

        Objects.requireNonNull(attemptId, "attemptId must not be null");

        Objects.requireNonNull(expiredAt, "expiredAt must not be null");

        if (providerCheckoutId == null || providerCheckoutId.isBlank()) {

            throw new IllegalArgumentException(
                    "providerCheckoutId must not be blank"
            );
        }


        // ========================================================
        // 1. Initial lookup only to discover parent Payment
        // ========================================================

        PaymentAttempt initialAttempt =
                attemptRepository.findById(attemptId)
                        .orElseThrow(() ->new RecordNotFoundException("Payment attempt not found: " + attemptId
                                )
                        );


        UUID paymentId = initialAttempt.getPayment().getId();


        // ========================================================
        // 2. Lock parent Payment
        //
        // Same lock ordering as financial-success handling.
        // ========================================================

        paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new RecordNotFoundException("Payment not found: " + paymentId));


        // Reload after acquiring Payment lock.
        PaymentAttempt attempt = attemptRepository.findById(attemptId)
                        .orElseThrow(() -> new RecordNotFoundException("Payment attempt not found: " + attemptId));


        // ========================================================
        // 3. Provider must be Stripe
        // ========================================================

        if (attempt.getProvider() != PaymentProvider.STRIPE) {

            throw new IllegalStateException("Stripe expiration webhook does not match attempt provider");}


        // ========================================================
        // 4. Validate Checkout Session correlation
        // ========================================================

        if (attempt.getProviderCheckoutId() != null && !Objects.equals(attempt.getProviderCheckoutId(), providerCheckoutId)) {

            log.error("CRITICAL checkout expiration session mismatch. " + "attemptId={} stored={} incoming={}",
                    attemptId,
                    attempt.getProviderCheckoutId(),
                    providerCheckoutId
            );

            throw new IllegalStateException("Expired Checkout Session does not match PaymentAttempt");
        }


        /*
         * Webhook may beat markAttemptOpen().
         */
        if (attempt.getProviderCheckoutId() == null) {
            attempt.setProviderCheckoutId(providerCheckoutId);
        }


        // ========================================================
        // 5. Financial success is stronger than expiration.
        //
        // NEVER:
        //
        // SUCCEEDED -> EXPIRED
        // ========================================================

        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {

            log.warn(
                    "Ignoring checkout expiration because Attempt already SUCCEEDED. " +
                            "attemptId={}",
                    attemptId
            );

            return attempt;
        }


        // ========================================================
        // 6. Idempotent replay
        // ========================================================

        if (attempt.getStatus() == PaymentAttemptStatus.EXPIRED) {
            return attempt;
        }


        // ========================================================
        // 7. Valid unresolved states
        //
        // INITIALIZING:
        // webhook may have won before markAttemptOpen()
        //
        // OPEN:
        // normal expiration
        //
        // UNKNOWN:
        // provider truth resolves previously ambiguous state
        // ========================================================

        if (attempt.getStatus() != PaymentAttemptStatus.INITIALIZING && attempt.getStatus() != PaymentAttemptStatus.OPEN
                && attempt.getStatus() != PaymentAttemptStatus.UNKNOWN) {

            log.error(
                    "CRITICAL attempt cannot transition to EXPIRED. " +
                            "attemptId={} status={}", attemptId, attempt.getStatus());

            throw new IllegalStateException(
                    "PaymentAttempt is not eligible for EXPIRED transition"
            );
        }


        attempt.setStatus(PaymentAttemptStatus.EXPIRED);

        attempt.setFailureReason(null);

        attempt.setResolvedAt(expiredAt);

        return attemptRepository.saveAndFlush(attempt
        );
    }

    // =========================================================
// Booking Confirmation — CONFIRMED
// =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markBookingConfirmed(UUID paymentId) {

        Objects.requireNonNull(
                paymentId,
                "paymentId must not be null"
        );

        LocalDateTime now = LocalDateTime.now();


        // ========================================================
        // 1. Serialize Booking synchronization decisions
        // ========================================================

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new RecordNotFoundException("Payment not found: " + paymentId));


        // ========================================================
        // 2. Idempotent replay
        //
        // Booking confirmation endpoint itself is idempotent.
        // Our local finalization must be idempotent as well.
        // ========================================================

        if (payment.getBookingConfirmationStatus() == BookingConfirmationStatus.CONFIRMED) {

            return payment;
        }


        // ========================================================
        // 3. Never reverse a definitive rejection
        //
        // REJECTED means Booking definitively refused confirmation
        // and Phase 11 owns technical compensation.
        // ========================================================

        if (payment.getBookingConfirmationStatus() == BookingConfirmationStatus.REJECTED) {

            log.error(
                    "CRITICAL: Cannot mark Booking CONFIRMED because " +
                            "Payment confirmation is already REJECTED. " +
                            "paymentId={}",
                    paymentId
            );

            throw new IllegalStateException(
                    "Rejected Booking confirmation cannot transition to CONFIRMED"
            );
        }


        // ========================================================
        // 4. Financial success must already be durable
        //
        // Booking synchronization MUST NEVER create financial truth.
        // ========================================================

        assertPaymentSucceededForBookingConfirmation(payment);


        // ========================================================
        // 5. Only PENDING may become CONFIRMED
        // ========================================================

        if (payment.getBookingConfirmationStatus() != BookingConfirmationStatus.PENDING) {

            log.error(
                    "CRITICAL: Payment has invalid Booking confirmation " +
                            "state for CONFIRMED transition. " +
                            "paymentId={} confirmationStatus={}",
                    paymentId,
                    payment.getBookingConfirmationStatus()
            );

            throw new IllegalStateException(
                    "Booking confirmation is not eligible for CONFIRMED transition"
            );
        }


        payment.setBookingConfirmationStatus(BookingConfirmationStatus.CONFIRMED);

        payment.setBookingConfirmedAt(now);

        payment.setBookingConfirmationLastError(null);


        Payment saved = paymentRepository.saveAndFlush(payment);


        log.info(
                "Booking confirmation persisted successfully. " +
                        "paymentId={} bookingId={}",
                saved.getId(),
                saved.getBookingId()
        );


        return saved;
    }


// =========================================================
// Booking Confirmation — REJECTED
// =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markBookingRejected(
            UUID paymentId,
            String reason
    ) {

        Objects.requireNonNull(
                paymentId,
                "paymentId must not be null"
        );


        String safeReason =
                sanitizeBookingConfirmationError(
                        reason
                );


        // ========================================================
        // 1. Serialize synchronization decision
        // ========================================================

        Payment payment =
                paymentRepository
                        .findByIdForUpdate(paymentId)
                        .orElseThrow(() ->
                                new RecordNotFoundException(
                                        "Payment not found: " + paymentId
                                )
                        );


        // ========================================================
        // 2. Idempotent rejection replay
        //
        // Do not modify financial truth.
        // ========================================================

        if (payment.getBookingConfirmationStatus() == BookingConfirmationStatus.REJECTED) {

            return payment;
        }


        // ========================================================
        // 3. CONFIRMED is terminal from synchronization perspective
        //
        // A later error response must NEVER undo a successfully
        // confirmed Booking.
        // ========================================================

        if (payment.getBookingConfirmationStatus() == BookingConfirmationStatus.CONFIRMED) {

            log.error(
                    "CRITICAL: Cannot reject already CONFIRMED Booking. " +
                            "paymentId={} bookingId={}",
                    paymentId,
                    payment.getBookingId()
            );

            throw new IllegalStateException(
                    "Confirmed Booking cannot transition to REJECTED"
            );
        }


        // ========================================================
        // 4. Money must already have succeeded
        // ========================================================

        assertPaymentSucceededForBookingConfirmation(payment);

        // ========================================================
        // 5. Only PENDING -> REJECTED
        // ========================================================

        if (payment.getBookingConfirmationStatus() != BookingConfirmationStatus.PENDING) {

            log.error(
                    "CRITICAL: Payment has invalid Booking confirmation " +
                            "state for rejection. " +
                            "paymentId={} confirmationStatus={}",
                    paymentId,
                    payment.getBookingConfirmationStatus()
            );

            throw new IllegalStateException(
                    "Booking confirmation is not eligible for REJECTED transition"
            );
        }


        payment.setBookingConfirmationStatus(BookingConfirmationStatus.REJECTED);

        payment.setBookingConfirmationLastError(safeReason);

        /*
         * Booking was NOT confirmed.
         */
        payment.setBookingConfirmedAt(null);


        Payment saved = paymentRepository.saveAndFlush(payment);


        log.warn(
                "Booking confirmation definitively rejected. " +
                        "paymentId={} bookingId={} reason={}",
                saved.getId(),
                saved.getBookingId(),
                safeReason
        );


        return saved;
    }


// =========================================================
// Booking Confirmation — AMBIGUOUS / RETRYABLE ERROR
// =========================================================

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment recordBookingConfirmationError(UUID paymentId, String error) {

        Objects.requireNonNull(paymentId, "paymentId must not be null");


        String safeError = sanitizeBookingConfirmationError(error);


        // ========================================================
        // 1. Serialize synchronization updates
        // ========================================================

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new RecordNotFoundException("Payment not found: " + paymentId));


        // ========================================================
        // 2. Terminal synchronization truth wins
        //
        // A delayed timeout/error must NEVER overwrite:
        //
        // CONFIRMED
        // REJECTED
        //
        // with operational error metadata.
        // ========================================================

        if (payment.getBookingConfirmationStatus() == BookingConfirmationStatus.CONFIRMED
                || payment.getBookingConfirmationStatus() == BookingConfirmationStatus.REJECTED) {

            log.debug(
                    "Ignoring stale Booking confirmation error because " +
                            "confirmation is already terminal. " +
                            "paymentId={} confirmationStatus={}",
                    paymentId,
                    payment.getBookingConfirmationStatus()
            );

            return payment;
        }


        // ========================================================
        // 3. Money must already have succeeded
        // ========================================================

        assertPaymentSucceededForBookingConfirmation(payment);


        // ========================================================
        // 4. Ambiguous outcome MUST remain PENDING
        //
        // We do not know whether Booking processed the command.
        //
        // Therefore NEVER:
        //
        // PENDING -> REJECTED
        //
        // Reconciliation will retry the same idempotent command.
        // ========================================================

        if (payment.getBookingConfirmationStatus() != BookingConfirmationStatus.PENDING) {

            log.error(
                    "CRITICAL: Payment has invalid Booking confirmation " +
                            "state for ambiguous error recording. " +
                            "paymentId={} confirmationStatus={}",
                    paymentId,
                    payment.getBookingConfirmationStatus()
            );

            throw new IllegalStateException("Booking confirmation is not in PENDING state");
        }


        payment.setBookingConfirmationLastError(safeError);


        Payment saved = paymentRepository.saveAndFlush(payment);


        log.warn(
                "Booking confirmation outcome remains ambiguous. " +
                        "paymentId={} bookingId={} error={}",
                saved.getId(),
                saved.getBookingId(),
                safeError
        );


        return saved;
    }

    // =========================================================
// Booking Confirmation Guards
// =========================================================

    private void assertPaymentSucceededForBookingConfirmation(Payment payment) {

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {

            log.error(
                    "CRITICAL: Booking confirmation operation attempted " +
                            "for non-SUCCEEDED Payment. " +
                            "paymentId={} paymentStatus={} " +
                            "confirmationStatus={}",
                    payment.getId(),
                    payment.getStatus(),
                    payment.getBookingConfirmationStatus()
            );

            throw new IllegalStateException(
                    "Booking confirmation requires a SUCCEEDED Payment"
            );
        }


        if (payment.getSucceededAttemptId() == null) {

            log.error(
                    "CRITICAL: SUCCEEDED Payment has no canonical " +
                            "succeededAttemptId during Booking confirmation. " +
                            "paymentId={}",
                    payment.getId()
            );

            throw new IllegalStateException(
                    "SUCCEEDED Payment has no canonical successful attempt"
            );
        }
    }


    private String sanitizeBookingConfirmationError(String error) {

        if (error == null || error.isBlank()) {
            return "Booking confirmation failed";
        }


        String normalized = error.trim();


        /*
         * Payment.bookingConfirmationLastError
         * has length = 500.
         */
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}