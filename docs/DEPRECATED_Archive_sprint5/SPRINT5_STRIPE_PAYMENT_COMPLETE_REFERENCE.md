# Sprint 5 — Stripe Payment Complete Reference

> **Project:** Airline Booking System  
> **Scope:** Stripe Payment Integration  
> **Purpose:** مرجع فهم معماري وتقني — عربي + English technical terminology  
> **Relationship:** هذا الملف يشرح **WHY + HOW**. ملف `SPRINT5_STRIPE_PAYMENT_IMPLEMENTATION_PLAN.md` هو الملف التنفيذي الذي يحتوي الـphases والـcheckboxes.

---

# 1. الصورة الكبيرة — What Are We Building?

```text
Customer
   |
   v
API Gateway
   |
   v
Payment Service
   | \
   |  +------> Stripe
   |
   +---------> Booking Service
                   |
                   v
               Flight Service
```

الـownership:

```text
Booking Service
    owns booking lifecycle + payable snapshot

Payment Service
    owns payment lifecycle + Stripe coordination

Stripe
    owns card processing + provider financial state

Flight Service
    owns inventory
```

القاعدة الأساسية:

> **Money movement must never be guessed from the browser or from a network error.**

---

# 2. لماذا Payment Service مستقلة؟

لو وضعنا Stripe داخل Booking Service يصبح `BookingServiceImpl` مسؤولًا عن booking + inventory + payments + webhooks + refunds + reconciliation.

الأصح:

```text
Booking -> What should be paid?
Payment -> What happened financially?
Stripe  -> Did card processing actually succeed?
```

هذا يحقق:
- Separation of Concerns
- Independent persistence
- Independent failure recovery
- Cleaner testing
- Easier future provider addition

---

# 3. Booking Truth vs Payment Truth

Booking Service تعرف:

```text
bookingId
userId
status
priceAtBooking
passengerCount
totalAmount
currency
expiresAt
```

Payment Service تعرف:

```text
paymentId
attempts
Stripe Session references
payment status
booking synchronization status
refund/reconciliation state
```

Stripe تعرف:

```text
card
3DS
Checkout Session
PaymentIntent
Charge
Refund
```

---

# 4. لماذا Client لا يرسل amount؟

Request الصحيح:

```json
{
  "bookingId": "B1"
}
```

Payment Service تجلب authoritative amount من Booking Service.

ممنوع:

```json
{
  "bookingId": "B1",
  "amount": 1
}
```

لأن browser/client غير trusted source للmoney values.

---

# 5. `Booking.totalAmount`

في الـMVP:

```java
totalAmount = priceAtBooking
        .multiply(BigDecimal.valueOf(passengerCount));
```

مكان الحساب:

```text
IN_PROGRESS claim
   ↓
Flight.reserveSeats()
   ↓
authoritative snapshot
   ↓
priceAtBooking
currency
   ↓
totalAmount = priceAtBooking × passengerCount
   ↓
finalize PENDING
```

Payment لا تعيد حساب السعر من Flight.

---

# 6. لماذا `BigDecimal`؟

Money تحتاج decimal precision واضح، لذلك:

```java
BigDecimal
```

وليس:

```java
double
```

Stripe minor-unit conversion يبقى داخل Stripe adapter/helper.

---

# 7. Stripe-hosted Checkout

نستخدم:

```text
Stripe Checkout Sessions
+
Stripe-hosted Checkout
```

Flow:

```text
Payment Service -> Stripe create Session
Stripe -> checkoutUrl
Browser -> Stripe hosted page
Customer -> card / CVC / 3DS at Stripe
```

backend لا يستقبل full card data.

---

# 8. Checkout Session vs PaymentIntent vs Charge

```text
Checkout Session
    = checkout experience/session

PaymentIntent
    = payment lifecycle

Charge
    = actual charge record
```

في أول version ندير Checkout Session ونستفيد من Stripe-managed flow بدل raw PaymentIntent orchestration.

---

# 9. Payment Entity

`Payment` تمثل logical financial obligation:

