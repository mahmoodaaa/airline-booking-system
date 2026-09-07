# Sprint 4 — Booking Service: الخطة النهائية المعتمدة

> ✅ جميع القرارات حُسمت — هذا الملف هو baseline للتنفيذ.

---

## 1. الهدف

أول Sprint يدخل Inter-Service Communication حقيقي.

```
Client → Gateway → Booking Service → Flight Service → Flight DB
```

- **Booking Service** يملك: دورة حياة الحجز، passenger data، booking snapshot، idempotency، expiration، cancellation.
- **Booking Service لا يملك:** availableSeats، Flight live data، Payment، User data.

---

## 2. القرارات المعتمدة نهائياً

| # | القرار | النتيجة النهائية |
|---|--------|----------------|
| Q1 | Seat endpoints | `POST /internal/fare-classes/{id}/reserve` + `/release` — Internal only, لا تُعرَّض بالـ Gateway |
| Q2 | Service Auth | Service JWT `{sub: "booking-service", type: "SERVICE"}` — Flight يتحقق من الاثنين معاً |
| Q3 | Flight Down | 503، بدون Retry (non-idempotent operations) |
| Q6 | Distributed TX | Best-effort compensation + structured CRITICAL log — Outbox/Saga مع Kafka Sprint |
| Idempotency | Duplicate submission | `(userId, idempotencyKey)` UNIQUE + `requestHash` — يُطبّق الآن |
| Confirm | معنى غامض | **محذوف** من Sprint 4 — يُبنى مع Payment Sprint |
| passengerCount | Data Redundancy | **يُحسب** `passengers.size()` — لا يُخزن كـ field |
| Cancel | Idempotency | CANCELLED → CANCELLED = no-op (لا release مرة ثانية) |
| Expire | Idempotency | EXPIRED → لا release مرة ثانية |
| Scheduler failure | Seat safety | لو release فشل → يبقى PENDING (لا يصير EXPIRED) + log error + retry next cycle |
| /internal/** | Gateway | لا تُوجَّه من Gateway — Booking يتصل مباشرة بـ Flight على :7172 |
| REST Client | Technology | `RestClient` (Spring 6.1+) — لا Feign، لا Kafka |
| IN_PROGRESS | Lifecycle | حالة مؤقتة أثناء إنشاء الحجز — لا تظهر للعميل لو فشل reserve |

---

## 3. Booking Lifecycle

```
POST /api/bookings
        ↓
   IN_PROGRESS (مؤقت)
        ↓
   reserve seats (Flight Service)
   ┌────────────────────┐
   │                    │
 success              failure
   │                    │
   ↓                    ↓
 PENDING            FAILED/503 (لا يُحفظ)
   │
   ├──── CANCEL (user) ──────────→ CANCELLED → release seats
   │
   └──── TTL 10min (scheduler) ──→ EXPIRED  → release seats
```

**قاعدة مهمة:** لا نُعلم EXPIRED إلا إذا نجح release أولاً.

---

## 4. دورة حياة الإنشاء (التفصيل الكامل)

```
POST /api/bookings
  1. Validate JWT → extract userId
  2. Validate Idempotency-Key (required)
     - موجود بنفس userId + requestHash → return existing booking (200)
     - موجود بنفس userId + requestHash مختلف → 409 Conflict
     - غير موجود → تابع
  3. Validate request fields
  4. GET /api/flights/{id}/availability (تحقق مبدئي فقط)
  5. Validate: departureTime > NOW()
  6. Validate: availableSeats >= passengers.size()
  7. Build Booking snapshot (من بيانات Flight)
  8. POST /internal/fare-classes/{fareClassId}/reserve (count=passengers.size())
     - فشل (409 لا مقاعد) → 409 Conflict
     - فشل (503 Flight down) → 503 Service Unavailable
  9. Save Booking (status=PENDING, expiresAt=NOW()+10min)
     - فشل → compensate: POST /internal/.../release
       - فشل compensation → CRITICAL LOG (structured) + manual recovery
 10. Return 201 Created
```

---

## 5. Idempotency Design

```java
// Booking Entity
@Column(nullable = false)
private String idempotencyKey;   // من Client header

@Column(nullable = false)
private String requestHash;      // SHA-256 لـ request body

// Unique constraint
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
```

| السيناريو | الاستجابة |
|----------|----------|
| نفس userId + نفس key + نفس requestHash | 200 + نفس Booking |
| نفس userId + نفس key + requestHash مختلف | 409 Conflict |
| بدون Idempotency-Key | 400 Bad Request |
| userId مختلف + نفس key | طبيعي — كل مستخدم له namespace مستقل |

---

## 6. Service JWT (InternalTokenProvider)

```java
// Booking Service — config/InternalTokenProvider.java
public String generateServiceToken() {
    return Jwts.builder()
            .subject("booking-service")
            .claim("type", "SERVICE")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000)) // 1 minute
            .signWith(signingKey)  // نفس jwt.secret المشترك
            .compact();
}
```

```java
// Flight Service — SecurityConfig.java
// /internal/** endpoint يتحقق:
// 1. JWT signature صحيح
// 2. claims.get("type") == "SERVICE"
// 3. claims.getSubject() == "booking-service"
// أي JWT آخر (CUSTOMER/ADMIN/SERVICE من خدمة أخرى) → 403
```

**استخدام:** في **كل** calls من Booking → Flight (create, cancel, scheduler).

---

## 7. Distributed Transaction — Compensation Pattern

```
reserve seats (Flight)
       ↓
save Booking (booking_db)
       ↓
if save FAILS:
  → release seats (compensating call)
  → if release FAILS:
      → log.error("CRITICAL: Seats reserved but booking failed. " +
                  "Manual recovery needed. " +
                  "fareClassId={}, count={}, timestamp={}", ...);
      // لا حل تلقائي الآن
      // Outbox/Saga مع Kafka Sprint
```

**قاعدة:** نعترف بالمشكلة، نوثقها، ولا ندّعي أن النظام distributed-transaction safe.

---

## 8. Flight Service — Internal Endpoints (تُضاف في Step 1)

```
POST /internal/fare-classes/{id}/reserve
Body: { "count": N }
Security: Service JWT only (sub=booking-service, type=SERVICE)
Response 200: OK
Response 409: insufficient seats
Response 400: invalid count

POST /internal/fare-classes/{id}/release
Body: { "count": N }
Security: Service JWT only
Response 200: OK
Guard: availableSeats + count <= totalSeats (لا تجاوز)
```

**قيد مهم على Release:**
```java
if (fareClass.getAvailableSeats() + count > fareClass.getTotalSeats()) {
    log.error("WARN: Release would exceed totalSeats. Data inconsistency detected.");
    // نضبطها على totalSeats بدل الرفض (safe fallback)
    fareClass.setAvailableSeats(fareClass.getTotalSeats());
} else {
    fareClass.setAvailableSeats(fareClass.getAvailableSeats() + count);
}
```

---

## 9. Booking Entity

```java
@Entity
@Table(name = "bookings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // User Reference (من JWT فقط — لا FK)
    @Column(nullable = false)
    private UUID userId;

    // Flight References (UUID references — لا FK)
    @Column(nullable = false) private UUID flightId;
    @Column(nullable = false) private UUID fareClassId;

    // Snapshot (لحماية البيانات التاريخية)
    @Column(nullable = false) private String flightNumber;
    @Column(nullable = false) private String originIata;
    @Column(nullable = false) private String destinationIata;
    @Column(nullable = false) private LocalDateTime departureTime;
    @Column(nullable = false) private LocalDateTime arrivalTime;
    @Column(nullable = false) private String fareClassType;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal priceAtBooking;
    @Column(nullable = false, length = 3) @Enumerated(EnumType.STRING) private Currency currency;

    // State
    @Column(nullable = false) @Enumerated(EnumType.STRING) private BookingStatus status;
    @Column(nullable = false) private LocalDateTime expiresAt;

    // Idempotency
    @Column(nullable = false) private String idempotencyKey;
    @Column(nullable = false) private String requestHash;

    // Audit
    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp   private LocalDateTime updatedAt;

    // Passengers
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingPassenger> passengers = new ArrayList<>();

    // Computed — لا يُخزن
    public int getPassengerCount() { return passengers.size(); }
}
```

---

## 10. BookingPassenger Entity

```java
@Entity
@Table(name = "booking_passengers")
public class BookingPassenger {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne @JoinColumn(name = "booking_id", nullable = false) private Booking booking;

    @Column(nullable = false) private String firstName;
    @Column(nullable = false) private String lastName;
    @Column(nullable = false) private String passportNumber;
    @Column(nullable = false) private LocalDate dateOfBirth;
    @Column(nullable = false, length = 2) private String nationality; // ISO country code
}
```

---

## 11. BookingStatus Enum

```java
public enum BookingStatus {
    IN_PROGRESS,  // مؤقت أثناء إنشاء الحجز
    PENDING,      // مقاعد محجوزة، TTL 10min
    CANCELLED,    // ألغاه المستخدم
    EXPIRED       // انتهت TTL
    // CONFIRMED → مع Payment Sprint
    // REFUNDED  → مع Payment Sprint
}
```

---

## 12. Scheduler — قواعد صارمة

```java
@Scheduled(fixedDelay = 60_000)
@Transactional
public void expireStaleBookings() {
    List<Booking> pending = bookingRepository
        .findByStatusAndExpiresAtBefore(BookingStatus.PENDING, LocalDateTime.now());

    for (Booking booking : pending) {
        try {
            // release أولاً — نجاح شرط EXPIRED
            flightClient.releaseSeats(
                booking.getFareClassId(),
                booking.getPassengerCount(),
                internalTokenProvider.generateServiceToken()
            );
            booking.setStatus(BookingStatus.EXPIRED);
            bookingRepository.save(booking);
            log.info("Booking {} expired and seats released.", booking.getId());

        } catch (Exception e) {
            // يبقى PENDING — يعيد المحاولة في الـ cycle القادم
            log.error("Failed to release seats for expired booking {}. " +
                      "Will retry next cycle. Error: {}", booking.getId(), e.getMessage());
        }
    }
}
```

---

## 13. Package Structure

```
com.project.bookingservice/
├── entity/          → Booking, BookingPassenger
├── enums/           → BookingStatus, Currency
├── mapper/          → BookingMapper
├── dto/
│   ├── request/     → BookingRequest, PassengerRequest, SeatOperationRequest
│   └── response/    → BookingResponse, PassengerResponse
├── repository/      → BookingRepository
├── client/          → FlightClient (RestClient)
├── service/impl/    → BookingServiceImpl
├── scheduler/       → BookingCleanupJob
├── security/        → JwtValidator, JwtAuthenticationFilter,
│                       SecurityAuthEntryPoint, SecurityAccessDeniedHandler
├── config/          → SecurityConfig, OpenApiConfig,
│                       RestClientConfig, InternalTokenProvider
└── controller/      → BookingController
```

---

## 14. API Endpoints

| Method | Path | Access | Notes |
|--------|------|--------|-------|
| POST | `/api/bookings` | CUSTOMER | Idempotency-Key header required |
| GET | `/api/bookings/my` | CUSTOMER | Paginated |
| GET | `/api/bookings/{id}` | OWNER / ADMIN | |
| POST | `/api/bookings/{id}/cancel` | OWNER / ADMIN | Idempotent |
| GET | `/api/bookings` | ADMIN only | Paginated |

**Internal (لا تُعرَّض بالـ Gateway):**
| Method | Path | Access |
|--------|------|--------|
| POST | `/internal/fare-classes/{id}/reserve` | SERVICE JWT |
| POST | `/internal/fare-classes/{id}/release` | SERVICE JWT |

---

## 15. Gateway Routing (يُضاف)

```yaml
- id: booking-service
  uri: http://localhost:7373
  predicates: [Path=/api/bookings/**]
```

`/internal/**` — لا route في Gateway.

---

## 16. ترتيب التنفيذ

```
Step 1  → Flight Service: /internal/fare-classes/{id}/reserve + /release + InternalSecurityConfig
Step 2  → booking-service: pom.xml + application.properties (port 7373, booking_db)
Step 3  → Enums: BookingStatus, Currency
Step 4  → Entities: Booking, BookingPassenger
Step 5  → Repository + Custom Queries
Step 6  → DTOs: BookingRequest, PassengerRequest, BookingResponse, PassengerResponse
Step 7  → Config: RestClientConfig, InternalTokenProvider
Step 8  → FlightClient: getAvailability(), reserveSeats(), releaseSeats()
Step 9  → BookingServiceImpl: create, cancel, getById, getMyBookings
Step 10 → BookingCleanupJob (@Scheduled)
Step 11 → Security: JWT filter chain (نفس نمط flight-service)
Step 12 → BookingController
Step 13 → Gateway: إضافة /api/bookings/**
Step 14 → Testing
```

---

## 17. Testing Plan

### Security
- 401 — بدون JWT
- 403 — ADMIN يحاول الوصول لـ /api/bookings/my (لو قيّدناه لـ CUSTOMER)
- 200/201 — JWT صحيح

### Business Logic
- حجز ناجح (Happy Path)
- لا مقاعد كافية → 409
- الرحلة غادرت → 400
- بيانات ركاب غير مكتملة → 400
- إلغاء حجز PENDING → CANCELLED + release
- إلغاء نفس الحجز مرتين → no-op
- Scheduler: PENDING انتهت مدته → EXPIRED + release
- نفس Idempotency-Key → نفس الحجز
- نفس Key + request مختلف → 409

### Integration
- Booking → Flight reserve (success)
- Booking → Flight release (cancel/expire)
- Flight unavailable → 503

### Concurrency
- مستخدمان يحجزان نفس الـ FareClass اللي فيه مقعد واحد → واحد فقط ينجح (@Version)

---

## 18. المبادئ الخمسة

```
1. Flight owns seat inventory — Booking لا يعدّ المقاعد
2. Booking owns booking lifecycle — Flight لا يعرف بوجود Booking
3. REST for synchronous reservation — المستخدم يريد رد فوري
4. Idempotency prevents duplicate bookings — (userId, key, requestHash)
5. Compensation handles cross-service failure — Best-effort, موثّق، ليس مخفيّاً
```

---

> **ما لن يُبنى في Sprint 4:** CONFIRMED، Payment، Kafka، Redis، Saga، Circuit Breaker، Feign، Retry.
