القسم الأول — أسئلة Architecture عامة
Q1 ⭐⭐⭐ احكيلي عن Architecture المشروع

جواب المقابلة:

I built the system using a microservices architecture. Currently I have an Auth Service for authentication and authorization, a Flight Service that owns flight and seat inventory, an API Gateway for external routing and JWT validation, and a Booking Service that owns the booking lifecycle.
Each service owns its own database and services communicate through APIs rather than sharing database tables.

الفكرة:

Client
   ↓
API Gateway
   ↓
┌─────────────┐
│ Auth        │
│ Flight      │
│ Booking     │
└─────────────┘

والقاعدة:

Booking owns bookings
Flight owns inventory
Auth owns users

Booking لا يعمل FK على Flight DB أو Auth DB. في المرجع هذا موثق صراحة: Booking يملك lifecycle والـpassenger/snapshot، بينما Flight هو Source of Truth للـinventory.

Q2 ⭐⭐⭐ ليش Database per Service؟

To keep services independently deployable and loosely coupled. Each service owns its data and other services access it through APIs rather than direct database access.

يعني ما بنعمل:

booking_db.booking.flight_id
       FK
flight_db.flight.id ❌

نعمل:

Booking:
flightId = UUID

Reference فقط.

ليش؟

لو Booking صار يدخل مباشرة على Flight DB:

Booking
   ↓
Flight DB

صار عندنا coupling.

Q3 ⭐⭐⭐ ليش Microservices أصلاً؟ وليش مش Monolith؟

جواب ممتاز:

Microservices are useful when domains have clear ownership boundaries, independent scaling or deployment requirements, and different failure characteristics. But they add network failures, distributed consistency problems and operational complexity, so I wouldn't choose them just because the system is large.

هذا جواب قوي لأنه ما بتحكي:

Microservices always better.

بل بتبين إنك فاهم trade-off.

Q4 ⭐⭐⭐ شو الفرق بين synchronous و asynchronous communication؟

عندنا حاليًا:

Booking
   ↓ synchronous HTTP
Flight

ليش synchronous؟

لأن المستخدم ضغط:

Book

وبده يعرف فورًا:

Did I get the seats?

فـBooking تحتاج نتيجة reserve الآن.

أما شيء مثل:

BookingConfirmed
        ↓
Notification

ما يحتاج المستخدم ينتظر email، فهون Kafka لاحقًا أفضل.

مختصر مقابلة:

I use synchronous communication when the caller needs an immediate business result, and asynchronous messaging when eventual processing is acceptable and I want better decoupling.

Q5 ⭐⭐⭐ ليش ما استخدمت Kafka بين Booking وFlight للـreserve؟

هذا سؤال ممتاز.

الجواب:

Seat reservation is currently part of the request-response flow. Booking needs an immediate authoritative answer from Flight Service before returning to the customer, so synchronous HTTP is simpler and more appropriate. Kafka will be more useful later for events such as BookingConfirmed or PaymentCompleted.

القسم الثاني — Sprint 4: Booking Service

هذا أهم قسم كامل بالمقابلة.

Q6 ⭐⭐⭐ مين Source of Truth للمقاعد؟

Flight Service.

Flight Service
    ↓
FareClass.availableSeats

Booking لا يحسب availability من عنده.

Booking فقط يخزن snapshot لما reserve ينجح.

هذا من أهم bounded-context decisions في التصميم.

Q7 ⭐⭐⭐ اشرح Create Booking flow

جواب مقابلة مرتب:

First I compute a deterministic request hash for idempotency. Then I create and commit an IN_PROGRESS booking claim. After that I call Flight Service to reserve inventory. If reservation succeeds, I store the authoritative flight snapshot and move the booking to PENDING. If Flight definitively rejects the request, I delete the claim. If the outcome is unknown, such as a timeout, I keep the record and move it to FAILED for reconciliation.

الرسم:

Request
  ↓
requestHash
  ↓
IN_PROGRESS claim
  ↓
COMMIT
  ↓
Flight.reserve()
  ↓
 ┌───────────────┬─────────────────┐
 │ Success       │ Failure         │
 ↓               ↓