```text
Booking B1
   ↓
Payment P1
```

قاعدة Sprint 5:

```text
One logical Payment per Booking
```

لذلك:

```text
UNIQUE(payment.booking_id)
```

---

# 10. PaymentAttempt

تمثل Stripe checkout attempt واحدة:

```text
Payment P1
   +-- Attempt A1 -> EXPIRED
   +-- Attempt A2 -> OPEN
   +-- Attempt A3 -> SUCCEEDED
```

---

# 11. PaymentAttempt Statuses

```text
INITIALIZING
OPEN
SUCCEEDED
EXPIRED
UNKNOWN
```

`INITIALIZING`: local durable attempt موجود، Stripe result لم يُحسم محليًا.  
`OPEN`: Checkout Session موجودة وقابلة للاستخدام.  
`SUCCEEDED`: verified Stripe success.  
`EXPIRED`: Session انتهت بدون successful payment.  
`UNKNOWN`: provider truth غير معروفة ونحتاج reconciliation.

---

# 12. Card Decline ليست FAILED Attempt

في hosted Checkout، card decline لا تنهي Session بالضرورة. المستخدم يمكن يجرب card ثانية داخل نفس Checkout.

لذلك Attempt تبقى:

```text
OPEN
```

حتى success أو expiry.

---

# 13. One Active Attempt Invariant

> **A Payment may have at most one unresolved active attempt at a time.**

Blocking:

```text
INITIALIZING
OPEN
UNKNOWN
```

`EXPIRED` تسمح بمحاولة جديدة.  
`SUCCEEDED` تمنع أي دفع جديد.

---

# 14. New Idempotency-Key مع OPEN Attempt

مثال:

```text
K1 -> Booking B1 -> Payment P1 -> Attempt A1 OPEN
```

ثم:

```text
K2 -> Booking B1
```

لا ننشئ A2.

نعيد:

```text
same A1
same checkoutUrl
```

لكن نسجل أن K2 استُخدمت أيضًا.

---

# 15. PaymentIdempotencyRecord

```text
PaymentIdempotencyRecord
------------------------
id
userId
idempotencyKey
requestHash
paymentId
attemptId
createdAt
```

Constraint:

```text
UNIQUE(user_id, idempotency_key)
```

فصل مهم:

```text
PaymentIdempotencyRecord
    = external API command idempotency

PaymentAttempt
    = Stripe attempt lifecycle
```

---

# 16. أربع طبقات Idempotency

```text
1. Client -> Payment
   Idempotency-Key + requestHash + DB unique

2. Payment -> Stripe
   stable Stripe idempotency key

3. Stripe -> Payment webhook
   UNIQUE(stripeEventId)

4. Payment -> Booking confirmation
   idempotent bookingId + paymentId command
```

كل واحدة تحل duplicate problem مختلفة.

---

# 17. Mandatory Idempotency-Key

```http
POST /api/payments
Idempotency-Key: <REQUIRED>
```

Validation:

```text
missing      -> 400
blank        -> 400
>100 chars   -> 400
```

---

# 18. Race: Two Different Keys, Same Booking

```text
A: K1 -> B1
B: K2 -> B1
```

Same-key idempotency لا تكفي.

نستخدم:

```text
UNIQUE Payment.bookingId
+
short PESSIMISTIC_WRITE lock on Payment
```

داخل TX:

```text
SELECT Payment FOR UPDATE
inspect active attempt

OPEN         -> reuse
INITIALIZING -> block duplicate
UNKNOWN      -> reconcile first
none         -> create INITIALIZING

COMMIT
```

ثم Stripe call بعد الـcommit.

---

# 19. لماذا Pessimistic Lock هنا منطقي؟

الـlock:
- على Payment row واحدة
- قصير
- لا يبقى أثناء Stripe HTTP call

وظيفته فقط serialize decision:

```text
Who may create the next attempt?
```

---

# 20. لماذا لا يوجد `PAYMENT_PENDING` في Booking؟

هذا intentional.

