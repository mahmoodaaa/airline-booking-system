# Booking Service — Sprint 4.5 OpenFeign Reference

> **Project:** Airline Booking System  
> **Service:** `booking-service`  
> **Sprint:** 4.5 — Declarative Inter-Service Communication  
> **Status:** **CLOSED ✅**  
> **Main change:** `RestClient` → `Spring Cloud OpenFeign`  
> **Business behavior change:** **NONE**  
> **Eureka:** **NOT ADDED**  
> **Automatic retry:** **DISABLED**  
> **Regression testing:** **PASSED**

---

# 1. لماذا Sprint 4.5 موجود أصلاً؟

Sprint 4 أنهى الـBooking lifecycle والـdistributed failure semantics:

```text
IN_PROGRESS
    |
    +--> reserve success --> PENDING
    |
    +--> ambiguous result --> FAILED

PENDING
    |
    +--> CANCELLING --> release --> CANCELLED
    |
    +--> EXPIRING   --> release --> EXPIRED
```

كما ثبّت:

- Idempotency.
- Durable claim قبل الـremote side effect.
- `REQUIRES_NEW` transaction boundaries.
- عدم إبقاء DB transaction مفتوحة أثناء HTTP call.
- التمييز بين definitive rejection وambiguous outcome.
- No blind retry للـreserve/release.
- Compensation + reconciliation.
- Atomic state transitions لمنع double release.

Sprint 4.5 **لم يغيّر أي قرار Business من هذه القرارات**.

الهدف الوحيد:

```text
Change HOW booking-service calls flight-service

WITHOUT changing WHAT BookingServiceImpl expects.
```

---

# 2. القرار النهائي

قبل Sprint 4.5:

```text
BookingServiceImpl
        |
        v
FlightClient (RestClient implementation)
        |
        v
http://localhost:7172
        |
        v
flight-service
```

بعد Sprint 4.5:

```text
BookingServiceImpl
        |
        | depends only on abstraction
        v
FlightClient
  (interface)
        |
        v
FeignFlightClient
  (adapter)
        |
        v
FlightFeignClient
  (@FeignClient)
        |
        | HTTP
        v
flight-service
```

النتيجة المهمة:

```text
BookingServiceImpl did not need to know that RestClient was replaced by Feign.
```

وهذا هو الدليل العملي أن abstraction boundary صحيحة.

---

# 3. لماذا اخترنا OpenFeign؟

اخترنا `Spring Cloud OpenFeign` للأسباب التالية:

- Declarative interface-based HTTP client.
- يقلل boilerplate مقارنة ببناء request يدوي.
- منتشر جداً في Spring microservices والـenterprise codebases.
- مهم للمقابلات وفهم مشاريع موجودة فعلياً.
- يسمح بعزل:
  - authentication interceptor
  - error decoding
  - timeout configuration
  - retry policy
  - HTTP mapping

مثال الاستخدام النهائي:

```java
@FeignClient(
        name = "flight-service",
        url = "${flight-service.url}",
        configuration = FlightFeignConfig.class
)
public interface FlightFeignClient {
    ...
}
```

## ملاحظة تقنية

اختيار Feign هنا اختيار واعٍ للتعلم والـenterprise exposure.

في مشاريع Spring الجديدة يمكن أيضاً التفكير لاحقاً في:

```text
Spring HTTP Service Clients / @HttpExchange
```

لكن لم نغيّر القرار في هذا السبرنت لأن هدف Sprint 4.5 هو تعلم Feign بدون إعادة تصميم الـbusiness layer.

---

# 4. لماذا لم نستخدم Eureka؟

Feign وEureka **ليسا نفس الشيء**.

```text
Feign
    -> كيف أرسل synchronous HTTP request؟

Eureka
    -> أين توجد instance الخاصة بالخدمة؟
```

Feign تعمل بدون Eureka:

```properties
flight-service.url=http://localhost:7172
```

ثم:

```java
@FeignClient(
    name = "flight-service",
    url = "${flight-service.url}"
)
```

حالياً لدينا عدد محدود من الخدمات والـaddresses معروفة، لذلك لا توجد مشكلة Service Discovery حقيقية تحتاج Eureka.