PENDING      definitive / ambiguous

والـimplementation عندك يعمل claim أولًا ثم reserve، ويعامل definitive rejection مختلف عن ambiguous failure.

Q8 ⭐⭐⭐ شو هو الـClaim؟

مش Entity جديدة.

هو:

Booking row
status = IN_PROGRESS

يعني:

This request has officially started and owns this idempotency key.

مثال:

id = B1
userId = U1
idempotencyKey = ABC
requestHash = XYZ
status = IN_PROGRESS
Q9 ⭐⭐⭐ ليش تعمل Claim قبل ما تعمل Reserve؟

هذه من أقوى أسئلة Sprint 4.

تخيل العكس:

reserve seats
     ↓
application crashes
     ↓
booking row was never created

صار عند Flight:

2 seats reserved

لكن Booking ما عندها record يدل شو صار.

مع Claim First:

create IN_PROGRESS
COMMIT
       ↓
reserve

حتى لو crash:

DB:
Booking B1 = IN_PROGRESS

عندك أثر persistent تستطيع تعمل عليه reconciliation.

Q10 ⭐⭐⭐ شو هي Idempotency؟

Idempotency means that retrying the same logical request should not create the business side effect multiple times.

مثال:

User clicks Book twice

بدونها:

Booking #1 → reserve 2
Booking #2 → reserve 2

4 seats ❌

مع:

Idempotency-Key: ABC

نفس logical request:

→ one booking
→ one reserve
Q11 ⭐⭐⭐ ليش Idempotency-Key مش كافي؟ ليش requestHash؟

لأن client ممكن بالغلط:

Key = ABC
Request 1 = Flight F1

Key = ABC
Request 2 = Flight F2

لو اعتمدنا فقط على Key:

Request 2
→ نرجع Booking F1 ❌

لذلك:

same key + same hash
→ same request

same key + different hash
→ 409 Conflict
Q12 ⭐⭐⭐ ليش عندك DB UNIQUE constraint إذا أنت عامل SELECT أول؟

عندنا:

UNIQUE(user_id, idempotency_key)

لأن الـSELECT ما يحل concurrency.

ممكن:

Thread A → SELECT → not found
Thread B → SELECT → not found

A → INSERT
B → INSERT

الـDB constraint هو خط الدفاع الأخير:

A INSERT ✅
B INSERT ❌ unique violation

ثم B تقرأ winner.

وهذا هو التصميم الفعلي عندك.

Q13 ⭐⭐⭐ ليش requestHash deterministic؟

لازم نفس logical request يعطي دائمًا:

same hash

لذلك تعمل normalization مثل:

trim names
uppercase passport
uppercase nationality
ISO date
stable passenger ordering

ثم:

SHA-256
Q14 ⭐⭐ هل SHA-256 هنا Encryption؟

لا.

Hashing ≠ Encryption

نستخدم SHA-256 هنا كـ:

fingerprint

مش لاسترجاع البيانات.

Q15 ⭐⭐⭐ ليش passengerCount مخزن في Booking؟

مع إنه عندنا passengers؟

لأن Cancel/Scheduler يحتاجوا:

releaseSeats(count)

بدل كل مرة نحمل:

booking.getPassengers().size()

ونواجه lazy loading أو queries إضافية.

هو:

Intentional denormalization.

ويتم حسابه server-side وليس من الـclient.

Q16 ⭐⭐⭐ ليش Booking تخزن Flight Snapshot؟

مثلاً:

flightNumber
origin
destination
departure
priceAtBooking
currency
fareClass

لأن:

Current Flight data
≠
Historical Booking data

لو السعر بعد أسبوع تغير من:

$120 → $180

Booking القديمة لازم تظل:

priceAtBooking = $120
القسم الثالث — Transactions
Q17 ⭐⭐⭐ ليش ما عملت @Transactional على createBooking كلها؟

سؤال مقابلة ممتاز جدًا.

لو:

@Transactional
public void createBooking() {

    saveClaim();

    flightClient.reserveSeats(); // network

    finalizeBooking();
}