```text
PENDING
  +-> CANCELLING
  +-> EXPIRING
  +-> CONFIRMED
```

`CANCELLING/EXPIRING` تحتاج intermediate state لأن بعدها remote `releaseSeats()` side effect.

أما confirmation:

```text
Stripe already succeeded
Payment -> Booking
PENDING -> CONFIRMED
```

هي local CAS فقط داخل Booking.

لذلك `PAYMENT_PENDING` غير ضرورية للسياسة الحالية.

---

# 21. Trade-off حذف PAYMENT_PENDING

Booking TTL تستمر أثناء Stripe Checkout و3DS.

إذا Expiry فازت أولًا ثم Stripe نجحت لاحقًا:

```text
late payment
-> Booking cannot confirm
-> technical refund
```

هذا accepted business/UX trade-off، وليس bug.

---

# 22. Scheduler vs Cancel vs Payment

كلها تتنافس من `PENDING`:

```text
PENDING -> CANCELLING
PENDING -> EXPIRING
PENDING -> CONFIRMED
```

باستخدام CAS:

```sql
UPDATE bookings
SET status = :newStatus
WHERE id = :id
  AND status = 'PENDING';
```

واحد فقط يفوز.

---

# 23. Late Payment

الحالة:

```text
Booking = EXPIRED
Seats = RELEASED
Stripe = SUCCEEDED
```

ممنوع:

```text
EXPIRED -> CONFIRMED
```

لأن inventory قد لم تعد متاحة.

الحل:

```text
technical refund compensation
```

---

# 24. Compensation vs Rollback

Refund ليست DB rollback.

هي business compensation:

```text
Payment
   ↓
Refund
```

مثل Sprint 4:

```text
reserve
   ↓
release
```

---

# 25. PaymentStatus vs BookingConfirmationStatus

مثال:

```text
Stripe succeeded
Booking Service down
```

الحقيقة:

```text
PaymentStatus = SUCCEEDED
BookingConfirmationStatus = PENDING
```

Money truth تبقى صحيحة حتى لو cross-service sync لم تنتهِ.

---

# 26. لماذا لا Refund فورًا إذا Booking down؟

`Booking Service DOWN` ليست definitive business rejection.

يمكن الخدمة ترجع بعد ثواني ويحدث confirm.

إذن:

```text
Payment SUCCEEDED
BookingConfirmation PENDING
```

ثم Reconciliation.

Refund فقط عندما Booking definitively لا تستطيع confirmation.

---

# 27. PaymentGateway

```text
PaymentServiceImpl
   ↓
PaymentGateway
   ↓
StripePaymentGateway
   ↓
Stripe Java SDK
```

الفائدة:
- Adapter Pattern
- Dependency Inversion
- SDK isolation
- easy mocking/testing
- future provider flexibility

ولا نحتاج ProviderRegistry الآن لأن Sprint 5 Stripe فقط.

---

# 28. BookingClient

```text
PaymentServiceImpl
   ↓
BookingClient
   ↓
FeignBookingClient
   ↓
BookingFeignClient
   ↓
Booking Service
```

نفس pattern Sprint 4.5 لكن الاتجاه Payment -> Booking.

---

# 29. SERVICE JWT

Payment Service تستخدم:

```text
sub = payment-service
type = SERVICE
```

Customer JWT لا تُمرر كهوية خدمة للـinternal endpoint.

---

# 30. Initiate Payment — Full Flow

```text
1. Authenticate CUSTOMER
2. Require Idempotency-Key
3. Compute requestHash
4. Claim/resolve PaymentIdempotencyRecord
5. BookingClient.getPaymentContext()
6. Validate owner + PENDING + not expired + totalAmount + currency
7. Resolve one Payment for Booking
8. Lock Payment row
9. Inspect active attempt
   OPEN         -> reuse URL
   INITIALIZING -> processing, no duplicate
   UNKNOWN      -> reconciliation required
   none/EXPIRED -> create INITIALIZING
10. Persist Stripe idempotency key
11. COMMIT
12. Call Stripe
13. Receive sessionId + checkoutUrl + expiresAt
14. TX: INITIALIZING -> OPEN, save refs
15. Return checkoutUrl
```