## القرار

```text
OpenFeign = YES
Eureka    = NO, for now
```

Eureka يمكن العودة لها مستقبلاً إذا ظهر سبب فعلي، مثل:

- Dynamic service instances.
- Client-side discovery requirement.
- بيئة deployment تحتاج registry مستقلة.
- عدة instances تتغير عناوينها باستمرار.

وفي Docker/Kubernetes قد يقوم الـplatform نفسه بجزء كبير من service discovery، لذلك عدد الـmicroservices وحده ليس سبباً كافياً لإضافة Eureka.

---

# 5. الـPackage Structure بعد Sprint 4.5

```text
com.project.bookingservice

client/
├── FlightClient.java
│
├── dto/
│   ├── SeatOperationRequest.java
│   └── SeatReservationResult.java
│
└── feign/
    ├── FlightFeignClient.java
    ├── FeignFlightClient.java
    ├── FlightClientErrorDecoder.java
    ├── FlightFeignConfig.java
    └── ServiceJwtRequestInterceptor.java
```

القاعدة:

```text
client/FlightClient
    = contract يفهمه application/business layer

client/feign/*
    = transport/infrastructure details
```

---

# 6. FlightClient — الـBusiness-Facing Contract

```java
public interface FlightClient {

    SeatReservationResult reserveSeats(
            UUID flightId,
            UUID fareClassId,
            int count
    );

    void releaseSeats(
            UUID flightId,
            UUID fareClassId,
            int count
    );
}
```

`BookingServiceImpl` يعتمد فقط على هذه interface.

هو لا يعرف:

- Feign.
- `FeignException`.
- `RetryableException`.
- `ErrorDecoder`.
- HTTP serialization details.
- Authentication interceptor details.

وهذا يحقق:

```text
Dependency on abstraction
+
Transport replaceability
+
Clean business boundary
```

---

# 7. FlightFeignClient — Declarative HTTP Contract

هذه interface تمثل Flight internal inventory endpoints مباشرة:

```java
@FeignClient(
        name = "flight-service",
        url = "${flight-service.url}",
        configuration = FlightFeignConfig.class
)
public interface FlightFeignClient {

    @PostMapping(
        "/internal/flights/{flightId}/fare-classes/{fareClassId}/reserve"
    )
    ApiResponse<SeatReservationResult> reserveSeats(
            @PathVariable UUID flightId,
            @PathVariable UUID fareClassId,
            @RequestBody SeatOperationRequest request
    );

    @PostMapping(
        "/internal/flights/{flightId}/fare-classes/{fareClassId}/release"
    )
    void releaseSeats(
            @PathVariable UUID flightId,
            @PathVariable UUID fareClassId,
            @RequestBody SeatOperationRequest request
    );
}
```

هذه interface ليست Business Service.

هي HTTP contract.

```text
FlightClient
    !=
FlightFeignClient
```

الأولى abstraction للـBooking.

الثانية Feign transport contract.

---

# 8. FeignFlightClient — Adapter

`FeignFlightClient` هو adapter بين العالمين:

```text
Booking semantics
        |
        v
FeignFlightClient
        |
        v
Feign semantics
```

مسؤولياته:

- Call `FlightFeignClient`.
- Validate successful response.
- Catch Feign transport exceptions.
- Translate technical failures إلى exceptions يفهمها Booking flow.
- Preserve Sprint 4 failure semantics.

## Reserve Success

```text
Feign call
   |
   v
2xx + valid ApiResponse
   |
   v
return SeatReservationResult
```

## Invalid Success Response

إذا حصل:

```text
HTTP 2xx
but response == null
or response.data == null
```

لا نفترض أن الـreserve لم يحدث.

لأن الـremote side effect قد يكون نُفذ.

إذن:

```text
invalid 2xx response
    -> ambiguous
    -> ServiceUnavailableException
    -> Booking marks FAILED
    -> reconciliation
```

---

# 9. أهم قاعدة في Sprint 4.5

تغيير HTTP library **لا يجوز أن يغيّر distributed semantics**.

هذه هي القاعدة:

```text
Definitive rejection
    -> reserve definitely did NOT happen
    -> claim can be deleted safely

Ambiguous outcome
    -> reserve MAY have happened
    -> do not delete
    -> do not retry
    -> FAILED / reconciliation
```

---

# 10. Error Mapping النهائي

## Mapping Table

| Downstream result | Meaning | Exception exposed to Booking layer | Booking action |
|---|---|---|---|
| `400 Bad Request` | Definitive business rejection | `FlightReservationRejectedException(400)` | Delete claim |
| `404 Not Found` | Definitive business rejection | `FlightReservationRejectedException(404)` | Delete claim |
| `409 Conflict` | Definitive business rejection | `FlightReservationRejectedException(409)` | Delete claim |
| Flight `401/403` for SERVICE JWT | Definitive internal authentication rejection before business logic | `FlightReservationRejectedException(502)` | Delete claim |
| Flight `5xx` | Outcome may be unknown | `ServiceUnavailableException` | `FAILED` / reconciliation |
| Connection refused | Outcome unknown from Booking perspective | `ServiceUnavailableException` | `FAILED` / reconciliation |
| Timeout | Outcome unknown | `ServiceUnavailableException` | `FAILED` / reconciliation |
| Decode/malformed response | Cannot reconstruct authoritative result | `ServiceUnavailableException` | `FAILED` / reconciliation |
| Empty/null successful response | Contract invalid after remote call | `ServiceUnavailableException` | `FAILED` / reconciliation |

---

# 11. لماذا 400 / 404 / 409 Definitive؟

إذا Flight Service ردت business response واضحة مثل:

```text
400 Fare class does not belong to flight
404 Fare class not found
409 Not enough seats
409 Flight already departed
```

فهذا يعني أن Flight Service **رفضت العملية**.

إذن:

```text
reserve did not happen
```

وبالتالي:

```text
deleteClaim() is safe
```

---

# 12. لماذا 5xx Ambiguous؟

مثال:

```text
Booking -> Flight reserve
Flight decrements seats
Flight crashes before response completes
Booking receives 500/503
```

Booking لا تستطيع أن تقول:

```text
reserve failed
```

ولا تستطيع أن تقول:

```text
reserve succeeded
```

إذن:

```text
UNKNOWN OUTCOME
```

والتصرف الآمن:

```text
IN_PROGRESS -> FAILED
manual reconciliation
```

لا:

```text
delete claim
```

ولا:

```text
retry reserve
```

---

# 13. Timeout / Connection Failure

في RestClient كنا نتعامل مع:

```text
ResourceAccessException
```

بعد Feign أصبح transport failure عادة يظهر من خلال Feign-specific exceptions مثل:

```text
RetryableException
```

لكن الـbusiness layer لا يجب أن يعرف هذا.

`FeignFlightClient` يترجمه إلى:

```text
ServiceUnavailableException
```

ومن ثم يظل `BookingServiceImpl` يستخدم نفس المسار القديم:

```text
ambiguous
-> FAILED
-> reconciliation
```

---

# 14. DecodeException

حالة مهمة جداً لأننا واجهنا مشكلة مشابهة فعلياً في Sprint 4.

القاعدة:

```text
A response was received,
but Booking cannot safely reconstruct the authoritative result.
```

لذلك:

```text
DecodeException
    -> ambiguous
    -> ServiceUnavailableException
    -> reconciliation
```

التعليق الصحيح:

```java
// A response was received but its body could not be decoded.
// From Booking's perspective we cannot safely reconstruct the
// authoritative result, so treat the outcome as ambiguous.
```

لا نفترض نجاحاً أو فشلاً بلا دليل.

---

# 15. FlightClientErrorDecoder

الـ`ErrorDecoder` مسؤول عن HTTP responses غير الناجحة.

الـfinal semantics:

```text
400 / 404 / 409
    -> FlightReservationRejectedException(original status)

401 / 403 from internal SERVICE authentication
    -> FlightReservationRejectedException(502 BAD_GATEWAY)

5xx / unexpected remote server errors
    -> ServiceUnavailableException
```

## لماذا 401/403 تتحول إلى 502؟

