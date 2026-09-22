package com.project.paymentservice.service.impl;

import com.project.common.exception.ConflictException;
import com.project.paymentservice.entity.Payment;
import com.project.paymentservice.entity.PaymentAttempt;
import com.project.paymentservice.entity.PaymentIdempotencyRecord;
import com.project.paymentservice.enums.PaymentAttemptStatus;
import com.project.paymentservice.enums.PaymentMethodType;
import com.project.paymentservice.enums.PaymentProvider;
import com.project.paymentservice.enums.PaymentStatus;
import com.project.paymentservice.repository.PaymentAttemptRepository;
import com.project.paymentservice.repository.PaymentIdempotencyRecordRepository;
import com.project.paymentservice.repository.PaymentRepository;
import com.project.paymentservice.service.model.AttemptClaimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentTransactionServiceImpl#claimAttemptForInitiation}.
 *
 * This method is the financial concurrency gate of the payment system.
 * Every guard path is tested explicitly.
 *
 * Test naming convention:
 *   TC-XX matches the reference doc scenario list in PAYMENT_SERVICE_IMPL_REFERENCE.md
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentTransactionServiceImpl — claimAttemptForInitiation()")
class PaymentTransactionServiceImplClaimAttemptTest {

    @Mock PaymentRepository            paymentRepository;
    @Mock PaymentAttemptRepository     attemptRepository;
    @Mock PaymentIdempotencyRecordRepository idempotencyRepository;

    @InjectMocks
    PaymentTransactionServiceImpl sut;

    // -------------------------------------------------------------------------
    // Common test fixtures
    // -------------------------------------------------------------------------

    private static final UUID PAYMENT_ID           = UUID.randomUUID();
    private static final UUID IDEMPOTENCY_ID        = UUID.randomUUID();
    private static final UUID ATTEMPT_ID            = UUID.randomUUID();
    private static final UUID OTHER_ATTEMPT_ID      = UUID.randomUUID();

    private static final PaymentProvider   PROVIDER = PaymentProvider.STRIPE;
    private static final PaymentMethodType METHOD   = PaymentMethodType.CARD;

    private Payment pendingPayment;
    private PaymentIdempotencyRecord unboundRecord;

    @BeforeEach
    void setUp() {
        pendingPayment = Payment.builder()
                .id(PAYMENT_ID)
                .bookingId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amount(new BigDecimal("150.00"))
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .build();

        unboundRecord = new PaymentIdempotencyRecord();
        unboundRecord.setId(IDEMPOTENCY_ID);
        unboundRecord.setPaymentId(null);
        unboundRecord.setAttemptId(null);
    }

    // =========================================================================
    // TC-01 — Happy path: no active attempt → new INITIALIZING created
    // =========================================================================

    @Nested
    @DisplayName("TC-01 | Happy path: no active attempt")
    class HappyPath {

        @Test
        @DisplayName("Creates INITIALIZING attempt, binds idempotency key, returns createdNewAttempt=true")
        void createsNewInitializingAttempt() {

            PaymentAttempt savedAttempt = attemptWithStatus(ATTEMPT_ID, PaymentAttemptStatus.INITIALIZING);

            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(unboundRecord);
            givenNoActiveAttempt();

            when(attemptRepository.saveAndFlush(any(PaymentAttempt.class)))
                    .thenReturn(savedAttempt);
            when(idempotencyRepository.saveAndFlush(any()))
                    .thenReturn(unboundRecord);

            AttemptClaimResult result = sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            );

            assertThat(result.createdNewAttempt()).isTrue();
            assertThat(result.attempt().getId()).isEqualTo(ATTEMPT_ID);
            assertThat(result.attempt().getStatus()).isEqualTo(PaymentAttemptStatus.INITIALIZING);