---

# 31. COMMIT BEFORE NETWORK

لا نعمل:

```java
@Transactional
save();
stripeCall();
saveAgain();
```

الصحيح:

```text
TX #1
COMMIT

Stripe HTTP

TX #2
COMMIT
```

حتى لا نمسك DB resources أثناء network uncertainty.

---

# 32. Stripe Idempotency Key

مثال:

```text
checkout-attempt:{attemptId}:create
```

يُحفظ قبل Stripe call.

إذا response ضاعت، نريد recover/retry نفس logical provider operation وليس إنشاء واحدة جديدة.

---

# 33. Stripe Success + Local Finalization Failure

```text
TX #1
Attempt INITIALIZING
Stripe key persisted
COMMIT

Stripe creates Session

TX #2 fails
```

لا ننشئ Attempt ثانية.

الحالة تصبح unresolved:

```text
UNKNOWN / reconciliation candidate
```

---

# 34. success_url ليست Payment Truth

Browser redirect = UX only.

الfrontend قد تعرض:

```text
Payment is being verified...
```

Payment truth تأتي من verified webhook أو Stripe API.

---

# 35. Webhook

```text
Stripe Server
   |
   v
POST /api/webhooks/stripe
```

لا Customer JWT.

---

# 36. Stripe Signature Verification

نستخدم:

```text
raw request body
Stripe-Signature
STRIPE_WEBHOOK_SECRET
```

Invalid signature:

```text
reject
```

---

# 37. لماذا raw body؟

Signature تعتمد على exact bytes.

Parsing/re-serialization قبل verification قد يغير body representation.

---

# 38. Webhook Deduplication

```text
UNIQUE(stripe_event_id)
```

إذا نفس `evt_...` وصل أكثر من مرة، processing تكون idempotent.

---

# 39. Inbox Pattern

```text
incoming webhook
   ↓
persist StripeWebhookEvent
   ↓
process
```

إذا crash، عندنا durable trace.

```text
Inbox  = incoming reliability
Outbox = outgoing publishing reliability
```

Outbox/Kafka لاحقًا.

---

# 40. Webhook Success Flow

```text
verify
↓
dedupe
↓
validate Stripe payment state
↓
TX:
Attempt -> SUCCEEDED
Payment -> SUCCEEDED
BookingConfirmation -> PENDING
COMMIT
↓
BookingClient.confirmPayment()
```

ثم:

```text
success                  -> CONFIRMED
Booking unavailable      -> keep PENDING + reconcile
definitive rejection     -> technical refund
```

---

# 41. Booking Confirmation Idempotency

أول مرة:

```text
PENDING -> CONFIRMED
paymentId = P1
```

Retry بنفس P1:

```text
already CONFIRMED with P1
-> return success
```

أما different paymentId:

```text
conflict / reconciliation
```

---

# 42. Stripe Success + Booking Down

```text
Payment = SUCCEEDED
BookingConfirmation = PENDING
```

لا نكذب ونقول Payment FAILED.

Reconciliation تعيد idempotent confirmation لاحقًا.

---

# 43. Reconciliation

Candidates:

```text
Attempt INITIALIZING too long
Attempt UNKNOWN
WebhookEvent FAILED
Payment SUCCEEDED + Booking sync PENDING
Refund UNKNOWN
```

نبحث عن stuck records فقط.

---

# 44. Reconciliation ليست Blind Retry

```text
inspect persisted state
determine safe action
query Stripe if needed
repeat only idempotent operations
repair state
```

---

# 45. Stripe Expiry vs Booking Expiry

```text
Booking.expiresAt
    owns inventory hold lifetime

Stripe Session expiresAt
    owns Checkout lifetime
```

إذا Stripe Session expired:

```text
Attempt -> EXPIRED
```

Payment Service لا تطلق seats.

---

# 46. Payment Service Never Calls Flight

ممنوع:

```text
StripeWebhook -> Flight.releaseSeats()
```

الصحيح:

```text
Payment -> Booking
Booking -> Flight
```

كل bounded context يحافظ على ownership.

---

# 47. Technical Refund

في Sprint 5 هي compensation فقط:

```text
Stripe SUCCEEDED
+
Booking definitively cannot CONFIRM
→ Refund
```

ليست customer refund feature.

---

# 48. Refund Entity

لاحقًا:

```text
id
paymentId
paymentAttemptId
amount
currency
stripeRefundId
stripeIdempotencyKey
status
reason
createdAt
updatedAt
version
```

Statuses:

```text
PENDING
SUCCEEDED
UNKNOWN
FAILED
```

---

# 49. Refund Timeout

إذا Stripe ربما نفذت refund والresponse ضاعت:

```text
DO NOT create second refund blindly
```

نستخدم stable refund idempotency + UNKNOWN + reconciliation.

---

# 50. Definitive vs Ambiguous

Definitive:

```text
Booking EXPIRED
Booking CANCELLED
not payable
```

Ambiguous:

```text
timeout
connection reset
response lost
crash after provider call
```

القاعدة:

```text
Definitive -> deterministic business action
Ambiguous  -> persist uncertainty + reconcile
```

---

# 51. Domain Status ≠ HTTP Status

`503` من Booking لا يعني Payment FAILED.

لو Stripe paid:

```text
Payment = SUCCEEDED
```

HTTP call result شيء، financial domain truth شيء آخر.

---

# 52. Security Boundaries

```text
Customer -> Payment
    CUSTOMER JWT

Payment -> Booking
    SERVICE JWT

Stripe -> Payment
    Stripe signature
```

---

# 53. Secrets

```text
STRIPE_SECRET_KEY
STRIPE_WEBHOOK_SECRET
```

لا تدخل Git، لا logs، لا frontend.

---

# 54. Logging

مسموح:

```text
paymentId
attemptId
bookingId
stripeSessionId
stripePaymentIntentId
eventId
amount
currency
status
```

ممنوع:

```text
secret keys
Authorization
full card
CVC
```

---

# 55. Local Testing with Stripe CLI

```bash
stripe login
stripe listen --forward-to http://localhost:7174/api/webhooks/stripe
```

CLI تعطي:

```text
whsec_...
```

كـlocal webhook secret.

ngrok ليس ضروريًا للbase Stripe flow.

---

# 56. Manual Happy Path

```text
Login CUSTOMER
↓
Create Booking -> PENDING
↓
POST /api/payments
↓
Payment reads Booking total
↓
Payment/Attempt durable claim
↓
Stripe Checkout Session
↓
checkoutUrl
↓
Customer pays on Stripe
↓
Webhook
↓
Payment SUCCEEDED
↓
Booking CONFIRMED
```

---

# 57. Manual Failure Scenarios

```text
Missing Idempotency-Key
-> 400

Same key + same Booking
-> replay same logical result

Same key + different Booking
-> 409

New key + OPEN attempt
-> reuse checkoutUrl

New key + INITIALIZING
-> no duplicate

New key + UNKNOWN
-> reconcile first

Previous attempt EXPIRED
-> allow new attempt
```

---

# 58. Race: Different Keys, Same Booking

```text
A: K1 -> B1
B: K2 -> B1
```

Payment row lock serializes attempt creation.

Result:

```text
one logical Payment
one unresolved active Attempt
one Stripe creation path
```

---

# 59. Design Patterns Used

```text
Adapter Pattern
Dependency Inversion
Repository Pattern
Orchestrator
Idempotency Pattern
Inbox Pattern
Compensation Pattern
Reconciliation Pattern
State Machine
Pessimistic Locking
Compare-And-Swap
```

---

# 60. لماذا ليست Full Saga بعد؟

لدينا distributed orchestration + compensation + persisted recovery، لكن ما عندنا بعد durable Saga messaging workflow مع Outbox/Kafka.

هذا يأتي عندما نحتاجه في Sprint لاحق، وليس بالاسم فقط.