الـCustomer JWT ليست المشكلة.

المشكلة هي:

```text
booking-service
    |
    | SERVICE JWT
    v
flight-service
    -> rejects internal authentication
```

لو أرجعنا للـCustomer:

```text
401 Unauthorized
```

قد يفهم أن Customer token تبعه هو المرفوض.

لكن الحقيقة:

```text
internal service-to-service authentication failed
```

لذلك نعرض:

```text
502 BAD_GATEWAY
```

---

# 16. لماذا 401/403 تعتبر Definitive؟

Flight Security ترفض request قبل الوصول للـController / inventory business logic.

إذن:

```text
reserve did NOT execute
```

هذا مهم جداً.

لو عاملناها Ambiguous سنفعل:

```text
FAILED + reconciliation
```

بدون حاجة.

الحل النهائي:

```text
401/403 internal auth failure
        |
        v
FlightReservationRejectedException(502)
        |
        v
BookingServiceImpl definitive path
        |
        v
deleteClaim()
```

مع المحافظة على:

```text
BookingServiceImpl unchanged
```

---

# 17. Service JWT Interceptor

كل outbound Feign request لـFlight Service يحصل على SERVICE JWT جديد.

```java
public class ServiceJwtRequestInterceptor implements RequestInterceptor {

    private final InternalTokenProvider internalTokenProvider;

    @Override
    public void apply(RequestTemplate template) {

        String token =
                internalTokenProvider.generateServiceToken();

        template.header(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + token
        );
    }
}
```

الـtoken الطبيعي:

```text
sub  = booking-service
type = SERVICE
exp  = short lived
```

Flight Service تتحقق من service identity قبل السماح بـ`/internal/**`.

---

# 18. لماذا الـInterceptor ليس Global Component؟

لم نضع:

```java
@Component
```

على `ServiceJwtRequestInterceptor`.

بل نسجله داخل config الخاصة بـFlight client.

السبب:

```text
Future Feign clients may require different authentication.
```

مثلاً لاحقاً:

```text
PaymentFeignClient
NotificationFeignClient
AnalyticsFeignClient
```

لا نريد تلقائياً إرسال Flight SERVICE JWT لكل client.

إذن:

```text
FlightFeignClient
    -> FlightFeignConfig
        -> Flight-only RequestInterceptor
```

---

# 19. FlightFeignConfig

الـconfig خاصة فقط بـ`FlightFeignClient`.

```java
public class FlightFeignConfig {

    @Bean
    public ErrorDecoder flightClientErrorDecoder(...) {
        ...
    }

    @Bean
    public RequestInterceptor serviceJwtRequestInterceptor(...) {
        ...
    }

    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Request.Options options() {
        ...
    }
}
```

## لماذا لا توجد `@Configuration`؟

عمداً.

نريدها scoped إلى:

```java
@FeignClient(configuration = FlightFeignConfig.class)
```

ولا نريد أن تتحول accidental global configuration لكل Feign clients في التطبيق.

---

# 20. Jackson 3 / Spring Boot 4 Issue الذي ظهر

أثناء startup ظهر:

```text
Parameter 0 of method flightClientErrorDecoder
required a bean of type
'com.fasterxml.jackson.databind.ObjectMapper'
that could not be found.
```

السبب:

المشروع يعمل على Spring Boot 4 / Jackson 3.

Jackson 3 يستخدم package:

```java
tools.jackson.databind.ObjectMapper
tools.jackson.databind.JsonNode
```

وليس:

```java
com.fasterxml.jackson.databind.ObjectMapper
com.fasterxml.jackson.databind.JsonNode
```

## الحل

استخدام Spring-managed Jackson 3 mapper:

```java
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
```

ولا ننشئ:

```java
new ObjectMapper()
```

لمجرد تجاوز المشكلة.

الهدف هو استخدام mapper المهيأ من Spring.

---

# 21. NO AUTOMATIC RETRY

هذا قرار معماري وليس مجرد default.

```java
@Bean
public Retryer retryer() {
    return Retryer.NEVER_RETRY;
}
```

السبب:

```text
reserveSeats
releaseSeats
```