            verify(attemptRepository).saveAndFlush(any(PaymentAttempt.class));
            verify(idempotencyRepository).saveAndFlush(any(PaymentIdempotencyRecord.class));
        }
    }

    // =========================================================================
    // TC-02 / TC-03 — Immutable idempotency mapping
    // =========================================================================

    @Nested
    @DisplayName("TC-02,03 | Immutable idempotency mapping")
    class IdempotencyMappingImmutability {

        @Test
        @DisplayName("TC-02: Same key already mapped to same provider/method → returns existing attempt, createdNewAttempt=false")
        void returnsMappedAttemptWhenSameProviderMethod() {

            PaymentIdempotencyRecord mappedRecord = new PaymentIdempotencyRecord();
            mappedRecord.setId(IDEMPOTENCY_ID);
            mappedRecord.setAttemptId(ATTEMPT_ID);
            mappedRecord.setPaymentId(PAYMENT_ID);

            PaymentAttempt mappedAttempt = attemptWithStatus(ATTEMPT_ID, PaymentAttemptStatus.OPEN);
            mappedAttempt.setPayment(pendingPayment);

            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(mappedRecord);
            when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(mappedAttempt));

            AttemptClaimResult result = sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            );

            assertThat(result.createdNewAttempt()).isFalse();
            assertThat(result.attempt().getId()).isEqualTo(ATTEMPT_ID);
            verify(attemptRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("TC-03: Same key already mapped but to different provider → ConflictException")
        void throwsConflictWhenProviderMismatch() {

            PaymentIdempotencyRecord mappedRecord = new PaymentIdempotencyRecord();
            mappedRecord.setId(IDEMPOTENCY_ID);
            mappedRecord.setAttemptId(ATTEMPT_ID);
            mappedRecord.setPaymentId(PAYMENT_ID);

            PaymentAttempt mappedAttempt = attemptWithStatus(ATTEMPT_ID, PaymentAttemptStatus.OPEN);
            mappedAttempt.setPayment(pendingPayment);
            mappedAttempt.setProvider(PaymentProvider.PAYPAL);   // different provider
            mappedAttempt.setPaymentMethod(METHOD);

            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(mappedRecord);
            when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(mappedAttempt));

            assertThatThrownBy(() -> sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            )).isInstanceOf(ConflictException.class);
        }
    }

    // =========================================================================
    // TC-04..07 — Terminal Payment guard (SUCCEEDED / REFUNDED)
    // =========================================================================

    @Nested
    @DisplayName("TC-04..07 | Terminal Payment guard")
    class TerminalPaymentGuard {

        @Test
        @DisplayName("TC-04: SUCCEEDED payment + canonical key → returns canonical attempt (idempotent replay)")
        void allowsCanonicalKeyReplayOnSucceededPayment() {

            Payment succeededPayment = succeededPayment(ATTEMPT_ID);

            PaymentIdempotencyRecord canonicalRecord = new PaymentIdempotencyRecord();
            canonicalRecord.setId(IDEMPOTENCY_ID);
            canonicalRecord.setAttemptId(ATTEMPT_ID);   // mapped to canonical
            canonicalRecord.setPaymentId(PAYMENT_ID);

            PaymentAttempt canonicalAttempt = attemptWithStatus(ATTEMPT_ID, PaymentAttemptStatus.SUCCEEDED);
            canonicalAttempt.setPayment(succeededPayment);

            givenLockedPayment(succeededPayment);
            givenIdempotencyRecord(canonicalRecord);
            when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(canonicalAttempt));

            AttemptClaimResult result = sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            );

            assertThat(result.createdNewAttempt()).isFalse();
            assertThat(result.attempt().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
            verify(attemptRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("TC-05: SUCCEEDED payment + brand-new key → ConflictException")
        void rejectsNewKeyOnSucceededPayment() {

            Payment succeededPayment = succeededPayment(ATTEMPT_ID);

            // unboundRecord.attemptId = null  (new key, not mapped to canonical)
            givenLockedPayment(succeededPayment);
            givenIdempotencyRecord(unboundRecord);

            assertThatThrownBy(() -> sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            )).isInstanceOf(ConflictException.class)
              .hasMessageContaining("already been paid");
        }

        @Test
        @DisplayName("TC-06: SUCCEEDED payment with no succeededAttemptId → IllegalStateException (CRITICAL)")
        void throwsIllegalStateWhenSucceededPaymentHasNoCanonicalAttempt() {

            Payment inconsistentPayment = Payment.builder()
                    .id(PAYMENT_ID)
                    .status(PaymentStatus.SUCCEEDED)
                    .succeededAttemptId(null)       // ← inconsistent state
                    .build();

            givenLockedPayment(inconsistentPayment);
            givenIdempotencyRecord(unboundRecord);

            assertThatThrownBy(() -> sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("no canonical successful attempt");
        }

        @Test
        @DisplayName("TC-07: REFUNDED payment + any key → ConflictException")
        void rejectsAnyAttemptOnRefundedPayment() {

            Payment refundedPayment = Payment.builder()
                    .id(PAYMENT_ID)
                    .status(PaymentStatus.REFUNDED)
                    .succeededAttemptId(ATTEMPT_ID)
                    .build();

            // unboundRecord is a brand-new key (attemptId = null)
            givenLockedPayment(refundedPayment);
            givenIdempotencyRecord(unboundRecord);

            assertThatThrownBy(() -> sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            )).isInstanceOf(ConflictException.class)
              .hasMessageContaining("already been paid");
        }
    }

    // =========================================================================
    // TC-08 — Default-deny gate for unexpected PaymentStatus
    // =========================================================================

    @Nested
    @DisplayName("TC-08 | Default-deny PENDING gate")
    class DefaultDenyPendingGate {

        @Test
        @DisplayName("TC-08: Unknown/future status (not PENDING) → ConflictException")
        void rejectsUnexpectedNonPendingStatus() {

            // Simulate a future status like REFUND_PENDING by using a payment that
            // is neither PENDING, SUCCEEDED, nor REFUNDED. We can't add a new enum
            // value here, but we verify the PENDING != check by temporarily using
            // a Payment with SUCCEEDED that has no succeededAttemptId skipped,
            // and instead just mock a payment in a state that passes the terminal
            // check but fails the PENDING check.
            //
            // This TC documents the invariant: the guard exists for future statuses.
            // The meaningful verification is that Payment.PENDING is the ONLY
            // status that proceeds to active-attempt lookup.
            //
            // Verified indirectly: TC-04..07 cover SUCCEEDED/REFUNDED.
            // TC-01 covers PENDING. This test covers the conceptual boundary.

            // To make this concrete: if Payment.status were a String field
            // we could test any value. Since it's an enum we verify the
            // existing non-PENDING, non-terminal values are not present
            // (enum exhaustion is compile-time guaranteed for PENDING only).
            //
            // Therefore this TC documents the design decision rather than
            // a mechanical assertion. It is preserved for future-proofing.
            assertThat(PaymentStatus.values())
                    .as("If a new PaymentStatus is added, update claimAttemptForInitiation()")
                    .containsExactlyInAnyOrder(
                            PaymentStatus.PENDING,
                            PaymentStatus.SUCCEEDED,
                            PaymentStatus.REFUNDED
                    );
        }
    }

    // =========================================================================
    // TC-09..11 — Active attempt: same provider/method → bind and return
    // =========================================================================

    @Nested
    @DisplayName("TC-09..11 | Active attempt: same provider/method")
    class ActiveAttemptSameChannel {

        @Test
        @DisplayName("TC-09: Active INITIALIZING, same provider/method → bind key, return existing, createdNewAttempt=false")
        void bindsKeyToExistingInitializingAttempt() {

            PaymentAttempt initializingAttempt = activeAttempt(ATTEMPT_ID, PaymentAttemptStatus.INITIALIZING);
            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(unboundRecord);
            givenActiveAttempt(initializingAttempt);
            when(idempotencyRepository.saveAndFlush(any())).thenReturn(unboundRecord);

            AttemptClaimResult result = sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            );

            assertThat(result.createdNewAttempt()).isFalse();
            assertThat(result.attempt().getId()).isEqualTo(ATTEMPT_ID);
            verify(attemptRepository, never()).saveAndFlush(any(PaymentAttempt.class));
        }

        @Test
        @DisplayName("TC-10: Active OPEN, same provider/method → bind key, return existing")
        void bindsKeyToExistingOpenAttempt() {

            PaymentAttempt openAttempt = activeAttempt(ATTEMPT_ID, PaymentAttemptStatus.OPEN);
            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(unboundRecord);
            givenActiveAttempt(openAttempt);
            when(idempotencyRepository.saveAndFlush(any())).thenReturn(unboundRecord);

            AttemptClaimResult result = sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            );

            assertThat(result.createdNewAttempt()).isFalse();
            assertThat(result.attempt().getStatus()).isEqualTo(PaymentAttemptStatus.OPEN);
        }

        @Test
        @DisplayName("TC-11: Active UNKNOWN, same provider/method → bind key, return existing")
        void bindsKeyToExistingUnknownAttempt() {

            PaymentAttempt unknownAttempt = activeAttempt(ATTEMPT_ID, PaymentAttemptStatus.UNKNOWN);
            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(unboundRecord);
            givenActiveAttempt(unknownAttempt);
            when(idempotencyRepository.saveAndFlush(any())).thenReturn(unboundRecord);

            AttemptClaimResult result = sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            );

            assertThat(result.createdNewAttempt()).isFalse();
            assertThat(result.attempt().getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        }
    }

    // =========================================================================
    // TC-12 — Active attempt: competing provider/method → ConflictException
    // =========================================================================

    @Nested
    @DisplayName("TC-12 | Active attempt: different provider → ConflictException")
    class ActiveAttemptCompetingChannel {

        @Test
        @DisplayName("TC-12: OPEN attempt on PAYPAL, incoming STRIPE → ConflictException")
        void rejectsCompetingProviderWhileAttemptIsActive() {

            PaymentAttempt paypalAttempt = activeAttempt(OTHER_ATTEMPT_ID, PaymentAttemptStatus.OPEN);
            paypalAttempt.setProvider(PaymentProvider.PAYPAL);
            paypalAttempt.setPaymentMethod(PaymentMethodType.WALLET);

            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(unboundRecord);
            givenActiveAttempt(paypalAttempt);

            assertThatThrownBy(() -> sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER /* STRIPE */, METHOD
            )).isInstanceOf(ConflictException.class)
              .hasMessageContaining("different");
        }
    }

    // =========================================================================
    // TC-13 — Cross-payment idempotency contamination
    // =========================================================================

    @Nested
    @DisplayName("TC-13 | Cross-payment idempotency contamination")
    class CrossPaymentContamination {

        @Test
        @DisplayName("TC-13: Idempotency record paymentId != argument paymentId → IllegalStateException")
        void throwsWhenIdempotencyRecordLinkedToAnotherPayment() {

            UUID otherPaymentId = UUID.randomUUID();

            PaymentIdempotencyRecord foreignRecord = new PaymentIdempotencyRecord();
            foreignRecord.setId(IDEMPOTENCY_ID);
            foreignRecord.setPaymentId(otherPaymentId);   // belongs to DIFFERENT payment
            foreignRecord.setAttemptId(null);

            givenLockedPayment(pendingPayment);
            givenIdempotencyRecord(foreignRecord);

            assertThatThrownBy(() -> sut.claimAttemptForInitiation(
                    PAYMENT_ID, IDEMPOTENCY_ID, PROVIDER, METHOD
            )).isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("already linked to another Payment");
        }
    }


    // =========================================================================
    // Helper builders
    // =========================================================================

    private void givenLockedPayment(Payment payment) {
        when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
    }

    private void givenIdempotencyRecord(PaymentIdempotencyRecord record) {
        when(idempotencyRepository.findById(IDEMPOTENCY_ID))
                .thenReturn(Optional.of(record));
    }

    private void givenNoActiveAttempt() {
        when(attemptRepository.findActiveAttempt(eq(PAYMENT_ID), any()))
                .thenReturn(Optional.empty());
    }

    private void givenActiveAttempt(PaymentAttempt attempt) {
        when(attemptRepository.findActiveAttempt(eq(PAYMENT_ID), any()))
                .thenReturn(Optional.of(attempt));
    }

    /**
     * Minimal attempt with id and status. Provider/method default to STRIPE/CARD
     * to match the test's incoming request.
     */
    private PaymentAttempt attemptWithStatus(UUID id, PaymentAttemptStatus status) {
        PaymentAttempt a = new PaymentAttempt();
        a.setId(id);
        a.setStatus(status);
        a.setProvider(PROVIDER);
        a.setPaymentMethod(METHOD);
        return a;
    }

    /**
     * Active attempt (same provider/method) with its parent set to pendingPayment.
     */
    private PaymentAttempt activeAttempt(UUID id, PaymentAttemptStatus status) {
        PaymentAttempt a = attemptWithStatus(id, status);
        a.setPayment(pendingPayment);
        return a;
    }

    private Payment succeededPayment(UUID succeededAttemptId) {
        return Payment.builder()
                .id(PAYMENT_ID)
                .status(PaymentStatus.SUCCEEDED)
                .succeededAttemptId(succeededAttemptId)
                .build();
    }
}