---

# 61. Interview — Explain Payment Architecture

> I separated payment processing into its own Payment Service. Booking owns the payable snapshot and booking lifecycle, while Payment owns the financial workflow and Stripe integration. The client never sends the authoritative amount; Payment retrieves it from Booking through a service-authenticated internal API. I use Stripe-hosted Checkout so card data and 3DS stay outside our backend. Payment confirmation is webhook-driven, idempotent, and synchronized back to Booking through an idempotent internal confirmation command.

---

# 62. Interview — Why Webhook?

> I don't trust the browser redirect as payment truth because the user may close the browser or never return. The server verifies Stripe webhooks and deduplicates them by Stripe event ID. The webhook updates financial state first, commits it locally, and only then synchronizes Booking.

---

# 63. Interview — Stripe succeeds but Booking is down

> I preserve financial truth: Payment remains SUCCEEDED while BookingConfirmationStatus remains PENDING. I don't mark the payment failed and I don't refund immediately because a service outage is ambiguous, not a definitive business rejection. A reconciliation job retries the idempotent Booking confirmation later.

---

# 64. Interview — Booking expired before payment arrived

> Booking confirmation uses a CAS transition from PENDING to CONFIRMED. If expiry already won and the booking is EXPIRED, I never force confirmation because inventory may already be released. Since Stripe has already taken the money, Payment starts a technical refund as a compensating action.

---

# 65. Interview — Why separate API and Stripe idempotency?

> Client idempotency protects my API from duplicate commands, while Stripe idempotency protects a specific provider mutation during retry or recovery. They solve different failure boundaries, so I persist them separately.

---

# 66. Interview — Why PaymentIdempotencyRecord?

> A second client idempotency key may legitimately map to an already-open Stripe attempt for the same booking. A dedicated idempotency record lets every consumed API key remain traceable without mixing API-command identity with provider-attempt lifecycle.

---

# 67. Interview — Why one active attempt?

> Different idempotency keys can target the same booking concurrently, so same-key idempotency is not enough. I keep one logical Payment per booking and serialize active-attempt creation on that Payment. OPEN is reused, INITIALIZING and UNKNOWN block new attempts, and EXPIRED allows a new attempt.

---

# 68. Interview — Why no PAYMENT_PENDING?

> Payment lifecycle belongs to Payment Service. Booking only needs an atomic PENDING-to-CONFIRMED transition after Stripe success. Unlike cancellation or expiry, Booking has no remote side effect between claiming and finalizing payment confirmation. The trade-off is that booking TTL continues during Checkout; if expiry wins first, a late successful payment is refunded.

---

# 69. Failure Matrix Template

| Scenario | Payment DB | Stripe | Booking | API/Action | Retry? | Refund? | Reconciliation? |
|---|---|---|---|---|---|---|---|
| Example | `UNKNOWN` | unknown | `PENDING` | `503` | only if safe | no | yes |

Phase 2 ستملأ هذه المصفوفة scenario-by-scenario قبل كتابة Payment domain code.

---

# 70. Final Mental Model

```text
Booking decides WHAT must be paid.
Payment tracks WHETHER money moved.
Stripe performs the card transaction.
Webhook reports provider truth.
Idempotency prevents duplicate commands.
Locks prevent competing active attempts.
CAS chooses one Booking transition winner.
Refund compensates an unrecoverable late payment.
Reconciliation repairs uncertain distributed outcomes.
```

---

# 71. Current Sprint 5 Decisions

```text
Stripe only
Stripe-hosted Checkout
separate payment-service + payment_db
BigDecimal
Booking.totalAmount
Booking CONFIRMED
no PAYMENT_PENDING
one Payment per Booking
one unresolved active Attempt
mandatory API Idempotency-Key
PaymentIdempotencyRecord
stable Stripe idempotency keys
verified/deduplicated webhooks
technical refund for late success
reconciliation for ambiguous outcomes
Stripe CLI local testing
```

---

**Sprint 5 — Stripe Payment Complete Reference**
