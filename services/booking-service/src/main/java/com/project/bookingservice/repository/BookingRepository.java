package com.project.bookingservice.repository;

import com.project.bookingservice.entity.Booking;
import com.project.bookingservice.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    // 1. Idempotency
    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // 2. My Bookings
    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    // 3. Owner validation
    Optional<Booking> findByIdAndUserId(UUID bookingId,UUID userId);

    // 4. Scheduler - expired PENDING bookings
    List<Booking> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(BookingStatus status, LocalDateTime time, Pageable pageable);

    // 5. Atomic state transition
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE Booking b
           SET b.status = :newStatus,
               b.version = b.version + 1,
               b.updatedAt = :updatedAt
         WHERE b.id = :bookingId
           AND b.status = :expectedStatus
    """)
    int transitionStatus(
            @Param("bookingId") UUID bookingId,
            @Param("expectedStatus") BookingStatus expectedStatus,
            @Param("newStatus") BookingStatus newStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    // 6. Atomic payment confirmation
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE Booking b
           SET b.status = 'CONFIRMED',
               b.paymentId = :paymentId,
               b.confirmedAt = :confirmedAt,
               b.version = b.version + 1,
               b.updatedAt = :updatedAt
         WHERE b.id = :bookingId
           AND b.status = 'PENDING'
           AND b.paymentId IS NULL
    """)
    int confirmPayment(
            @Param("bookingId") UUID bookingId,
            @Param("paymentId") UUID paymentId,
            @Param("confirmedAt") LocalDateTime confirmedAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
