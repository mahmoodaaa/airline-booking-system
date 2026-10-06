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

    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Page<Booking> findByUserId(
            UUID userId,
            Pageable pageable
    );

    Optional<Booking> findByIdAndUserId(UUID bookingId, UUID userId);

    // One unified expiry query.
    @Query(
            value = """
                    SELECT *
                      FROM bookings
                     WHERE status IN ('PENDING', 'PAYMENT_PENDING')
                       AND expires_at <= :now
                     ORDER BY expires_at ASC
                    """,
            nativeQuery = true
    )
    List<Booking> findDueBookings(@Param("now") LocalDateTime now, Pageable pageable);

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

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE Booking b
           SET b.status = :newStatus,
               b.expiresAt = :newExpiresAt,
               b.version = b.version + 1,
               b.updatedAt = :now
         WHERE b.id = :bookingId
           AND b.userId = :userId
           AND b.status = :expectedStatus
           AND b.expiresAt > :now
    """)
    int startPaymentWindow(
            @Param("bookingId") UUID bookingId,
            @Param("userId") UUID userId,
            @Param("expectedStatus") BookingStatus expectedStatus,
            @Param("newStatus") BookingStatus newStatus,
            @Param("newExpiresAt") LocalDateTime newExpiresAt,
            @Param("now") LocalDateTime now
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE Booking b
           SET b.status = :newStatus,
               b.paymentId = :paymentId,
               b.confirmedAt = :now,
               b.version = b.version + 1,
               b.updatedAt = :now
         WHERE b.id = :bookingId
           AND b.status = :expectedStatus
           AND b.expiresAt > :now
           AND b.paymentId IS NULL
    """)
    int confirmPayment(
            @Param("bookingId") UUID bookingId,
            @Param("paymentId") UUID paymentId,
            @Param("expectedStatus") BookingStatus expectedStatus,
            @Param("newStatus") BookingStatus newStatus,
            @Param("now") LocalDateTime now
    );
}