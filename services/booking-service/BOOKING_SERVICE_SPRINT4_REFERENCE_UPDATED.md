# Booking Service — Sprint 4 Complete Reference

> **Project:** Airline Booking System  
> **Service:** `booking-service`  
> **Sprint:** 4 — Booking Lifecycle + Inter-Service Communication  
> **Purpose:** مرجع تقني وعملي يشرح ما بنيناه، لماذا بنيناه بهذه الطريقة، وكيف تتفاعل الأجزاء مع بعضها قبل بدء الـ Testing.


> [!IMPORTANT]
> **Post-Sprint 4 Update — Sprint 4.5 completed**
>
> هذا الملف يبقى مرجعاً تاريخياً كاملاً لتصميم Sprint 4 كما تم بناؤه أول مرة باستخدام `RestClient`.
> في Sprint 4.5 تم استبدال transport adapter بـ **Spring Cloud OpenFeign** مع الحفاظ على نفس `FlightClient` business contract ونفس distributed failure semantics بدون تغيير `BookingServiceImpl`.
>
> **القرار النهائي بعد Sprint 4.5:**
> - `RestClient` → replaced by OpenFeign.
> - `Eureka` → لم تتم إضافتها حالياً؛ نرجع لها فقط إذا ظهرت حاجة فعلية للـservice discovery.
> - Automatic Feign retry → disabled.
> - Service JWT → preserved عبر Feign `RequestInterceptor`.
> - Definitive vs ambiguous failure semantics → preserved and regression-tested.
>
> المرجع الجديد: `BOOKING_SERVICE_SPRINT4_5_FEIGN_REFERENCE.md`
>
> **ملاحظة:** أي أقسام لاحقة في هذا الملف تتحدث عن `RestClient` أو تضع Eureka كخطة Sprint 4.5 يجب قراءتها كتاريخ Sprint 4، وليس كالحالة الحالية للنظام.


---

# 1. ما مسؤولية Booking Service؟

الـ `booking-service` لا يملك الرحلة ولا المقاعد الفعلية.

هو يملك:

- Booking lifecycle.
- Passenger information.
- Booking snapshot.
- Idempotency.
- Booking expiration.
- Cancel workflow.
- التنسيق مع `flight-service`.
- التعامل مع حالات الفشل بين قاعدة بيانات Booking وFlight Service.

أما `flight-service` فهو **Source of Truth للـ inventory**:

- عدد المقاعد.
- Fare Class.
- السعر الحالي وقت الحجز.
- Flight status.
- Reserve / Release seats.

قاعدة مهمة:

```text
Booking Service owns the booking.
Flight Service owns the seat inventory.
```

لا يوجد Foreign Key بين قواعد بيانات الخدمات.

---

# 2. الصورة الكاملة للـ Architecture

```text
Client
  |
  v
API Gateway
  |
  v
BookingController
  |
  v
BookingService (interface)
  |
  v
BookingServiceImpl                 <-- Application Orchestrator
  |
  +--> RequestHashService          <-- Idempotency fingerprint
  |
  +--> BookingTransactionService   <-- Short DB transactions / REQUIRES_NEW
  |
  +--> BookingRepository           <-- Persistence + CAS status update
  |
  +--> FlightClient                <-- Synchronous REST to flight-service
  |
  +--> BookingMapper               <-- Entity <-> DTO


Background flow:

BookingCleanupJob
  |
  v
BookingExpirationService
  |
  +--> BookingRepository
  +--> BookingTransactionService
  +--> FlightClient
```

الـ `BookingServiceImpl` اسمه عملياً **Orchestrator** لأنه ينسق عدة خطوات، لكنه ليس Saga كاملة بعد.

---

# 3. Package Structure

```text
com.project.bookingservice

client/
  dto/
    SeatOperationRequest
    SeatReservationResult
  FlightClient

config/
  OpenApiConfig
  RestClientConfig
  SecurityConfig

controller/
  BookingController

dto/
  request/
    BookingRequest
    PassengerRequest
  response/
    BookingResponse
    PassengerResponse

entity/
  Booking
  BookingPassenger

enums/
  BookingStatus
  Currency
  FareClassType
  UserRole

exception/
  BookingExceptionHandler
  BookingFinalizationException
  BookingProcessingException
  BookingReconciliationException
  BookingStateException
  FlightReservationRejectedException
  IdempotencyConflictException

mapper/
  BookingMapper

repository/
  BookingRepository

scheduler/
  BookingCleanupJob

security/
  InternalTokenProvider
  JwtAuthenticationFilter
  JwtValidator

service/
  BookingExpirationService
  BookingService
  BookingTransactionService
  RequestHashService

service/impl/
  BookingExpirationServiceImpl
  BookingServiceImpl
  BookingTransactionServiceImpl
  RequestHashServiceImpl
```