ليستا idempotent بالكامل حالياً.

مثال الخطر:

```text
Booking -> reserve 2
Flight reserves 2
response is lost
Feign retries automatically
Flight reserves 2 again

Result:
4 seats affected instead of 2
```

لذلك:

```text
unknown outcome
    != retry
```

بل:

```text
unknown outcome
    -> reconciliation
```

---

# 22. Timeouts

لدينا timeout configuration صريحة.

المعنى:

```text
connectTimeout
    -> كم ننتظر لإنشاء connection إلى Flight Service؟

readTimeout
    -> بعد الاتصال، كم ننتظر response؟
```

مثال configuration:

```properties
spring.cloud.openfeign.client.config.flight-service.connectTimeout=5000
spring.cloud.openfeign.client.config.flight-service.readTimeout=10000
```

وفي حالة mutable operation مثل reserve:

```text
read timeout
    -> لا يعني أن العملية لم تُنفذ
```

لذلك يبقى Ambiguous.

---

# 23. RestClientConfig

`RestClientConfig` كان مسؤولاً سابقاً عن:

- بناء `RestClient`.
- base URL.
- SERVICE JWT interceptor.

بعد نجاح Feign regression tests لم يعد transport الحالي يحتاجه.

الحالة النهائية:

```text
RestClientConfig
    -> removed / no longer part of active Booking-to-Flight path
```

لكن تاريخ استخدام RestClient يبقى موثقاً في Sprint 4 reference.

---

# 24. Business Logic التي لم تتغير

Sprint 4.5 لم يغير:

- Booking state machine.
- Idempotency.
- Request hash.
- Unique `(user_id, idempotency_key)`.
- Passenger handling.
- Snapshot.
- `IN_PROGRESS` claim.
- `REQUIRES_NEW`.
- `PENDING`.
- TTL.
- Cancel flow.
- Expiration flow.
- CAS transitions.
- Compensation.
- Reconciliation.
- `FAILED`.
- No blind retry semantics.
- Scheduler.
- Ownership/security rules.

هذه نقطة جوهرية:

```text
Transport refactor != business redesign
```

---

# 25. Create Booking Flow بعد Feign

```text
Customer Request
      |
      v
BookingController
      |
      v
BookingServiceImpl
      |
      +--> requestHash
      |
      +--> create IN_PROGRESS claim + COMMIT
      |
      v
FlightClient
      |
      v
FeignFlightClient
      |
      v
FlightFeignClient
      |
      +--> SERVICE JWT interceptor
      |
      +--> HTTP POST reserve
      |
      v
Flight Service
```

ثم:

```text
Success
    -> authoritative SeatReservationResult
    -> snapshot
    -> PENDING

Definitive rejection
    -> delete claim

Ambiguous result
    -> FAILED
```

---

# 26. Cancel Flow بعد Feign

Business flow لم يتغير:

```text
PENDING
   |
   | atomic claim
   v
CANCELLING
   |
   | FlightClient.releaseSeats()
   v
FeignFlightClient
   |
   v
FlightFeignClient
   |
   v
flight-service
```

ثم:

```text
release success
    -> CANCELLING -> CANCELLED

release ambiguous
    -> leave CANCELLING
    -> reconciliation
```

ولا يوجد blind retry.

---

# 27. Regression Testing — Final Result

تمت إعادة اختبار الـcritical Sprint 4 behaviors بعد الـmigration.