الـDB transaction تبقى مفتوحة أثناء network call.

المشاكل:

DB connection occupied
locks longer
pool exhaustion
slow transaction
remote latency inside DB transaction

لذلك عملنا:

TX1:
create claim
COMMIT

HTTP:
reserve

TX2:
finalize
COMMIT

هذا بالضبط سبب BookingTransactionService.

Q18 ⭐⭐⭐ شو فائدة REQUIRES_NEW؟

It creates an independent transaction boundary for each local database step.

مثلاً:

createClaim()
   ↓
TX starts
INSERT
COMMIT
   ↓
return

وبعدين HTTP call.

Q19 ⭐⭐⭐ saveAndFlush() يعني Commit؟

لا.

flush
→ SQL sent to DB

commit
→ transaction completed permanently

ممكن تعمل:

saveAndFlush()

وبعدها exception داخل نفس transaction:

ROLLBACK

لهيك كان مهم نخرج من REQUIRES_NEW method قبل network call.

Q20 ⭐⭐⭐ هل عندك Distributed Transaction؟

الجواب الدقيق:

Not an ACID distributed transaction. I use local transactions with orchestration, explicit workflow states, compensation and reconciliation.

يعني:

Booking DB Transaction
+
Flight DB Transaction

ما في:

one ACID transaction covering both

ولا 2PC.

Q21 ⭐⭐⭐ ليش ما استخدمت 2PC؟

2PC increases coupling and operational complexity and reduces service autonomy. Instead, the design uses local transactions, state machines and compensating actions.

القسم الرابع — أخطر جزء: Distributed Failures
Q22 ⭐⭐⭐ شو الفرق بين Definitive Failure وAmbiguous Failure؟

لازم تحفظ هاي.

Definitive

Flight ردت:

400
404
409

مثلاً:

Not enough seats

إحنا نعرف:

reserve DID NOT happen

إذن:

delete claim ✅
Ambiguous
timeout
connection reset
5xx
malformed response

ممكن Flight نفذت reserve بس الرد ضاع.

إذن:

reserve MAY have happened

نعمل:

IN_PROGRESS → FAILED

ولا نحذف.

المرجع نفسه يجعل هذه distinction قاعدة أساسية.

Q23 ⭐⭐⭐ إذا Flight Service عملت reserve وبعدين timeout، شو تعمل؟

لا تعمل retry.

Booking → reserve 2
Flight → reserves 2
response lost
Booking → timeout

لو retry:

Flight → reserve another 2 ❌

لذلك:

FAILED
+ manual reconciliation
Q24 ⭐⭐⭐ ليش ما تعمل Automatic Retry؟

جواب مقابلة ممتاز:

Because reserve and release are currently non-idempotent. A timeout doesn't prove that the operation failed. If I retry automatically after the remote service already committed the side effect, I could reserve or release twice.

ولهذا Feign عندنا صراحة:

Retryer.NEVER_RETRY

Q25 ⭐⭐⭐ شو هي Compensation؟

هي مش rollback موزع.

هي:

Business action that reverses another business action.

مثلاً:

reserveSeats()
      ↓
Booking finalization failed
      ↓
releaseSeats()

هذا:

Compensation

مش:

Database rollback
Q26 ⭐⭐⭐ إذا finalizeBooking رمت exception بعد reserve شو تعمل؟

هذا من أصعب أسئلة المشروع.

ما تعمل مباشرة:

releaseSeats ❌

ليش؟

ممكن DB عملت:

COMMIT PENDING ✅

لكن التطبيق استقبل exception بسبب مشكلة بالconnection/commit acknowledgement.

لذلك:

finalize error
    ↓
check DB
    ↓
┌─────────────────┬─────────────────┐
PENDING          IN_PROGRESS
↓                  ↓
success          compensate

وإذا DB نفسها لا يمكن التحقق منها:

don't guess
→ reconciliation
القسم الخامس — Concurrency
Q27 ⭐⭐⭐ كيف منعت Cancel والScheduler يعملوا release مرتين؟

عندنا:

PENDING
  |
  +→ CANCELLING
  |
  +→ EXPIRING

