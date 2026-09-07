package com.project.bookingservice.service.impl;

import com.project.bookingservice.client.FlightClient;
import com.project.bookingservice.client.dto.SeatReservationResult;
import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.request.PassengerRequest;
import com.project.bookingservice.dto.response.BookingResponse;
import com.project.bookingservice.dto.response.CreateBookingResult;
import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.entity.BookingPassenger;
import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.enums.Currency;
import com.project.bookingservice.enums.FareClassType;
import com.project.bookingservice.exception.*;
import com.project.bookingservice.mapper.BookingMapper;
import com.project.bookingservice.repository.BookingRepository;
import com.project.bookingservice.service.BookingTransactionService;
import com.project.bookingservice.service.RequestHashService;
import com.project.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingServiceImpl.
 *
 * All external dependencies (repository, transaction service, flight client, mapper)
 * are mocked — no Spring context is loaded.
 *
 * Coverage targets:
 *  - createBooking: happy path, idempotency, flight rejection, ambiguous outcome, finalize failure
 *  - cancelBooking: happy path, already cancelled (idempotent), CANCELLING race, release failure
 *  - handleExistingBooking: hash mismatch, IN_PROGRESS, FAILED (reconciliation)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingServiceImpl Unit Tests")
class BookingServiceImplTest {

    @Mock BookingRepository bookingRepository;
    @Mock BookingTransactionService bookingTransactionService;
    @Mock RequestHashService requestHashService;
    @Mock FlightClient flightClient;
    @Mock BookingMapper bookingMapper;

    @InjectMocks BookingServiceImpl bookingService;

    // ──────────────────────────────────────────────────────────────
    // Test fixtures
    // ──────────────────────────────────────────────────────────────

    static final UUID USER_ID       = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID FLIGHT_ID     = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID FARE_CLASS_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID BOOKING_ID    = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final String IDEM_KEY    = "idem-key-001";
    static final String HASH_A      = "hash-aaa";
    static final String HASH_B      = "hash-bbb";

    @BeforeEach
    void setup() {
        // Inject @Value field since we're not loading Spring context
        ReflectionTestUtils.setField(bookingService, "bookingTtlMinutes", 30L);
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private BookingRequest aRequest() {
        return BookingRequest.builder()
                .flightId(FLIGHT_ID)
                .fareClassId(FARE_CLASS_ID)
                .passengers(List.of(
                        PassengerRequest.builder()
                                .firstName("Ahmad")
                                .lastName("Nasser")
                                .passportNumber("PA123456")
                                .dateOfBirth(LocalDate.of(1990, 1, 1))
                                .nationality("JO")
                                .build()
                ))
                .build();
    }

    private Booking aClaimWith(BookingStatus status) {
        return Booking.builder()
                .id(BOOKING_ID)
                .userId(USER_ID)
                .flightId(FLIGHT_ID)
                .fareClassId(FARE_CLASS_ID)
                .idempotencyKey(IDEM_KEY)
                .requestHash(HASH_A)
                .status(status)
                .passengerCount(1)
                .priceAtBooking(new BigDecimal("150.00"))
                .totalAmount(new BigDecimal("150.00"))
                .currency(Currency.JOD)
                .build();
    }

    private SeatReservationResult aReservationResult() {
        return new SeatReservationResult(
                FLIGHT_ID, FARE_CLASS_ID,
                "WB101", "AMM", "DXB",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(4),
                FareClassType.ECONOMY,
                new BigDecimal("150.00"), Currency.JOD,
                98
        );
    }

    private BookingResponse aBookingResponse() {
        return BookingResponse.builder()
                .id(BOOKING_ID)
                .status(BookingStatus.PENDING)
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    // CREATE BOOKING
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createBooking()")
    class CreateBooking {

        @BeforeEach
        void stubPassengerMapper() {
            // BookingServiceImpl calls bookingMapper.toPassengerEntity(p) for each passenger
            // and then booking.addPassenger(entity) which calls entity.setBooking(this).
            // We need a real (non-null) BookingPassenger to avoid NPE inside addPassenger().
            BookingPassenger stubPassenger = new BookingPassenger();
            lenient().when(bookingMapper.toPassengerEntity(any())).thenReturn(stubPassenger);
        }
        @Test
        @DisplayName("happy path — reserve succeeds → PENDING booking created")
        void whenReserveSucceeds_shouldReturnPendingBooking() {
            // Arrange
            BookingRequest req = aRequest();
            Booking claim = aClaimWith(BookingStatus.IN_PROGRESS);
            Booking finalizedBooking = aClaimWith(BookingStatus.PENDING);

            when(requestHashService.computeHash(req)).thenReturn(HASH_A);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.empty());
            when(bookingTransactionService.createClaim(any())).thenReturn(claim);
            when(flightClient.reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 1))
                    .thenReturn(aReservationResult());
            when(bookingTransactionService.finalizeBooking(any())).thenReturn(finalizedBooking);
            when(bookingMapper.toResponse(finalizedBooking)).thenReturn(aBookingResponse());

            // Act
            CreateBookingResult result = bookingService.createBooking(USER_ID, IDEM_KEY, req);

            // Assert
            assertThat(result.created()).isTrue();
            assertThat(result.booking().getStatus()).isEqualTo(BookingStatus.PENDING);
            verify(flightClient).reserveSeats(FLIGHT_ID, FARE_CLASS_ID, 1);
            verify(bookingTransactionService).finalizeBooking(any());
            verify(bookingTransactionService, never()).deleteClaim(any());
        }

        @Test
        @DisplayName("flight rejects (409 not enough seats) → claim deleted, exception rethrown")
        void whenFlightRejectsDefinitively_shouldDeleteClaimAndRethrow() {
            // Arrange
            BookingRequest req = aRequest();
            Booking claim = aClaimWith(BookingStatus.IN_PROGRESS);

            when(requestHashService.computeHash(req)).thenReturn(HASH_A);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.empty());
            when(bookingTransactionService.createClaim(any())).thenReturn(claim);
            when(flightClient.reserveSeats(any(), any(), anyInt()))
                    .thenThrow(new FlightReservationRejectedException(
                            "Not enough available seats", HttpStatus.CONFLICT));

            // Act & Assert
            assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                    .isInstanceOf(FlightReservationRejectedException.class)
                    .hasMessageContaining("Not enough available seats");

            verify(bookingTransactionService).deleteClaim(BOOKING_ID);
            verify(bookingTransactionService, never()).finalizeBooking(any());
        }