| # | Scenario | Expected Result | Status |
|---|---|---|---|
| 1 | CREATE happy path | `201`, `PENDING`, seats decrease once | PASS ✅ |
| 2 | Same key + same body | `200`, same booking, no second reserve | PASS ✅ |
| 3 | Same key + different body | `409 Conflict` | PASS ✅ |
| 4 | Not enough seats | `409`, Flight message preserved, claim deleted | PASS ✅ |
| 5 | Departed flight | `409`, claim deleted | PASS ✅ |
| 6 | Invalid fare class | `404`, claim deleted | PASS ✅ |
| 7 | Flight/fareClass mismatch | `400`, claim deleted | PASS ✅ |
| 8 | Wrong SERVICE JWT | Customer gets `502`, claim deleted | PASS ✅ |
| 9 | Flight DOWN during create | `503`, booking becomes `FAILED` | PASS ✅ |
| 10 | Same FAILED key again | `503`, no second reserve | PASS ✅ |
| 11 | Cancel PENDING | `200`, `CANCELLED`, seats released once | PASS ✅ |
| 12 | Cancel CANCELLED again | `200`, no second release | PASS ✅ |
| 13 | Cancel EXPIRED | `409` | PASS ✅ |
| 14 | Flight DOWN during cancel | `503`, remains `CANCELLING` | PASS ✅ |
| 15 | Cancel CANCELLING again | `409`, no second release | PASS ✅ |
| 16 | Concurrent same idempotency key | one booking + one reserve execution | PASS ✅ |
| 17 | Decode/invalid response behavior | ambiguous/reconciliation semantics preserved | PASS ✅ |

---

# 28. Wrong SERVICE JWT Test

هذا test أُضيف خصيصاً للتحقق من internal-auth semantics.

تم تعديل service identity مؤقتاً بحيث Flight Service ترفض SERVICE JWT.

الـobserved response:

```text
HTTP 502 BAD_GATEWAY
message = Internal service authentication failure
```

ثم تم التحقق من قاعدة Booking:

```sql
SELECT *
FROM bookings
WHERE idempotency_key = '<wrong-service-jwt-test-key>';
```

النتيجة:

```text
0 rows
```

وهذا يثبت:

```text
internal 401/403
    -> definitive rejection
    -> claim deleted
    -> customer sees 502
```

وليس:

```text
FAILED
```

---

# 29. لماذا لم نعدل BookingServiceImpl؟

هذا أحد أنجح أجزاء الـrefactor.

قبل Feign:

```text
BookingServiceImpl
    -> FlightClient
```

بعد Feign:

```text
BookingServiceImpl
    -> FlightClient
```

لم يتغير contract.

الذي تغير فقط:

```text
implementation behind FlightClient
```

وهذا مثال عملي على:

```text
Dependency Inversion
Programming to an Interface
Adapter Pattern
Separation of Concerns
```

---

# 30. Mapping ذهني سريع

```text
FlightClient
    = ماذا يحتاج Booking من Flight؟

FlightFeignClient
    = كيف تبدو HTTP endpoints؟

FeignFlightClient
    = كيف أحول Feign behavior إلى Booking semantics؟

FlightClientErrorDecoder
    = كيف أفسر HTTP error responses؟

ServiceJwtRequestInterceptor
    = كيف أثبت هوية booking-service لكل outbound request؟

FlightFeignConfig
    = أين أجمع Feign-specific policies؟
```

---

# 31. ما الذي لم نضفه عمداً؟

لم نضف في Sprint 4.5:

```text
Eureka
Circuit Breaker
Resilience4j
automatic retry
fallback
Kafka
Redis
```

ليس لأنها تقنيات سيئة.

بل لأن القاعدة:

```text
Do not add infrastructure without a real problem to solve.
```

---

# 32. لماذا لم نضف Fallback؟

Fallback غير مناسب بسهولة في inventory mutation.

مثلاً fallback مثل:

```text
reserve failed
-> return fake/default response
```

قد يخفي distributed inconsistency خطيرة.

لذلك حالياً:

```text
failure must remain visible
```

والـambiguous outcome يدخل reconciliation.

---

# 33. Future Revisit Triggers

نرجع لهذا الـintegration layer عندما يكبر المشروع إذا ظهر سبب واضح.

أمثلة:

## Service Discovery

إذا أصبحت الـservice addresses dynamic:

```text
evaluate:
Eureka
or platform-native discovery
```

## Circuit Breaker

إذا ظهرت cascading failures أو Flight Service غير مستقرة:

```text
evaluate Resilience4j
```

لكن بدون blind retry للـnon-idempotent mutations.

## Downstream Idempotency

تحسين مستقبلي مهم:

```text
reserve/release themselves become idempotent
```

مثلاً باستخدام operation key / reservation command ID.

عندها يمكن بناء retry/recovery strategy أقوى.