---

# 4. Booking State Machine

الحالات الأساسية في Sprint 4:

```java
public enum BookingStatus {
    IN_PROGRESS,
    PENDING,

    CANCELLING,
    EXPIRING,

    CANCELLED,
    EXPIRED,

    FAILED
}
```

وفي Sprint 5 سيظهر غالباً:

```text
CONFIRMED
```

بعد نجاح Payment.

## معنى كل حالة

| Status | المعنى |
|---|---|
| `IN_PROGRESS` | تم إنشاء Booking claim في DB، لكن الـ reserve لم يكتمل بعد |
| `PENDING` | المقاعد حُجزت والـBooking تنتظر الخطوة التالية/Payment، ولها TTL |
| `CANCELLING` | Cancel فاز بالـrace وبدأ release |
| `CANCELLED` | تم إلغاء الحجز وإطلاق المقاعد |
| `EXPIRING` | Scheduler فاز بالـrace وبدأ release |
| `EXPIRED` | انتهت مهلة الحجز وتم إطلاق المقاعد |
| `FAILED` | النتيجة غير مؤكدة وتحتاج reconciliation/manual review |

الرسم:

```text
                    create request
                         |
                         v
                   IN_PROGRESS
                    /       \
                   /         \
          reserve success    ambiguous failure
                 |                 |
                 v                 v
              PENDING            FAILED
              /    \
             /      \
       cancel        TTL expired
          |              |
          v              v
     CANCELLING       EXPIRING
          |              |
     release seats   release seats
          |              |
          v              v
      CANCELLED        EXPIRED
```

---

# 5. ما هو الـ Claim؟

كلمة `claim` ليست Entity جديدة وليست Table جديدة.

الـClaim هو **نفس Booking row** عندما تكون حالتها:

```text
IN_PROGRESS
```

مثال:

```text
Booking:
id             = B123
userId         = U10
flightId       = F20
fareClassId    = FC1
idempotencyKey = req-abc
requestHash    = ...
passengerCount = 2
status         = IN_PROGRESS
```

هذا الصف يقول:

> هذه العملية بدأت فعلاً، وهذا الـIdempotency-Key أصبح مملوكاً لهذا الـrequest.

لذلك:

```java
createClaim(...)
```

يعني عملياً:

```text
Create preliminary IN_PROGRESS Booking + COMMIT
```

و:

```java
finalizeBooking(...)
```

يعني:

```text
Update SAME Booking:
IN_PROGRESS -> PENDING
+ snapshot
+ expiresAt
```

و:

```java
deleteClaim(...)
```

يعني:

```text
Delete preliminary Booking only when we KNOW that reserve did not happen
or when compensation was confirmed successful.
```

لا نحذف Claim إذا كانت نتيجة remote operation غير مؤكدة.

---

# 6. لماذا Idempotency مهمة؟

المشكلة:

```text
User clicks Book
        |
network slow
        |
User clicks Book again
```

أو الـfrontend نفسه يعمل retry.

بدون Idempotency قد نحصل على:

```text
Request #1 -> reserve 2 seats
Request #2 -> reserve 2 seats

Result:
4 seats reserved accidentally
2 bookings created
```

الحل هو Header:

```http
Idempotency-Key: 7d6c...
```

والـBooking Service يخزن:

```text
userId + idempotencyKey + requestHash
```

وعندنا DB constraint:

```text
UNIQUE(user_id, idempotency_key)
```

## لماذا نحتاج `requestHash` أيضاً؟

لأن نفس المفتاح يمكن أن يُستخدم بالغلط مع request مختلف:

```text
Key = ABC
Request #1 = Flight F1 + Passenger Mahmoud

Key = ABC
Request #2 = Flight F2 + Passenger Ahmad
```

إذا اعتمدنا على key فقط، سنرجع booking قديمة لطلب مختلف.

لذلك:

```text
same key + same hash
    -> same logical request

same key + different hash
    -> 409 Conflict
```

---

# 7. RequestHashService بالتفصيل

`RequestHashService` مسؤول عن إنشاء **deterministic fingerprint** للـBookingRequest.

الـflow:

```text
BookingRequest
    |
normalize
    |
stable representation
    |
SHA-256
    |
64-char hash
```

