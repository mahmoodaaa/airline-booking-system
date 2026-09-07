package com.project.bookingservice.entity;

import com.project.bookingservice.enums.BookingStatus;
import com.project.bookingservice.enums.Currency;
import com.project.bookingservice.enums.FareClassType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "bookings",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_booking_user_idempotency",
                        columnNames = {"user_id", "idempotency_key"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_booking_status_expires",
                        columnList = "status, expires_at"
                ),
                @Index(
                        name = "idx_booking_user_created",
                        columnList = "user_id, created_at"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // -----------------------------------
    // User Reference
    // -----------------------------------

    // Reference only - no FK to auth_service
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // -----------------------------------
    // Flight References
    // -----------------------------------

    // References only - no FK to flight_service
    @Column(name = "flight_id", nullable = false)
    private UUID flightId;

    @Column(name = "fare_class_id", nullable = false)
    private UUID fareClassId;

    // -----------------------------------
    // Flight Snapshot
    // Filled after successful reserve
    // -----------------------------------

    @Column(name = "flight_number")
    private String flightNumber;

    @Column(name = "origin_iata", length = 3)
    private String originIata;

    @Column(name = "destination_iata", length = 3)
    private String destinationIata;

    @Column(name = "departure_time")
    private LocalDateTime departureTime;

    @Column(name = "arrival_time")
    private LocalDateTime arrivalTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "fare_class_type")
    private FareClassType fareClassType;

    @Column(
            name = "price_at_booking",
            precision = 10,
            scale = 2
    )
    private BigDecimal priceAtBooking;

    @Column(
            name = "total_amount",
            precision = 10,
            scale = 2
    )
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3)
    private Currency currency;

    // -----------------------------------
    // Payment Reference
    // -----------------------------------

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    // -----------------------------------
    // Lifecycle
    // -----------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    // null during IN_PROGRESS
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // -----------------------------------
    // Idempotency
    // -----------------------------------

    @Column(
            name = "idempotency_key",
            nullable = false,
            length = 100
    )
    private String idempotencyKey;

    // SHA-256 = 64 hex characters
    @Column(
            name = "request_hash",
            nullable = false,
            length = 64
    )
    private String requestHash;

    // -----------------------------------
    // Optimistic Locking
    // -----------------------------------

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // -----------------------------------
    // Audit
    // -----------------------------------

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false
    )
    private LocalDateTime updatedAt;

    // -----------------------------------
    // Passengers
    // -----------------------------------

    @OneToMany(
            mappedBy = "booking",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<BookingPassenger> passengers = new ArrayList<>();

    @Column(name = "passenger_count", nullable = false)
    private int passengerCount;

    // -----------------------------------
    // Helpers
    // -----------------------------------

    public void addPassenger(BookingPassenger passenger) {
        passengers.add(passenger);
        passenger.setBooking(this);
    }
}