## Observability

مع زيادة الخدمات:

- correlation ID.
- distributed tracing.
- metrics.
- Feign latency.
- downstream error rate.
- reconciliation counters.

## Kubernetes / Docker

يمكن تغيير:

```text
flight-service.url
```

إلى DNS/service name حسب البيئة بدون تغيير Business code.

---

# 34. Sprint 5 Impact

Sprint 5 سيضيف Payment.

الـexpected lifecycle:

```text
PENDING
   |
   | payment succeeds
   v
CONFIRMED
```

وسيظهر distributed workflow أكبر:

```text
Reserve Seats
     |
     v
Payment
     |
     v
Confirm Booking
```

وعندها نعيد تقييم:

- Saga.
- compensation.
- durable workflow state.
- payment idempotency.
- refund.
- outbox لاحقاً.

لكن Feign migration في Sprint 4.5 لا يغير هذا الأساس.

---

# 35. Interview-Level Explanation

إذا سُئلت:

> Why did you migrate from RestClient to Feign?

جواب مناسب:

> We initially implemented synchronous Booking-to-Flight communication with Spring RestClient so we could focus on booking lifecycle and distributed consistency first. In Sprint 4.5, we migrated the transport adapter to Spring Cloud OpenFeign to use a declarative interface-based client commonly found in enterprise Spring systems. The key requirement was behavioral equivalence: the Booking business layer did not change.

إذا سُئلت:

> What was the biggest risk in the Feign migration?

الجواب:

> Exception semantics. RestClient and Feign throw different exception types, while our booking workflow depends on distinguishing definitive downstream rejections from ambiguous outcomes. We introduced a dedicated ErrorDecoder and Feign adapter so 400/404/409 remain definitive, while 5xx, timeouts, connection failures, and decode failures remain ambiguous and trigger reconciliation.

إذا سُئلت:

> Why did you disable Feign retries?

الجواب:

> Seat reserve and release operations are non-idempotent. If the downstream service commits the mutation but the response is lost, an automatic retry could apply the side effect twice. We therefore explicitly use no automatic retries and preserve unknown outcomes for reconciliation.

إذا سُئلت:

> Why no Eureka?

الجواب:

> Feign handles how a service communicates over HTTP, while Eureka handles service discovery. They are independent. Our current deployment has stable service addresses, so Eureka would add infrastructure without solving a real problem. We can introduce discovery later if deployment topology requires it.

---

# 36. Sprint 4.5 Final Architecture

```text
                           booking-service
                                  |
                                  v
                        BookingServiceImpl
                                  |
                                  | business abstraction
                                  v
                            FlightClient
                                  |
                                  v
                         FeignFlightClient
                                  |
                    +-------------+-------------+
                    |             |             |
                    v             v             v
            transport errors   validation   semantics
                    |
                    v
                         FlightFeignClient
                                  |
               +------------------+------------------+
               |                  |                  |
               v                  v                  v
       ErrorDecoder        JWT Interceptor      NO RETRY
               |                  |                  |
               +------------------+------------------+
                                  |
                                  v
                           HTTP /internal/**
                                  |
                                  v
                           flight-service
```

---

# 37. Final Decisions

```text
Spring Cloud OpenFeign         ✅ ADDED

RestClient active integration  ❌ REPLACED

FlightClient abstraction       ✅ PRESERVED

BookingServiceImpl changes     ❌ NONE required for migration

SERVICE JWT                    ✅ PRESERVED

Feign ErrorDecoder             ✅ ADDED

Feign RequestInterceptor       ✅ ADDED

Automatic retry                ❌ DISABLED

Timeouts                       ✅ EXPLICIT

Eureka                         ❌ NOT ADDED

Circuit Breaker                ❌ NOT ADDED

Regression tests               ✅ PASSED
```

---

# 38. Sprint Status

```text
Sprint 4.5 — CLOSED ✅

Goal:
Replace RestClient transport with OpenFeign
while preserving Sprint 4 distributed semantics.

Result:
SUCCESS.
```

---

**Booking Service — Sprint 4.5 OpenFeign Refactor Reference**