## لماذا Normalization؟

مثلاً:

```text
"Mahmoud "
"Mahmoud"
```

منطقياً نفس الاسم، لكن بدون normalization سيعطون hashes مختلفة.

القواعد الحالية:

```text
trim(firstName)
trim(lastName)
uppercase(passportNumber)
uppercase(nationality)
dateOfBirth -> ISO string
passengers -> deterministic sorting
```

مثال:

```text
jo  -> JO
abc123 -> ABC123
```

Sorting يمنع اختلاف الـhash فقط لأن ترتيب passengers في JSON تغير:

```text
[A, B]
[B, A]
```

إذا business semantics تعتبر الترتيب غير مهم، يجب أن ينتج الاثنان نفس fingerprint.

## لماذا SHA-256؟

نحن لا نستخدمه لتشفير بيانات حساسة.

نستخدمه كـ:

```text
stable fingerprint
```

خصائصه المفيدة:

- سريع.
- deterministic.
- fixed size.
- collision probability منخفضة جداً عملياً.
- لا نحتاج تخزين request JSON كامل فقط للمقارنة.

---

# 8. أهم Columns في Booking Entity ولماذا

## `id`

هو Booking identifier.

```text
UUID
```

مناسب للخدمات الموزعة لأنه لا يعتمد على auto-increment مشترك.

## `userId`

Reference فقط إلى المستخدم في Auth Service.

```text
NO cross-service foreign key
```

السبب: كل Service تملك DB مستقلة.

## `flightId` و `fareClassId`

References إلى Flight Service.

```text
IDs only
No DB foreign keys across services
```

## `status`

قلب الـstate machine، ويستخدم في create/cancel/scheduler/reconciliation/Payment لاحقاً.

## `idempotencyKey`

المفتاح القادم من Client. وظيفته منع تنفيذ نفس command مرتين.

## `requestHash`

يتأكد أن إعادة استخدام نفس Idempotency-Key تمثل نفس الـrequest فعلاً.

## `passengerCount`

يتم حسابه Server-side:

```java
request.getPassengers().size()
```

ثم يُخزن.

**لا نثق بقيمة قادمة من العميل.**

لماذا نخزنه؟ لأن Cancel وExpiration يحتاجان فقط عدد المقاعد المطلوب release لها.

بدون هذا العمود سنضطر لتحميل:

```text
booking.passengers
```

كل مرة فقط لنعرف `.size()`.

وهذا خصوصاً في Scheduler قد يؤدي إلى Lazy Loading problems.

إذن هو intentional denormalization:

```text
calculated once
stored once
used safely later
```

## Snapshot Fields

مثل:

```text
flightNumber
originIata
destinationIata
departureTime
arrivalTime
fareClassType
priceAtBooking
currency
```

Booking يجب أن تحتفظ بالحقيقة **وقت الحجز**.

إذا تغير Flight أو السعر لاحقاً، تاريخ الحجز لا يجب أن يتغير معه.

## `expiresAt`

وقت انتهاء الـPENDING booking.

Scheduler يبحث عن:

```text
status = PENDING
AND expiresAt < now
```

## `@Version version`

Optimistic Locking.

وعندنا أيضاً في JPQL CAS update:

```java
b.version = b.version + 1
```

لأن bulk JPQL لا يزيد `@Version` تلقائياً مثل managed entity update.

## `createdAt` / `updatedAt`

لـ audit, ordering, debugging, monitoring وfuture reconciliation.

---

# 9. BookingPassenger ولماذا داخل Aggregate

`BookingPassenger` child للـBooking.

```text
Booking
  |
  +--- BookingPassenger
  +--- BookingPassenger
```

Booking هي Aggregate Root.

لذلك لا نحتاج حالياً `BookingPassengerRepository`.

كل lifecycle للـpassengers يمر من Booking، و`CascadeType.ALL` يسمح بحفظ passengers مع Booking نفسها.

---

# 10. BookingRepository

أهم methods:

```java
Optional<Booking> findByUserIdAndIdempotencyKey(...);

Page<Booking> findByUserId(...);

Optional<Booking> findByIdAndUserId(...);

List<Booking> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(...);

int transitionStatus(
    UUID bookingId,
    BookingStatus expectedStatus,
    BookingStatus newStatus
);
```

## أهم Method: `transitionStatus`

فكر فيها كـ Compare-And-Swap:

```sql
UPDATE bookings
SET status = :newStatus,
    version = version + 1
WHERE id = :bookingId
  AND status = :expectedStatus;
```