Cancel يحاول:

UPDATE booking
SET status = 'CANCELLING'
WHERE id = ?
AND status = 'PENDING';

Scheduler بنفس الوقت:

UPDATE booking
SET status = 'EXPIRING'
WHERE id = ?
AND status = 'PENDING';

واحد فقط يرجع:

updatedRows = 1

الثاني:

0

وبالتالي فقط winner يعمل release. هذه هي وظيفة الـCAS transition عندك.

Q28 ⭐⭐⭐ ليش CANCELLING وEXPIRING؟ ليش مش مباشرة CANCELLED / EXPIRED؟

لأن لازم نحجز ownership للعملية قبل remote side effect.

PENDING
↓
CANCELLING
COMMIT
↓
release
↓
CANCELLED

إذا بقيت PENDING أثناء release، scheduler ممكن يشوفها أيضًا ويعمل release.

Q29 ⭐⭐⭐ شو هو Compare-And-Swap CAS؟

بشكل مبسط:

Change state ONLY if current state is what I expect.

مثلاً:

UPDATE booking
SET status = 'CANCELLING'
WHERE id = ?
AND status = 'PENDING';

إذا row count:

1 → I won
0 → someone changed it before me
Q30 ⭐⭐ شو الفرق بين @Version وCAS؟

@Version:

optimistic locking between entity updates

أما CAS عندنا:

atomic business state transition

مثل:

PENDING → CANCELLING

وبالتالي الاثنين مرتبطين بالـconcurrency لكن مش نفس الشيء.

Q31 ⭐⭐⭐ كيف Flight تمنع اثنين يحجزوا آخر مقاعد بنفس الوقت؟

FareClass عندها inventory:

availableSeats

ومع concurrency protection مثل optimistic locking عبر:

@Version

إذا requestين قرأوا نفس version وحاولوا update، واحد ينجح والثاني يحصل conflict/retry policy مناسبة بدل lost update.

Q32 ⭐⭐⭐ ليش ما تعمل getAvailability() ثم reserve()؟

TOCTOU problem:

check:
2 seats available ✅

another request reserves them

reserve:
❌

لذلك:

reserve itself must validate + modify inventory

ولا نعتمد على pre-check كضمان.

القسم السادس — Scheduler
Q33 ⭐⭐ ليش Scheduler موجود؟

Booking PENDING ما لازم تمسك seats للأبد.

لذلك:

PENDING
expiresAt < now
       ↓
EXPIRING
       ↓
release
       ↓
EXPIRED
Q34 ⭐⭐ ليش fixedDelay بدل Cron؟

لأننا نريد:

check periodically

مش:

every day at exactly 02:00

fixedDelay مناسب للpolling بعد انتهاء run السابق. هذا موثق في تصميم scheduler.

Q35 ⭐⭐ ليش Batch محدود؟

لو عندك:

500,000 expired bookings

ما بدك scheduler يرسل 500k requests دفعة وحدة.

لذلك:

LIMIT 100
oldest first

يعطي:

bounded memory
bounded load
less pressure on Flight
القسم السابع — Security
Q36 ⭐⭐⭐ JWT شو فيه عندكم؟

تقريبًا:

sub  → user UUID
role → CUSTOMER / ADMIN
email
exp

والـJWT موقعة بحيث نعرف أنها لم يتم تعديلها.

Q37 ⭐⭐⭐ الفرق بين Authentication وAuthorization؟
Authentication
→ Who are you?

Authorization
→ What are you allowed to do?

مثال:

JWT valid
→ authenticated

role=CUSTOMER
GET /admin
→ authenticated but not authorized
→ 403
Q38 ⭐⭐⭐ الفرق بين 401 و403؟
401 Unauthorized
→ not authenticated
→ missing/invalid/expired credentials

403 Forbidden
→ authenticated
→ but insufficient permission
Q39 ⭐⭐⭐ ليش Service JWT غير Customer JWT؟

Booking لما تتصل بـFlight:

Booking → Flight internal endpoint

ما بنمرر Customer JWT.

Flight لازم تعرف:

The caller is booking-service

لذلك Service JWT:

sub  = booking-service
type = SERVICE

والـFlight تتحقق من الاثنين. هذا بالضبط contract الداخلي الموثق عندنا.

Q40 ⭐⭐⭐ ليش /internal/** مش عبر Gateway؟

لأنها:

service-to-service APIs

وليست public client APIs.

Client
   X
/internal/**

لكن برضه ما نعتمد فقط على Gateway؛ Flight نفسها تتحقق من Service JWT.

Q41 ⭐⭐⭐ شو فايدة API Gateway؟

جواب:

It provides a single entry point for external clients and centralizes cross-cutting concerns such as routing and initial JWT validation.

عندنا Gateway كمان يعمل anti-spoofing:

remove client-supplied X-User-Id
remove client-supplied X-User-Role
validate JWT
then add trusted headers

الكود الحالي يعمل هذا التسلسل فعليًا.

Q42 ⭐⭐⭐ ليش تمسح X-User-Id القادم من Client؟

لأن المستخدم ممكن يبعت:

X-User-Id: ADMIN-UUID

إذا وثقنا فيه:

identity spoofing ❌

Gateway يعمل:

remove incoming header
validate JWT
derive identity
add trusted header
Q43 ⭐⭐ إذا Gateway تحقق JWT ليش Service تتحقق كمان؟

Defense in depth.

لأنه ممكن:

Gateway bypass
internal network
configuration mistake
direct port access

الـservice نفسها لازم تحمي الـbusiness operation الحساسة.

القسم الثامن — Sprint 4.5 / OpenFeign
Q44 ⭐⭐⭐ ليش غيرت RestClient إلى OpenFeign؟

جواب قوي:

RestClient worked correctly, but I wanted a declarative interface-based client and to learn the communication pattern commonly used in enterprise Spring microservices. The important part was that I changed only the transport adapter while preserving the Booking business contract.

Q45 ⭐⭐⭐ كيف غيرت RestClient بدون ما تغيّر BookingServiceImpl؟

عن طريق abstraction:

BookingServiceImpl
        ↓
FlightClient interface
        ↓
FeignFlightClient
        ↓
FlightFeignClient

فـBooking تعرف:

flightClient.reserveSeats(...)

لكن لا تعرف هل التنفيذ:

RestClient
Feign
HttpExchange

وهذا تطبيق واضح لـ:

Dependency Inversion
Adapter Pattern
Programming to Interface
Q46 ⭐⭐⭐ شو الفرق بين FlightClient وFlightFeignClient؟
FlightClient
→ application-facing abstraction
→ what Booking needs

FlightFeignClient
→ HTTP declarative contract
→ how Flight API is called

مهم جدًا ما تخلط بينهم.

Q47 ⭐⭐⭐ شو هو ErrorDecoder في Feign؟

Feign لا ترمي نفس exceptions الخاصة بـRestClient.

لذلك ErrorDecoder يأخذ:

HTTP response

ويترجمه إلى exceptions تناسب semantics تبعتنا.

مثلاً:

409
→ FlightReservationRejectedException

500
→ ServiceUnavailableException
Q48 ⭐⭐⭐ كيف تعاملت مع Timeout في Feign؟

Timeout عادة ما يكون response HTTP حتى يدخله ErrorDecoder.

ممكن يظهر كـ:

RetryableException

لذلك الـadapter يمسك transport exceptions ويحوّلها إلى:

ServiceUnavailableException

ثم Booking تعاملها كـ:

ambiguous outcome
Q49 ⭐⭐⭐ ليش Flight 401/403 تتحول عندك إلى 502؟

هاي ممتازة جدًا بالمقابلة.

إذا Flight رفضت:

Booking SERVICE JWT

فالمشكلة ليست Customer JWT.

كمان Security رفضت request قبل business logic، لذلك:

reserve definitely did not happen

نعمل:

delete claim

لكن للـCustomer ما بنرجع:

401

حتى ما يفكر الـcustomer token تبعه غلط.

نرجع:

502 BAD_GATEWAY

يعني internal downstream authentication problem.

Q50 ⭐⭐⭐ Feign وEureka شو علاقتهم؟

احفظ:

Feign
→ HOW do I call another service?

Eureka
→ WHERE is the service?

LoadBalancer
→ WHICH instance should receive the request?

ممكن:

Feign ✅
Eureka ❌

مثل مشروعنا الآن باستخدام:

flight-service.url=http://localhost:7172
Q51 ⭐⭐⭐ ليش ما أضفت Eureka؟

Because I don't currently have a service discovery problem. The services have stable addresses, and adding Eureka would introduce infrastructure without solving a real need. If deployment later requires dynamic discovery, I can use Eureka or platform-native discovery.

هذا من أجمل قرارات المشروع لأنه يبين:

technology follows problem

مش:

أضيف كل شيء لأنه Microservices
القسم التاسع — Design Patterns / Principles اللي فعليًا استخدمناها

لو سألك:

Q52 ⭐⭐ أي Design Patterns استخدمت؟

ما تجاوب Factory/Singleton والسلام.

عندنا فعليًا:

Adapter Pattern
→ FeignFlightClient

Orchestrator
→ BookingServiceImpl

Repository Pattern
→ BookingRepository

Dependency Injection
→ Spring constructor injection

Dependency Inversion
→ BookingServiceImpl depends on FlightClient

Compensation Pattern
→ reserve ↔ release

Idempotency Pattern
→ idempotency key + request hash

State Machine
→ BookingStatus transitions

Optimistic Concurrency
→ @Version

Compare-And-Swap
→ conditional status UPDATE

هذا جواب أقوى بكثير.

Q53 ⭐⭐⭐ هل اللي عندك Saga؟

الجواب:

Not yet a full durable Saga. Currently BookingServiceImpl performs synchronous orchestration with local transactions and best-effort compensation.

وهذا مهم جدًا؛ لا تدعي:

I implemented Saga

وإحنا لسا ما عملنا durable saga state.

مع Payment رح نصير أقرب:

Reserve
   ↓
Pay
   ↓
Confirm

وCompensations:

Reserve → Release
Charge  → Refund
Q54 ⭐⭐⭐ وين يدخل Outbox؟

لاحقًا لما يصير:

Booking DB commit
+
Kafka event publish

المشكلة:

DB commit ✅
Kafka publish ❌

Outbox يحلها بأننا نحفظ:

Booking
+
OutboxEvent

بنفس local transaction.

ثم publisher يرسل event لاحقًا.

Q55 ⭐⭐⭐ شو الفرق بين Idempotency وConcurrency Control؟

مهم جدًا.

Idempotency
→ duplicate request problem

Concurrency control
→ simultaneous competing update problem

مثال:

Same user presses Book twice
→ Idempotency

Two users competing for last seat
→ Concurrency

الحلول مختلفة:

Idempotency-Key + UNIQUE

vs

@Version / atomic update / locking
Q56 ⭐⭐⭐ شو أكثر مشكلة Distributed Systems واجهتك بالمشروع؟

جواب ممتاز:

The hardest problem was not sending the HTTP request; it was dealing with uncertainty. If a network timeout happens after Flight Service may already have changed inventory, I cannot safely assume success or failure. So I explicitly model unknown outcomes using persistent states and reconciliation instead of blindly retrying or deleting data.

هذا الجواب وحده بالمقابلة يعطي انطباع إنك فاهم Microservices فعليًا، مش بس عامل Controllers.

أهم 10 تحفظهم أول شيء

إذا عندك مقابلة بكرة وما عندك وقت، ركز بهذا الترتيب:

1. Explain your system architecture.
2. Explain Booking create flow.
3. Why idempotency + requestHash + DB UNIQUE?
4. Why create IN_PROGRESS claim before reserve?
5. Why no DB transaction around HTTP call?
6. Definitive failure vs ambiguous failure.
7. Why no automatic retry?
8. How do you prevent Cancel vs Scheduler double release?
9. How does Booking communicate securely with Flight?
10. Feign vs Eureka and why you didn't use Eureka.

إذا أتقنت هذول، تقريبًا كل الأسئلة الثانية بتتفرع منهم.