        @Test
        @DisplayName("flight service DOWN (ServiceUnavailableException) → claim marked FAILED, reconciliation thrown")
        void whenFlightServiceDown_shouldMarkFailedAndThrowReconciliation() {
            // Arrange
            BookingRequest req = aRequest();
            Booking claim = aClaimWith(BookingStatus.IN_PROGRESS);

            when(requestHashService.computeHash(req)).thenReturn(HASH_A);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.empty());
            when(bookingTransactionService.createClaim(any())).thenReturn(claim);
            when(flightClient.reserveSeats(any(), any(), anyInt()))
                    .thenThrow(new ServiceUnavailableException("Flight service is currently unavailable"));
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.IN_PROGRESS, BookingStatus.FAILED))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                    .isInstanceOf(BookingReconciliationException.class)
                    .hasMessageContaining("Seat reservation outcome is unknown");

            verify(bookingTransactionService).transitionStatus(BOOKING_ID, BookingStatus.IN_PROGRESS, BookingStatus.FAILED);
            verify(bookingTransactionService, never()).deleteClaim(any());
            verify(bookingTransactionService, never()).finalizeBooking(any());
        }

        @Test
        @DisplayName("same idempotency key + same hash → return existing booking (idempotent)")
        void whenSameKeyAndSameHash_shouldReturnExistingBooking() {
            // Arrange
            BookingRequest req = aRequest();
            Booking existing = aClaimWith(BookingStatus.PENDING);
            BookingResponse response = aBookingResponse();

            when(requestHashService.computeHash(req)).thenReturn(HASH_A);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.of(existing));
            when(bookingMapper.toResponse(existing)).thenReturn(response);

            // Act
            CreateBookingResult result = bookingService.createBooking(USER_ID, IDEM_KEY, req);

            // Assert
            assertThat(result.created()).isFalse();
            assertThat(result.booking()).isEqualTo(response);
            verify(flightClient, never()).reserveSeats(any(), any(), anyInt());
        }

        @Test
        @DisplayName("same idempotency key + different hash → 409 IdempotencyConflictException")
        void whenSameKeyButDifferentHash_shouldThrowIdempotencyConflict() {
            // Arrange
            BookingRequest req = aRequest();
            Booking existing = aClaimWith(BookingStatus.PENDING);
            // existing.requestHash = HASH_A, but incoming hash = HASH_B

            when(requestHashService.computeHash(req)).thenReturn(HASH_B);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.of(existing));

            // Act & Assert
            assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .hasMessageContaining("different request payload");

            verify(flightClient, never()).reserveSeats(any(), any(), anyInt());
        }

        @Test
        @DisplayName("same key, booking in FAILED state → 503 BookingReconciliationException")
        void whenSameKeyAndBookingFailed_shouldThrowReconciliation() {
            // Arrange
            BookingRequest req = aRequest();
            Booking failed = aClaimWith(BookingStatus.FAILED);

            when(requestHashService.computeHash(req)).thenReturn(HASH_A);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.of(failed));

            // Act & Assert
            assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                    .isInstanceOf(BookingReconciliationException.class)
                    .hasMessageContaining("Previous booking attempt requires manual reconciliation");

            verify(flightClient, never()).reserveSeats(any(), any(), anyInt());
        }

        @Test
        @DisplayName("same key, booking IN_PROGRESS → 409 BookingProcessingException")
        void whenSameKeyAndBookingInProgress_shouldThrowProcessingConflict() {
            // Arrange
            BookingRequest req = aRequest();
            Booking inProgress = aClaimWith(BookingStatus.IN_PROGRESS);

            when(requestHashService.computeHash(req)).thenReturn(HASH_A);
            when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                    .thenReturn(Optional.of(inProgress));

            // Act & Assert
            assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                    .isInstanceOf(BookingProcessingException.class);

            verify(flightClient, never()).reserveSeats(any(), any(), anyInt());
        }

        @Nested
        @DisplayName("finalizeBooking failure handling")
        class FinalizeFailure {

            @Test
            @DisplayName("finalize fails but DB shows PENDING → booking succeeded, return it")
            void whenFinalizeFailsButDbIsPending_shouldReturnExistingPending() {
                // Arrange
                BookingRequest req = aRequest();
                Booking claim = aClaimWith(BookingStatus.IN_PROGRESS);
                Booking dbPending = aClaimWith(BookingStatus.PENDING);
                BookingResponse response = aBookingResponse();

                when(requestHashService.computeHash(req)).thenReturn(HASH_A);
                when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                        .thenReturn(Optional.empty());
                when(bookingTransactionService.createClaim(any())).thenReturn(claim);
                when(flightClient.reserveSeats(any(), any(), anyInt()))
                        .thenReturn(aReservationResult());
                when(bookingTransactionService.finalizeBooking(any()))
                        .thenThrow(new RuntimeException("DB timeout"));
                // DB read shows PENDING — commit actually succeeded
                when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(dbPending));
                when(bookingMapper.toResponse(dbPending)).thenReturn(response);

                // Act
                CreateBookingResult result = bookingService.createBooking(USER_ID, IDEM_KEY, req);

                // Assert
                assertThat(result.created()).isTrue();
                // No compensation release — seats were already finalized
                verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
            }

            @Test
            @DisplayName("finalize fails, DB shows IN_PROGRESS, release succeeds → delete claim, throw 500")
            void whenFinalizeFailsAndDbIsInProgressAndReleaseSucceeds_shouldDeleteClaimAndThrow() {
                // Arrange
                BookingRequest req = aRequest();
                Booking claim = aClaimWith(BookingStatus.IN_PROGRESS);
                Booking dbInProgress = aClaimWith(BookingStatus.IN_PROGRESS);

                when(requestHashService.computeHash(req)).thenReturn(HASH_A);
                when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                        .thenReturn(Optional.empty());
                when(bookingTransactionService.createClaim(any())).thenReturn(claim);
                when(flightClient.reserveSeats(any(), any(), anyInt()))
                        .thenReturn(aReservationResult());
                when(bookingTransactionService.finalizeBooking(any()))
                        .thenThrow(new RuntimeException("DB timeout"));
                when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(dbInProgress));
                // Release succeeds
                doNothing().when(flightClient).releaseSeats(any(), any(), anyInt());

                // Act & Assert
                assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                        .isInstanceOf(BookingFinalizationException.class);

                verify(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 1);
                verify(bookingTransactionService).deleteClaim(BOOKING_ID);
            }

            @Test
            @DisplayName("finalize fails, DB shows IN_PROGRESS, release also fails → mark FAILED, 503 reconciliation")
            void whenFinalizeFailsAndReleaseAlsoFails_shouldMarkFailedAndThrowReconciliation() {
                // Arrange
                BookingRequest req = aRequest();
                Booking claim = aClaimWith(BookingStatus.IN_PROGRESS);
                Booking dbInProgress = aClaimWith(BookingStatus.IN_PROGRESS);

                when(requestHashService.computeHash(req)).thenReturn(HASH_A);
                when(bookingRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEM_KEY))
                        .thenReturn(Optional.empty());
                when(bookingTransactionService.createClaim(any())).thenReturn(claim);
                when(flightClient.reserveSeats(any(), any(), anyInt()))
                        .thenReturn(aReservationResult());
                when(bookingTransactionService.finalizeBooking(any()))
                        .thenThrow(new RuntimeException("DB timeout"));
                when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(dbInProgress));
                // Release also fails
                doThrow(new ServiceUnavailableException("flight down"))
                        .when(flightClient).releaseSeats(any(), any(), anyInt());
                when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.IN_PROGRESS, BookingStatus.FAILED))
                        .thenReturn(true);

                // Act & Assert
                assertThatThrownBy(() -> bookingService.createBooking(USER_ID, IDEM_KEY, req))
                        .isInstanceOf(BookingReconciliationException.class);

                verify(bookingTransactionService).transitionStatus(BOOKING_ID, BookingStatus.IN_PROGRESS, BookingStatus.FAILED);
                verify(bookingTransactionService, never()).deleteClaim(any());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CANCEL BOOKING
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cancelBooking()")
    class CancelBooking {

        @Test
        @DisplayName("cancel PENDING booking → seats released, status = CANCELLED")
        void whenPendingBooking_shouldReleaseAndCancel() {
            // Arrange
            Booking pending = aClaimWith(BookingStatus.PENDING);
            Booking cancelled = aClaimWith(BookingStatus.CANCELLED);
            BookingResponse response = BookingResponse.builder().id(BOOKING_ID)
                    .status(BookingStatus.CANCELLED).build();

            when(bookingRepository.findByIdAndUserId(BOOKING_ID, USER_ID))
                    .thenReturn(Optional.of(pending));
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.CANCELLING))
                    .thenReturn(true);
            doNothing().when(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 1);
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.CANCELLING, BookingStatus.CANCELLED))
                    .thenReturn(true);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(cancelled));
            when(bookingMapper.toResponse(cancelled)).thenReturn(response);

            // Act
            BookingResponse result = bookingService.cancelBooking(BOOKING_ID, USER_ID);

            // Assert
            assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(flightClient).releaseSeats(FLIGHT_ID, FARE_CLASS_ID, 1);
            verify(bookingTransactionService).transitionStatus(BOOKING_ID, BookingStatus.CANCELLING, BookingStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancel already CANCELLED booking → idempotent, returns CANCELLED without second release")
        void whenAlreadyCancelled_shouldReturnCancelledWithoutRelease() {
            // Arrange
            Booking pending = aClaimWith(BookingStatus.PENDING);
            Booking cancelled = aClaimWith(BookingStatus.CANCELLED);
            BookingResponse response = BookingResponse.builder().id(BOOKING_ID)
                    .status(BookingStatus.CANCELLED).build();

            when(bookingRepository.findByIdAndUserId(BOOKING_ID, USER_ID))
                    .thenReturn(Optional.of(pending));
            // Transition fails because scheduler or other cancel already transitioned it
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.CANCELLING))
                    .thenReturn(false);
            // Current state in DB is CANCELLED
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(cancelled));
            when(bookingMapper.toResponse(cancelled)).thenReturn(response);

            // Act
            BookingResponse result = bookingService.cancelBooking(BOOKING_ID, USER_ID);

            // Assert
            assertThat(result.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            // No release should happen
            verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        }

        @Test
        @DisplayName("cancel while CANCELLING in progress → 409 ConflictException (no double release)")
        void whenAlreadyCancelling_shouldThrowConflict() {
            // Arrange
            Booking pending = aClaimWith(BookingStatus.PENDING);
            Booking cancelling = aClaimWith(BookingStatus.CANCELLING);

            when(bookingRepository.findByIdAndUserId(BOOKING_ID, USER_ID))
                    .thenReturn(Optional.of(pending));
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.CANCELLING))
                    .thenReturn(false);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(cancelling));

            // Act & Assert
            assertThatThrownBy(() -> bookingService.cancelBooking(BOOKING_ID, USER_ID))
                    .isInstanceOf(com.project.common.exception.ConflictException.class)
                    .hasMessageContaining("already in progress");

            verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        }

        @Test
        @DisplayName("cancel EXPIRED booking → 409 BookingStateException")
        void whenExpiredBooking_shouldThrowStateException() {
            // Arrange
            Booking pending = aClaimWith(BookingStatus.PENDING);
            Booking expired = aClaimWith(BookingStatus.EXPIRED);

            when(bookingRepository.findByIdAndUserId(BOOKING_ID, USER_ID))
                    .thenReturn(Optional.of(pending));
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.CANCELLING))
                    .thenReturn(false);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(expired));

            // Act & Assert
            assertThatThrownBy(() -> bookingService.cancelBooking(BOOKING_ID, USER_ID))
                    .isInstanceOf(BookingStateException.class)
                    .hasMessageContaining("cannot be cancelled");

            verify(flightClient, never()).releaseSeats(any(), any(), anyInt());
        }

        @Test
        @DisplayName("release fails during cancel → 503 BookingReconciliationException, stays in CANCELLING")
        void whenReleaseFails_shouldThrowReconciliationAndLeaveInCancelling() {
            // Arrange
            Booking pending = aClaimWith(BookingStatus.PENDING);

            when(bookingRepository.findByIdAndUserId(BOOKING_ID, USER_ID))
                    .thenReturn(Optional.of(pending));
            when(bookingTransactionService.transitionStatus(BOOKING_ID, BookingStatus.PENDING, BookingStatus.CANCELLING))
                    .thenReturn(true);
            doThrow(new ServiceUnavailableException("flight service down"))
                    .when(flightClient).releaseSeats(any(), any(), anyInt());

            // Act & Assert
            assertThatThrownBy(() -> bookingService.cancelBooking(BOOKING_ID, USER_ID))
                    .isInstanceOf(BookingReconciliationException.class)
                    .hasMessageContaining("manual reconciliation");

            // CANCELLING → CANCELLED must NOT be called — leave in CANCELLING
            verify(bookingTransactionService, never())
                    .transitionStatus(BOOKING_ID, BookingStatus.CANCELLING, BookingStatus.CANCELLED);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // INTERNAL PAYMENT
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Internal Payment Endpoints")
    class InternalPayment {

        @Test
        @DisplayName("getPaymentContext → happy path")
        void getPaymentContext_happyPath() {
            Booking booking = aClaimWith(BookingStatus.PENDING);
            booking.setExpiresAt(LocalDateTime.now().plusMinutes(30));

            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

            var result = bookingService.getPaymentContext(BOOKING_ID);

            assertThat(result.getBookingId()).isEqualTo(BOOKING_ID);
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(result.getTotalAmount()).isEqualTo(booking.getTotalAmount());
            assertThat(result.getCurrency()).isEqualTo(booking.getCurrency());
            assertThat(result.getExpiresAt()).isEqualTo(booking.getExpiresAt());
        }

        @Test
        @DisplayName("confirmPayment → happy path")
        void confirmPayment_happyPath() {
            Booking booking = aClaimWith(BookingStatus.PENDING);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(bookingTransactionService.confirmPayment(eq(BOOKING_ID), eq("pay_123"), any()))
                    .thenReturn(true);

            bookingService.confirmPayment(BOOKING_ID, "pay_123");

            verify(bookingTransactionService).confirmPayment(eq(BOOKING_ID), eq("pay_123"), any());
        }

        @Test
        @DisplayName("confirmPayment → idempotent (already confirmed)")
        void confirmPayment_alreadyConfirmed() {
            Booking booking = aClaimWith(BookingStatus.CONFIRMED);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

            bookingService.confirmPayment(BOOKING_ID, "pay_123");

            verify(bookingTransactionService, never()).confirmPayment(any(), any(), any());
        }

        @Test
        @DisplayName("confirmPayment → fail if not PENDING or CONFIRMED")
        void confirmPayment_invalidState() {
            Booking booking = aClaimWith(BookingStatus.CANCELLED);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> bookingService.confirmPayment(BOOKING_ID, "pay_123"))
                    .isInstanceOf(com.project.common.exception.ConflictException.class)
                    .hasMessageContaining("Booking cannot be confirmed");
        }

        @Test
        @DisplayName("confirmPayment → fail on concurrent update")
        void confirmPayment_concurrentUpdate() {
            Booking booking = aClaimWith(BookingStatus.PENDING);
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(bookingTransactionService.confirmPayment(eq(BOOKING_ID), eq("pay_123"), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> bookingService.confirmPayment(BOOKING_ID, "pay_123"))
                    .isInstanceOf(com.project.common.exception.ConflictException.class)
                    .hasMessageContaining("concurrent update");
        }
    }
}