إذا `updatedRows = 1` فأنت فزت بالـtransition. إذا `0` فالحالة تغيرت قبلك.

---

# 11. Race Condition بين Cancel وScheduler

تخيل Booking `PENDING` ووصل وقت expiration، وفي نفس اللحظة المستخدم ضغط Cancel.

بدون CAS:

```text
Cancel sees PENDING
Scheduler sees PENDING

Cancel -> release
Scheduler -> release

DOUBLE RELEASE
```

لهذا نستخدم intermediate states.

Cancel يحاول:

```text
PENDING -> CANCELLING
```

Scheduler يحاول:

```text
PENDING -> EXPIRING
```

واحد فقط يستطيع تنفيذ:

```sql
UPDATE ... WHERE status='PENDING'
```

---

# 12. لماذا BookingTransactionService؟

هذه من أهم أفكار Sprint 4.

المشكلة ليست فقط "نحتاج @Transactional".

المشكلة هي:

> لا نريد Local DB Transaction مفتوحة أثناء Remote HTTP Call.

النمط السيئ:

```java
@Transactional
public void createBooking() {

    repository.save(...);

    flightClient.reserveSeats(); // Network call داخل DB transaction

    repository.save(...);
}
```

لو Flight Service تأخرت، DB transaction تبقى مفتوحة.

النتائج المحتملة:

- DB connections occupied.
- locks أطول.
- connection pool exhaustion.
- rollback scope أكبر.
- distributed failure أصعب.

## تصميمنا

```text
TX #1
create IN_PROGRESS claim
COMMIT
|
v
REST reserveSeats
(no Booking DB transaction)
|
v
TX #2
finalize PENDING
COMMIT
```

---

# 13. لماذا `REQUIRES_NEW`؟

كل DB step لها transaction مستقلة.

```text
createClaim()
    -> TX begins
    -> INSERT
    -> flush
    -> method returns
    -> COMMIT
```

ثم بعد الـcommit نذهب إلى Flight Service.

ملاحظة مهمة:

```text
saveAndFlush != commit
```

`saveAndFlush()` يجبر Hibernate على إرسال SQL للـDB، لكن الـcommit الحقيقي يحصل عند نهاية الـtransaction الناجحة، أي عند الخروج من method التي عليها `REQUIRES_NEW`.

---

# 14. لماذا Interface + Impl؟

عندنا:

```text
BookingService / BookingServiceImpl
BookingTransactionService / BookingTransactionServiceImpl
RequestHashService / RequestHashServiceImpl
BookingExpirationService / BookingExpirationServiceImpl
```

الفوائد:

- dependency على abstraction.
- testing/mocking أسهل.
- implementation يمكن أن يتغير.
- responsibilities واضحة.
- consistency داخل المشروع.

---

# 15. BookingServiceImpl كـ Orchestrator

`BookingServiceImpl` ينسق:

```text
Hash
DB Claim
Flight Reserve
Snapshot
DB Finalization
Compensation
Response Mapping
```

## Create Booking — Happy Path

```text
1. Validate request
2. Compute requestHash
3. Search existing idempotency key
4. Create IN_PROGRESS claim in TX #1
5. COMMIT
6. REST reserveSeats()
7. Receive authoritative snapshot
8. Apply snapshot
9. status = PENDING
10. expiresAt = now + 10 minutes
11. Finalize in TX #2
12. COMMIT
13. Return BookingResponse
```

---

# 16. Concurrent Idempotency Race

الـSELECT الأول ليس كافياً.

```text
A: SELECT -> not found
B: SELECT -> not found

A: INSERT claim -> success
B: INSERT claim -> UNIQUE constraint violation
```

لهذا `UNIQUE(user_id, idempotency_key)` هو خط الدفاع النهائي.

الـService يمسك `DataIntegrityViolationException`، يقرأ الـwinner ثم يقرر:

```text
same hash + completed booking -> return existing
same hash + IN_PROGRESS -> processing
different hash -> 409
FAILED -> reconciliation
```

---

# 17. Definitive Failure vs Ambiguous Failure

## Definitive Failure

Flight Service ردت بشكل مؤكد، مثل business `400 / 404 / 409`.

هذا يعني أن Flight رفضت الـreserve.

إذن:

```text
deleteClaim() is safe
```

## Ambiguous Failure

مثل:

```text
timeout
connection reset
5xx where the server may have already changed state
invalid/empty response after request execution
```

