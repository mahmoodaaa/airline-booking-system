package com.project.bookingservice.service.impl;

import com.project.bookingservice.client.FlightClient;
import com.project.bookingservice.client.dto.SeatReservationResult;
import com.project.bookingservice.dto.request.BookingRequest;
import com.project.bookingservice.dto.response.BookingResponse;
import com.project.bookingservice.dto.response.CreateBookingResult;
import com.project.bookingservice.dto.response.PaymentContextResponse;
import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.exception.*;
import com.project.bookingservice.mapper.BookingMapper;
import com.project.bookingservice.repository.BookingRepository;
import com.project.bookingservice.service.BookingService;
import com.project.bookingservice.service.BookingTransactionService;
import com.project.bookingservice.service.RequestHashService;
import com.project.common.exception.ConflictException;
import com.project.common.exception.RecordNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

        @Value("${booking.ttl-minutes:10}")
        private long bookingTtlMinutes;

        private final BookingRepository bookingRepository;
        private final BookingTransactionService bookingTransactionService;
        private final RequestHashService requestHashService;
        private final FlightClient flightClient;
        private final BookingMapper bookingMapper;

        // ============================================================
        // CUSTOMER — Create
        // ============================================================

        @Override
        public CreateBookingResult createBooking(UUID userId, String idempotencyKey, BookingRequest request) {

                log.info("createBooking — userId={} idempotencyKey={}", userId, idempotencyKey);

                // ============================================================
                // 1. Request fingerprint
                // ============================================================

                String requestHash = requestHashService.computeHash(request);

                // ============================================================
                // 2. Fast idempotency check
                // ============================================================

                Optional<Booking> existing = bookingRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);

                if (existing.isPresent()) {
                        return handleExistingBooking(
                                        existing.get(),
                                        requestHash);
                }

                // ============================================================
                // 3. Create durable IN_PROGRESS claim
                // ============================================================

                int passengerCount = request.getPassengers().size();

                Booking claim = Booking.builder()
                                .userId(userId)
                                .flightId(request.getFlightId())
                                .fareClassId(request.getFareClassId())
                                .idempotencyKey(idempotencyKey)
                                .requestHash(requestHash)
                                .status(BookingStatus.IN_PROGRESS)
                                .passengerCount(passengerCount)
                                .build();

                request.getPassengers()
                                .stream()
                                .map(bookingMapper::toPassengerEntity)
                                .forEach(claim::addPassenger);

                try {

                        claim = bookingTransactionService.createClaim(claim);

                } catch (DataIntegrityViolationException e) {

                        // Another request with the same
                        // (userId, idempotencyKey) won the race.

                        Booking winner = bookingRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                                        .orElseThrow(() -> e);

                        return handleExistingBooking(
                                        winner,
                                        requestHash);
                }

                UUID claimId = claim.getId();

                // ============================================================
                // 4. Reserve inventory in Flight Service
                // ============================================================

                SeatReservationResult reservation;

                try {

                        reservation = flightClient.reserveSeats(
                                        request.getFlightId(),
                                        request.getFareClassId(),
                                        passengerCount);

                } catch (FlightReservationRejectedException e) {

                        /*
                         * Definitive business rejection.
                         *
                         * Examples:
                         * - not enough seats
                         * - flight already departed
                         * - fare class invalid
                         *
                         * We KNOW reserve did not happen.
                         */

                        bookingTransactionService.deleteClaim(claimId);

                        throw e;

                } catch (Exception e) {

                        /*
                         * Ambiguous outcome.
                         *
                         * Timeout / connection reset / 5xx:
                         * Flight MAY have processed reserve.
                         *
                         * DO NOT delete the claim.
                         * DO NOT retry reserve.
                         */

                        boolean markedFailed = bookingTransactionService.transitionStatus(claimId,
                                        BookingStatus.IN_PROGRESS,
                                        BookingStatus.FAILED);

                        if (!markedFailed) {
                                log.error(
                                                "CRITICAL: Could not mark booking {} as FAILED " +
                                                                "after unknown reserve outcome",
                                                claimId);
                        }

                        log.error("CRITICAL: Reserve outcome unknown for booking {}. " +
                                        "Manual reconciliation required", claimId, e);

                        throw new BookingReconciliationException(
                                        "Seat reservation outcome is unknown and requires reconciliation",
                                        e);
                }

                // ============================================================
                // 5. Build authoritative Booking snapshot
                // ============================================================

                applySnapshot(claim, reservation);

                claim.setStatus(BookingStatus.PENDING);

                claim.setExpiresAt(LocalDateTime.now().plusMinutes(bookingTtlMinutes));

                // ============================================================
                // 6. Finalize Booking
                // ============================================================

                try {

                        claim = bookingTransactionService.finalizeBooking(claim);

                } catch (Exception finalizeException) {

                        return handleFinalizeFailure(
                                        claimId,
                                        request,
                                        passengerCount,
                                        finalizeException);
                }

                log.info("Booking {} created successfully — status=PENDING expiresAt={}", claim.getId(),
                                claim.getExpiresAt());

                return new CreateBookingResult(bookingMapper.toResponse(claim), true);
        }

        // ============================================================
        // CUSTOMER — Read own
        // ============================================================

        @Override
        public BookingResponse getMyBookingById(UUID bookingId, UUID userId) {
                Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                                .orElseThrow(() -> new RecordNotFoundException(
                                                "Booking not found or access denied: " + bookingId));
                return bookingMapper.toResponse(booking);
        }

        @Override
        public Page<BookingResponse> getMyBookings(UUID userId, Pageable pageable) {
                return bookingRepository.findByUserId(userId, pageable)
                                .map(bookingMapper::toResponse);
        }

        // ============================================================
        // CUSTOMER — Cancel
        // ============================================================

        @Override
        public BookingResponse cancelBooking(UUID bookingId, UUID userId) {
                log.info("cancelBooking — bookingId={} userId={}", bookingId, userId);

                // Ownership check
                Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                                .orElseThrow(() -> new RecordNotFoundException(
                                                "Booking not found or access denied: " + bookingId));

                // Atomic: PENDING → CANCELLING
                // If 0 rows updated: Scheduler already expired it, or another cancel won
                boolean claimed = bookingTransactionService.transitionStatus(
                                bookingId, BookingStatus.PENDING, BookingStatus.CANCELLING);

                if (!claimed) {
                        Booking current = bookingRepository.findById(bookingId)
                                        .orElseThrow(() -> new RecordNotFoundException(
                                                        "Booking not found: " + bookingId));

                        if (current.getStatus() == BookingStatus.CANCELLED) {
                                return bookingMapper.toResponse(current);
                        }

                        if (current.getStatus() == BookingStatus.CONFIRMED) {
                                throw new ConflictException(
                                                "A CONFIRMED booking cannot be cancelled through this endpoint.");
                        }

                        if (current.getStatus() == BookingStatus.CANCELLING) {
                                throw new ConflictException(
                                                "Booking cancellation is already in progress");
                        }

                        throw new BookingStateException(
                                        "Booking cannot be cancelled from status: " + current.getStatus());
                }

                int passengerCount = booking.getPassengerCount();

                // Release seats — external call (between the two transitions)
                try {
                        flightClient.releaseSeats(
                                        booking.getFlightId(),
                                        booking.getFareClassId(),
                                        passengerCount);
                } catch (Exception e) {
                        log.error(
                                        "CRITICAL: releaseSeats outcome unknown during cancel for booking {} " +
                                                        "— leaving in CANCELLING state for manual reconciliation",
                                        bookingId, e);
                        throw new BookingReconciliationException(
                                        "Booking cancellation requires manual reconciliation", e);
                }

                // CANCELLING → CANCELLED
                boolean completed = bookingTransactionService.transitionStatus(
                                bookingId, BookingStatus.CANCELLING, BookingStatus.CANCELLED);

                if (!completed) {

                        log.error(
                                        "CRITICAL: Seats released but booking {} could not transition " +
                                                        "CANCELLING -> CANCELLED. Manual reconciliation required.",
                                        bookingId);
                        throw new BookingReconciliationException(
                                        "Booking cancellation could not be finalized and requires reconciliation");
                }

                Booking cancelled = bookingRepository.findById(bookingId)
                                .orElseThrow(() -> new RecordNotFoundException(
                                                "Booking not found after cancel: " + bookingId));

                log.info("Booking {} cancelled successfully", bookingId);
                return bookingMapper.toResponse(cancelled);
        }

        // ============================================================
        // ADMIN — Read any
        // ============================================================

        @Override
        public BookingResponse getBookingById(UUID bookingId) {

                return bookingRepository.findById(bookingId)
                                .map(bookingMapper::toResponse)
                                .orElseThrow(() -> new RecordNotFoundException(
                                                "Booking not found: " + bookingId));
        }

        @Override
        public Page<BookingResponse> getAllBookings(Pageable pageable) {
                return bookingRepository.findAll(pageable)
                                .map(bookingMapper::toResponse);
        }

        // ============================================================
        // INTERNAL (Payment Service)
        // ============================================================

        @Override
        public PaymentContextResponse getPaymentContext(UUID bookingId) {
                Booking booking = bookingRepository.findById(bookingId)
                                .orElseThrow(() -> new RecordNotFoundException("Booking not found: " + bookingId));

                return bookingMapper.toPaymentContextResponse(booking);
        }

        @Override
        public void confirmPayment(UUID bookingId, UUID paymentId) {
                Booking booking = bookingRepository.findById(bookingId)
                                .orElseThrow(() -> new RecordNotFoundException("Booking not found: " + bookingId));

                if (booking.getStatus() == BookingStatus.CONFIRMED) {

                        if (Objects.equals(booking.getPaymentId(), paymentId)) {
                                log.info(
                                                "Booking {} already confirmed with same payment {}",
                                                bookingId,
                                                paymentId);
                                return;
                        }

                        throw new ConflictException(
                                        "Booking is already confirmed with a different payment");
                }

                if (booking.getStatus() != BookingStatus.PENDING) {
                        throw new ConflictException(
                                        "Booking cannot be confirmed. Current status: " + booking.getStatus());
                }

                LocalDateTime now = LocalDateTime.now();
                boolean success = bookingTransactionService.confirmPayment(bookingId, paymentId, now);

                if (!success) {
                        Booking current = bookingRepository.findById(bookingId)
                                .orElseThrow(() -> new RecordNotFoundException("Booking not found: " + bookingId));

                        if (current.getStatus() == BookingStatus.CONFIRMED
                                && Objects.equals(current.getPaymentId(), paymentId)) {
                                log.info("Booking {} was concurrently confirmed with same payment {}", bookingId, paymentId);
                                return;
                        }

                        if (current.getStatus() == BookingStatus.CONFIRMED) {
                                throw new ConflictException("Booking was confirmed with a different payment");
                        }

                        throw new ConflictException("Booking cannot be confirmed. Current status: " + current.getStatus());
                }

                log.info("Booking {} confirmed successfully with paymentId {}", bookingId, paymentId);
        }

        // ============================================================
        // Private helpers
        // ============================================================

        private void applySnapshot(Booking booking, SeatReservationResult r) {
                booking.setFlightNumber(r.getFlightNumber());
                booking.setOriginIata(r.getOriginIata());
                booking.setDestinationIata(r.getDestinationIata());
                booking.setDepartureTime(r.getDepartureTime());
                booking.setArrivalTime(r.getArrivalTime());
                booking.setFareClassType(r.getFareClassType());
                booking.setPriceAtBooking(r.getPrice());
                booking.setCurrency(r.getCurrency());

                BigDecimal totalAmount = r.getPrice().multiply(BigDecimal.valueOf(booking.getPassengerCount()));
                booking.setTotalAmount(totalAmount);
        }

        private boolean safeRelease(UUID flightId, UUID fareClassId, int count, UUID bookingId) {
                try {

                        flightClient.releaseSeats(
                                        flightId,
                                        fareClassId,
                                        count);
                        return true;
                } catch (Exception releaseEx) {
                        log.error(
                                        "CRITICAL: compensation releaseSeats failed for booking {} " +
                                                        "flightId={} fareClassId={} count={}",
                                        bookingId,
                                        flightId,
                                        fareClassId,
                                        count,
                                        releaseEx);

                        return false;
                }
        }

        private CreateBookingResult handleExistingBooking(
                        Booking booking,
                        String requestHash) {

                if (!booking.getRequestHash().equals(requestHash)) {

                        throw new IdempotencyConflictException(
                                        "Idempotency key already used with a different request payload");
                }

                if (booking.getStatus() == BookingStatus.IN_PROGRESS) {

                        throw new BookingProcessingException(
                                        "Booking request is currently being processed");
                }

                if (booking.getStatus() == BookingStatus.FAILED) {

                        throw new BookingReconciliationException(
                                        "Previous booking attempt requires manual reconciliation");
                }

                /*
                 * PENDING / CANCELLED / EXPIRED
                 *
                 * Same idempotency request => same Booking.
                 */
                return new CreateBookingResult(bookingMapper.toResponse(booking), false);
        }

        private CreateBookingResult handleFinalizeFailure(
                        UUID claimId,
                        BookingRequest request,
                        int passengerCount,
                        Exception finalizeException) {

                log.error(
                                "finalizeBooking failed for booking {} — verifying persisted state",
                                claimId,
                                finalizeException);

                Booking persisted;

                try {

                        persisted = bookingRepository
                                        .findById(claimId)
                                        .orElse(null);

                } catch (Exception verificationException) {

                        /*
                         * DB itself cannot tell us whether COMMIT succeeded.
                         *
                         * DO NOT release seats.
                         * The outcome is unknown.
                         */

                        log.error(
                                        "CRITICAL: Could not verify finalization outcome for booking {}. " +
                                                        "No compensation will be attempted.",
                                        claimId,
                                        verificationException);

                        throw new BookingReconciliationException(
                                        "Booking finalization outcome is unknown and requires reconciliation",
                                        finalizeException);
                }

                // ------------------------------------------------------------
                // Commit actually succeeded although caller received exception
                // ------------------------------------------------------------

                if (persisted != null
                                && persisted.getStatus() == BookingStatus.PENDING) {

                        log.warn(
                                        "Booking {} is already PENDING despite finalize exception",
                                        claimId);

                        return new CreateBookingResult(bookingMapper.toResponse(persisted), true);
                }

                // ------------------------------------------------------------
                // DB confirms finalize did NOT happen
                // ------------------------------------------------------------

                if (persisted != null
                                && persisted.getStatus() == BookingStatus.IN_PROGRESS) {

                        boolean compensated = safeRelease(
                                        request.getFlightId(),
                                        request.getFareClassId(),
                                        passengerCount,
                                        claimId);

                        if (compensated) {

                                /*
                                 * Reserve definitely happened.
                                 * Release definitely succeeded.
                                 *
                                 * Safe to remove the failed claim.
                                 */

                                bookingTransactionService.deleteClaim(claimId);

                                throw new BookingFinalizationException(
                                                "Booking could not be finalized",
                                                finalizeException);
                        }

                        /*
                         * Reserve happened but compensation could not
                         * be confirmed.
                         */

                        boolean markedFailed = bookingTransactionService.transitionStatus(claimId,
                                        BookingStatus.IN_PROGRESS,
                                        BookingStatus.FAILED);

                        if (!markedFailed) {

                                log.error("CRITICAL: Could not transition booking {} " + "IN_PROGRESS -> FAILED",
                                                claimId);
                        }

                        log.error("CRITICAL: Booking {} requires manual reconciliation. "
                                        + "Seats may remain reserved.", claimId);

                        throw new BookingReconciliationException(
                                        "Booking failed and seat compensation could not be confirmed",
                                        finalizeException);
                }

                // ------------------------------------------------------------
                // Unexpected state
                // ------------------------------------------------------------

                log.error(
                                "CRITICAL: Unexpected booking state after finalize failure. " +
                                                "bookingId={} status={}",
                                claimId,
                                persisted == null ? "MISSING" : persisted.getStatus());

                throw new BookingReconciliationException(
                                "Booking finalization requires manual reconciliation",
                                finalizeException);
        }
}