لا نستطيع الجزم هل Flight حجزت المقاعد.

لذلك:

```text
DO NOT retry blindly.
DO NOT delete claim blindly.
IN_PROGRESS -> FAILED
manual reconciliation
```

---

# 18. FlightClient

المسؤولية:

```text
Booking Service -> HTTP -> Flight Service
```

Methods:

```java
SeatReservationResult reserveSeats(...);
void releaseSeats(...);
```

## Reserve error semantics

```text
Known business 4xx
    -> FlightReservationRejectedException
    -> Booking may delete claim

5xx / timeout / network
    -> technical failure
    -> Booking treats result as ambiguous
    -> FAILED
```

## Release مختلفة

`releaseSeats` non-idempotent حالياً.

لو Flight عملت release ثم response ضاعت، retry قد يزيد availability مرة ثانية.

لذلك:

```text
NO blind retry
```

وفي Cancel/Expiry نترك `CANCELLING` أو `EXPIRING` عند النتيجة غير المؤكدة ونطلب reconciliation.

---

# 19. لماذا لا يوجد `getAvailability()` قبل reserve؟

لأن هذا يخلق TOCTOU:

```text
check seats = 2 available
      |
another request takes them
      |
reserve seats -> fails
```

الـcheck لا يعطي ضمان.

الأصح أن `reserveSeats()` نفسها تتحقق وتنفذ atomically قدر الإمكان داخل Flight Service.

---

# 20. SeatReservationResult كـ Local Mirror

Booking Service لا تستورد DTO من Flight Service.

لدينا local DTO:

```text
client.dto.SeatReservationResult
```

هذا يحافظ على:

```text
Bounded Context
Loose Coupling
Independent deployment
```

---

# 21. RestClient — لماذا استخدمناه؟

نحتاج synchronous request:

```text
Booking wants to reserve seats NOW
and return result to user NOW.
```

لهذا REST مناسب، و`RestClient` هو HTTP client حديث في Spring للطلبات synchronous.

---

# 22. RestClient vs Eureka

`RestClient` وEureka **ليسوا بديلين عن بعض**.

## RestClient

يجيب على:

> كيف أرسل HTTP request إلى Service أخرى؟

حالياً:

```properties
flight-service.url=http://localhost:7172
```

## Eureka

تجيب على:

> أين توجد instances الخاصة بـ flight-service؟

هي Service Discovery.

بدل fixed host/port نستخدم logical service name ويتم resolve إلى instance متاحة.

---

# 23. لماذا لم نستخدم Eureka الآن؟

Sprint 4 هدفه كان:

```text
Booking lifecycle
inter-service REST
idempotency
transactions
race conditions
scheduler
compensation
```

حالياً لدينا instance واحدة وport معروف، لذلك fixed URL كافية للتعلم والـMVP.

إدخال Eureka الآن كان سيخلط Business complexity مع Infrastructure complexity.

---

# 24. متى سنضيف Eureka؟

الخطة:

```text
Sprint 4     Booking
Sprint 4.5   Eureka / Service Discovery
Sprint 5     Payment
```

بعد Eureka:

```text
Discovery Server :8761

Auth Service       -> registers
Flight Service     -> registers
Booking Service    -> registers
Gateway            -> registers
Payment Service    -> registers
```

RestClient سيبقى موجوداً، لكن طريقة الوصول تتغير:

```text
Before Eureka:
RestClient -> http://localhost:7172

After Eureka:
RestClient -> flight-service
           -> Discovery/Load Balancer
           -> actual instance
```

---

# 25. Eureka ليست Security

```text
Eureka tells us WHERE the service is.
JWT tells us WHO is calling.
```

Service JWT سيبقى مهماً حتى بعد Eureka.

---

# 26. InternalTokenProvider

Booking Service تتصل بـ `/internal/**` في Flight Service.

لا نمرر Customer JWT.

ننشئ Service JWT:

```text
sub  = booking-service
type = SERVICE
exp  = short lived
```

Flight Service تتحقق من caller كخدمة.

---

# 27. RestClient Interceptor

بدل وضع Authorization header يدوياً بكل method، وضعناه في `RestClientConfig` interceptor.

كل outbound request يحصل على Service JWT تلقائياً.

```text
FlightClient
    = business HTTP operations

RestClientConfig
    = technical HTTP configuration/authentication
```

---

# 28. Security Layer

Inbound JWT يمر عبر:

```text
JwtAuthenticationFilter
        |
JwtValidator
        |
Authentication
```

Controller يأخذ userId من:

```java
authentication.getName()
```

أي من JWT `sub`، وليس من body يقدر المستخدم يزوره.

---

# 29. لماذا `UserRole` enum محلي؟

أفضل من String لأنه يعطي compile-time safety ويمنع typos.

ومحلي لأن كل service لها bounded context خاص بها.

---

# 30. Controller Design

| Method | Endpoint | Authority | Use Case |
|---|---|---|---|
| POST | `/api/bookings` | CUSTOMER | Create |
| GET | `/api/bookings/my` | CUSTOMER | My bookings |
| GET | `/api/bookings/my/{bookingId}` | CUSTOMER | My booking details |
| POST | `/api/bookings/{bookingId}/cancel` | CUSTOMER | Cancel |
| GET | `/api/bookings/{bookingId}` | ADMIN | Any booking |
| GET | `/api/bookings` | ADMIN | All bookings |

Cancel هو POST وليس DELETE لأننا نغيّر state ولا نحذف resource.

---

# 31. Cancel Workflow

```text
1. Find booking owned by user
2. PENDING -> CANCELLING atomically
3. COMMIT
4. releaseSeats()
5. CANCELLING -> CANCELLED atomically
6. return response
```

Cancel مرتين:

```text
CANCELLED -> return existing booking
```

بدون second release.

---

# 32. BookingCleanupJob — ما فائدته؟

`PENDING` لها TTL، مثلاً 10 دقائق.

إذا المستخدم لم يكمل العملية، لا نريد المقاعد محجوزة للأبد.

لذلك Job يفحص دورياً:

```text
PENDING bookings where expiresAt < now
```

ثم يعمل release.

---

# 33. `@Scheduled` وCron — ماذا استخدمنا؟

استخدمنا:

```java
@Scheduled(fixedDelay = 60_000)
```

وليس Cron expression.

## fixedDelay

ينتظر 60 ثانية بعد انتهاء run السابق.

## fixedRate

يعتمد على معدل التشغيل من start times، وقد يكون أقل ملاءمة لو العمل يطول.

## Cron

نستخدمه عندما نريد وقتاً تقويمياً:

```text
Every day at 02:00
Every Monday at 08:00
```

Expiration لا تحتاج ساعة محددة، بل polling دوري، لذلك `fixedDelay` أنسب.

---

# 34. BookingExpirationService

فصلناه عن `BookingService`:

```text
BookingService
    -> HTTP business use cases

BookingExpirationService
    -> background system use case
```

هذا يفصل API concerns عن system maintenance.

---

# 35. Expiration Flow

```text
PENDING + expired
      |
PENDING -> EXPIRING
      |
 releaseSeats
      |
EXPIRING -> EXPIRED
```

إذا release outcome غير مؤكد:

```text
leave EXPIRING
CRITICAL log
manual reconciliation
```

لا retry أعمى.

---

# 36. لماذا Batch Size؟

نأخذ عدداً محدوداً مثل 100 per run.

الفوائد:

- memory safety.
- bounded load.
- لا نضغط Flight Service دفعة واحدة.
- الأقدم أولاً عبر `ORDER BY expiresAt ASC`.

---

# 37. Booking-specific Exception Handling

لدينا `BookingExceptionHandler` للخطاء الخاصة بالـBooking، بينما `common-lib` يحتفظ بالاستثناءات العامة المشتركة.

أهم الحالات:

```text
IdempotencyConflictException
BookingProcessingException
BookingStateException
BookingFinalizationException
BookingReconciliationException
FlightReservationRejectedException
```

---

# 38. معنى أهم Exceptions

| Exception | المعنى التقريبي |
|---|---|
| `IdempotencyConflictException` | same key + different payload |
| `BookingProcessingException` | العملية ما زالت processing |
| `BookingStateException` | command غير مسموح من state الحالية |
| `BookingFinalizationException` | finalization فشلت بشكل مؤكد |
| `BookingReconciliationException` | outcome غير معروف ويحتاج reconciliation |
| `FlightReservationRejectedException` | Flight رفضت reserve بشكل مؤكد |

---

# 39. Finalize Failure — لماذا نتحقق من DB أولاً؟

قد يحصل:

```text
DB COMMIT PENDING succeeds
but response/connection fails
```

Java ترى exception، لكن DB قد تكون PENDING فعلاً.

لو عوضنا فوراً بـrelease سنفسد consistency.

لذلك:

```text
finalize exception
      |
verify DB
      |
+-----+-------------+
|                   |
PENDING         IN_PROGRESS
|                   |
return success    compensate
```

إذا DB نفسها غير قابلة للتحقق:

```text
do not compensate blindly
-> reconciliation
```

---

# 40. Compensation

Compensation ليست Distributed Rollback.

هي business action معاكسة:

```text
reserveSeats
   |
compensation
   v
releaseSeats
```

في Sprint 4 هي best-effort compensation.

---

# 41. لماذا لا نعمل Retry تلقائي؟

لأن reserve/release ليست idempotent بالكامل حالياً.

إذا operation نجحت والresponse ضاعت، retry قد يكرر side effect.

لذلك:

```text
unknown outcome
-> no blind retry
-> reconciliation
```

---

# 42. أين تدخل Saga لاحقاً؟

حالياً لدينا:

```text
Synchronous orchestration
+
Best-effort compensation
```

ليست Saga كاملة بعد.

مع Payment:

```text
Reserve Seats
    |
Charge Payment
    |
Confirm Booking
```

لو Payment يفشل، نحتاج compensation.

لو Payment نجح لكن confirmation فشل، نحتاج durable workflow/recovery.

هنا Saga تصبح مناسبة.

---

# 43. Saga Orchestration المستقبلية

```text
Booking Saga Orchestrator
        |
        +--> Reserve Inventory
        |
        +--> Charge Payment
        |
        +--> Confirm Booking
        |
        +--> Publish BookingConfirmed
```

Compensations:

```text
Reserve -> Release
Charge  -> Refund
```

---

# 44. أين يدخل Kafka + Outbox؟

لاحقاً، وليس في Sprint 4.

Outbox يحل:

```text
DB commit succeeds
Kafka publish fails
```

فنحفظ Booking وOutbox event في نفس local transaction، ثم ننشر الحدث بشكل موثوق لاحقاً.

---

# 45. الفرق بين التقنيات التي استخدمناها

```text
Idempotency
    -> duplicate commands

Local Transactions
    -> consistency داخل DB واحدة

CAS / Optimistic Locking
    -> concurrent state changes

Compensation
    -> reverse remote business action

Saga
    -> distributed workflow

Outbox
    -> reliable DB-to-message publishing

Kafka
    -> asynchronous event transport

Eureka
    -> service discovery

RestClient
    -> synchronous HTTP client
```

---

# 46. لماذا لا نستخدم 2PC؟

نحن نحافظ على استقلال الخدمات.

Distributed ACID transaction بين Booking DB وFlight DB تزيد coupling والتعقيد.

لذلك نستخدم:

```text
local transactions
+
state machine
+
idempotency
+
compensation
+
future Saga/Outbox
```

---

# 47. API Gateway

Gateway مسؤول عن external routing:

```text
/api/bookings/**
    -> booking-service
```

لكن `/internal/**` في Flight لا نعمل لها Gateway route.

---

# 48. ما الذي لم نستخدمه بعد ولماذا؟

## Redis
ليس مطلوباً بعد؛ الـScheduler يغطي TTL الحالي.

## Kafka
ليس ضرورياً للـreserve synchronous الحالي؛ سيدخل للأحداث والـasync workflows.

## Elasticsearch
ليس جزءاً من Booking lifecycle.

## Payment
Sprint 5، وسيضيف `PENDING -> CONFIRMED`.

---

# 49. أهم Design Principles

```text
1. Database per service.
2. No cross-service foreign keys.
3. Booking owns booking lifecycle.
4. Flight owns inventory.
5. No business DTOs in common-lib.
6. Local enums داخل bounded context.
7. REST synchronous when immediate answer is required.
8. No remote HTTP inside long DB transaction.
9. Idempotency enforced at DB level.
10. Atomic CAS transitions protect races.
11. Unknown distributed outcomes are never guessed.
12. No blind retry for non-idempotent side effects.
13. Scheduler uses bounded batches.
14. Internal endpoints are not exposed via Gateway.
15. Service-to-service calls use Service JWT.
16. Add infrastructure only when there is a real reason.
```

---

# 50. أهم Scenarios قبل Testing

## Happy Path

```text
POST booking
-> IN_PROGRESS
-> reserve success
-> PENDING
-> 201
```

## Same key + same request

```text
return same booking
```

## Same key + different request

```text
409
```

## Concurrent same-key requests

```text
one claim wins
second hits UNIQUE and loads winner
```

## Not enough seats

```text
definitive rejection
-> delete claim
```

## Flight timeout during reserve

```text
outcome unknown
-> FAILED
-> reconciliation
```

## Finalize exception but DB already PENDING

```text
verify DB
-> return success
```

## Finalize failed and DB IN_PROGRESS

```text
try release compensation
```

Release confirmed:

```text
delete claim
```

Release unknown:

```text
FAILED
reconciliation
```

## Cancel

```text
PENDING -> CANCELLING -> release -> CANCELLED
```

## Cancel twice

```text
return existing CANCELLED
no second release
```

## Cancel vs Expiry

```text
only one wins CAS from PENDING
```

## Scheduler

```text
PENDING expired -> EXPIRING -> release -> EXPIRED
```

---

# 51. Testing Checklist

## Security

- No JWT -> 401.
- CUSTOMER create -> allowed.
- CUSTOMER own booking -> allowed.
- CUSTOMER other user's booking -> 404.
- ADMIN get all -> allowed.
- Internal Flight endpoint not exposed by Gateway.
- User JWT cannot invoke internal inventory endpoint.
- Booking Service JWT can invoke it.

## Create

- Happy path.
- Same key + same payload.
- Same key + different payload.
- Two concurrent requests same key.
- Not enough seats.
- Invalid flight.
- Flight unavailable.
- Timeout/ambiguous reserve.
- Empty/invalid Flight response.
- Finalize technical failure.
- Finalize ambiguous commit result.

## Cancel

- PENDING -> CANCELLED.
- Cancel twice.
- Cancel EXPIRED.
- Cancel while Scheduler wins.
- Release failure.
- Final status transition failure.

## Scheduler

- PENDING not expired -> untouched.
- PENDING expired -> EXPIRED.
- Batch limited.
- Oldest expiresAt first.
- Cancel vs expire concurrency.
- Release failure leaves EXPIRING.

## Repository/Concurrency

- `transitionStatus()` returns 1 when expected state matches.
- returns 0 when state already changed.
- `version` increments.
- unique `(user_id, idempotency_key)` works.

---

# 52. Roadmap

```text
Sprint 4     Booking core
    |
Sprint 4.5   Eureka / Service Discovery
    |
Sprint 5     Payment
    |
Sprint 6     Kafka + Outbox
    |
Sprint 7     Notification
    |
Sprint 8     Redis
    |
Sprint 9     Search
    |
Sprint 10    Analytics + Observability
    |
Sprint 11    Docker / CI-CD / Deployment
```

---

# 53. الخلاصة الذهنية

```text
1. CLAIM FIRST
   أنشئ IN_PROGRESS قبل remote side effect.

2. COMMIT BEFORE NETWORK
   لا تترك DB transaction مفتوحة أثناء REST.

3. RESERVE IS THE SOURCE OF TRUTH
   لا تعمل availability pre-check ثم reserve.

4. CLAIM STATE BEFORE RELEASE
   PENDING -> CANCELLING / EXPIRING
   حتى لا يحدث double release.

5. NEVER GUESS AN UNKNOWN OUTCOME
   إذا لا نعرف هل remote side effect حدث:
   لا نحذف، لا نعيد المحاولة أعمى، بل نستخدم FAILED/reconciliation.
```

---

# 54. Interview-Level Explanation

إذا سُئلت:

> كيف صممت Booking Service؟

جواب مختصر:

> صممت Booking Service كـapplication orchestrator فوق local PostgreSQL transactions وsynchronous REST مع Flight Service. استخدمت DB-level idempotency عبر unique `(userId, idempotencyKey)` مع deterministic SHA-256 request hash لمنع duplicate booking requests، وطبقت short `REQUIRES_NEW` transaction boundaries حتى لا تبقى DB transaction مفتوحة أثناء network calls.
>
> Inventory بقي owned by Flight Service، والـBooking تعتمد على atomic reserve بدلاً من availability pre-check لتجنب TOCTOU. للـcancel والـexpiry استخدمت compare-and-swap state transitions مثل `PENDING -> CANCELLING` و`PENDING -> EXPIRING` حتى يفوز actor واحد فقط ويُمنع double release.
>
> في distributed failures أفرق بين definitive rejection وambiguous outcome؛ لا أعمل blind retry على non-idempotent reserve/release، بل أحفظ `FAILED` أو transitional state للتعامل معها لاحقاً عبر reconciliation. في Sprint لاحق ستتطور orchestration إلى Saga مع Payment، ثم Outbox/Kafka للأحداث الموثوقة.

---

**Booking Service — Sprint 4 Complete Architecture & Business Reference**
