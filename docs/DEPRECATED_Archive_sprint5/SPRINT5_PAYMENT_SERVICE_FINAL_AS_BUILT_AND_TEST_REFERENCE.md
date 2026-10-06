# Sprint 5 — Payment Service Final As-Built & Test Reference

**Project:** Airline Booking System  
**Service:** `payment-service`  
**Provider:** Stripe  
**Local Port:** `7174`  
**API Gateway:** `8080`  
**Booking Service:** `7173`  
**Date closed for current core scope:** 2026-09-21

---

## 1. Why this file exists

This document is the **final as-built / as-tested reference** for the Stripe payment work completed in Sprint 5.

Keep the older files because they serve different purposes:

- `SPRINT5_STRIPE_PAYMENT_IMPLEMENTATION_PLAN.md` = original execution plan / Definition of Done.
- `SPRINT5_STRIPE_PAYMENT_COMPLETE_REFERENCE.md` = design and architecture reference.
- `SPRINT5_FAILURE_MATRIX.md` = distributed-failure design decisions.
- **This file** = what was actually implemented, tested manually, observed in Stripe, and verified in the databases.

Do not overwrite the older design/reference files with this one.

---

# 2. Final Scope Implemented

The implemented core payment flow includes:

```text
CUSTOMER
   |
   | JWT
   v
API Gateway / Payment Service
   |
   v
Booking payment-context validation
   |
   v
Payment + PaymentAttempt + Idempotency state
   |
   | COMMIT BEFORE NETWORK
   v
Stripe Checkout Session
   |
   v
Customer pays on Stripe hosted Checkout
   |
   v
checkout.session.completed
   |
   | Stripe-Signature verification
   v
StripeWebhookEvent inbox/deduplication
   |
   v
Financial TX
Attempt -> SUCCEEDED
Payment -> SUCCEEDED
BookingConfirmation -> PENDING
COMMIT
   |
   v
Booking Service confirmation using SERVICE JWT
   |
   v
Booking PENDING -> CONFIRMED
   |
   v
Payment.bookingConfirmationStatus -> CONFIRMED
   |
   v
Webhook -> PROCESSED
```

---

# 3. Bounded Context Ownership

## Booking Service owns

```text
Booking lifecycle
Seat reservation lifecycle
Booking expiry
Booking amount snapshot
Booking currency
Passenger data
Booking confirmation state
```

Payment Service **does not** release seats and **does not** call Flight Service directly.

Correct direction:

```text
Payment -> Booking -> Flight
```

## Payment Service owns

```text
Payment lifecycle
PaymentAttempt lifecycle
Client API idempotency
Provider idempotency
Stripe Checkout creation
Stripe webhook verification
Webhook deduplication
Financial success state
Booking confirmation orchestration
Payment-provider references
```

## Stripe owns

```text
Card collection
Card validation
3DS/payment authentication
PaymentIntent
Charge
Checkout Session
Financial provider truth
Webhook event creation
```

The application never stores:

```text
card number
CVC
card expiry
raw card details
```

---

# 4. Core Domain Model

## Payment

One logical `Payment` per `Booking`.

Important fields:

```text
id
bookingId
userId
amount
currency
status
succeededAttemptId
succeededAt
bookingConfirmationStatus
bookingConfirmationLastError
bookingConfirmedAt
version
createdAt
updatedAt
```

Statuses currently used:

```text
PENDING
SUCCEEDED
REFUNDED   // reserved for refund flow
```

Invariant:

```text
UNIQUE(booking_id)
```

## PaymentAttempt

Represents a provider-level payment attempt.

```text
id
paymentId
provider
paymentMethod
providerIdempotencyKey
providerCheckoutId
providerPaymentId
redirectUrl
providerExpiresAt
status
failureReason
resolvedAt
version
createdAt
updatedAt
```

Statuses:

```text
INITIALIZING
OPEN
SUCCEEDED
FAILED
EXPIRED
UNKNOWN
```

Meaning:

```text
INITIALIZING = durable local claim created before Stripe network call
OPEN         = Stripe Checkout exists and can be used
SUCCEEDED    = Stripe financial success verified
FAILED       = definitive provider creation failure
EXPIRED      = provider Checkout expired
UNKNOWN      = provider outcome uncertain and requires recovery/reconciliation
```

## PaymentIdempotencyRecord

Separates **client command idempotency** from the provider attempt.

```text
userId
bookingId
idempotencyKeyHash
requestHash
paymentId
attemptId
```

Rule:

```text
same key + same request      -> replay same logical payment operation
same key + different request -> 409 CONFLICT
```

The raw `Idempotency-Key` is not stored; its hash is stored.

## StripeWebhookEvent

Durable inbox record for incoming Stripe webhooks.

```text
stripeEventId
eventType
payloadHash
processingStatus
paymentId
attemptId
stripeCheckoutSessionId
stripePaymentIntentId
lastError
receivedAt
processedAt
updatedAt
```

Statuses:

```text
RECEIVED
PROCESSING
PROCESSED
FAILED
```

Critical invariant:

```text
UNIQUE(stripe_event_id)
```

---

# 5. Payment Initiation Business Flow

Endpoint:

```http
POST /api/payments
```

Required:

```text
Authorization: Bearer <CUSTOMER JWT>
Idempotency-Key: <client key>
```

Request:

```json
{
  "bookingId": "...",
  "provider": "STRIPE",
  "paymentMethod": "CARD"
}
```

The frontend does **not** send:

```text
amount
currency
userId
Stripe idempotency key
```

Full sequence:

```text
1. Authenticate CUSTOMER
2. Validate request + Idempotency-Key
3. Hash idempotency key + request payload
4. Resolve Stripe PaymentGateway
5. BookingClient.getPaymentContext(bookingId)
6. Validate owner + PENDING + not expired + amount + currency
7. Claim client idempotency record
8. Resolve/create one Payment for Booking
9. Lock Payment decision point
10. Resolve/create PaymentAttempt
11. If new: persist INITIALIZING + providerIdempotencyKey, then COMMIT
12. AFTER COMMIT call Stripe
13. Stripe creates Checkout Session
14. TX: INITIALIZING -> OPEN and save provider refs
15. Return PaymentInitiationResponse
```

---

# 6. Why COMMIT BEFORE NETWORK

Wrong pattern:

```text
BEGIN DB TX
save state
call Stripe over network
wait
save again
COMMIT
```

Chosen pattern:

```text
TX #1
persist durable intent
COMMIT

Stripe network call

TX #2
persist provider result
COMMIT
```

This avoids holding DB resources across uncertain network calls and gives recovery a durable starting point.

---

# 7. Existing Attempt Rules

```text
OPEN         -> return same redirect URL; no new Stripe call
INITIALIZING -> 409 "Payment initiation is currently being processed"
UNKNOWN      -> reconciliation required
FAILED       -> conflict; new attempt requires new policy/key
EXPIRED      -> conflict; start new attempt according to retry policy
SUCCEEDED    -> replay existing logical payment/attempt
```

---

# 8. Stripe Checkout Contract

Stripe-hosted Checkout is used.

```text
mode = payment
payment_method_types = card
amount = Booking authoritative amount
currency = Booking authoritative currency
metadata.payment_id
metadata.attempt_id
metadata.booking_id
client_reference_id = paymentId
```

Browser `success_url` / `cancel_url` are UX only.

```text
Browser redirect != financial truth
```

---

# 9. Local Webhook Setup

```powershell
stripe listen --events checkout.session.completed,checkout.session.expired --forward-to http://localhost:7174/api/webhooks/stripe
```

Use the `whsec_...` printed by that **active CLI listener**:

```properties
stripe.webhook-secret=whsec_...
```

Webhook endpoint:

```http
POST /api/webhooks/stripe
```

Security is based on:

```text
raw request body
+
Stripe-Signature
+
webhook signing secret
```

---

# 10. Webhook Processing Flow

```text
Stripe event
  -> verify signature
  -> register/dedupe by stripe_event_id
  -> claim for processing
  -> route event
```

For `checkout.session.completed`, validate:

```text
mode = payment
payment_status = paid
metadata.payment_id
metadata.attempt_id
metadata.booking_id
client_reference_id
PaymentIntent exists
amount == local Payment amount
currency == local Payment currency
```

Only then can financial state change.

---

# 11. Financial Success Transaction

```text
PaymentAttempt -> SUCCEEDED
providerPaymentId = Stripe PaymentIntent ID
resolvedAt = now

Payment PENDING -> SUCCEEDED
succeededAttemptId = canonical Attempt
succeededAt = now
bookingConfirmationStatus -> PENDING
```

This commits **before** the remote Booking confirmation call.

---

# 12. Canonical Successful Attempt

Only one Attempt is canonical:

```text
Payment.succeededAttemptId
```

Canonical replay:

```text
same canonical Attempt -> idempotent replay
```

Different real successful Attempt:

```text
do not overwrite succeededAttemptId
do not call it FAILED
later compensate with technical refund
```

---

# 13. Booking Confirmation Flow

After financial success commits:

```text
Payment Service
  -> SERVICE JWT
  -> POST /internal/bookings/{bookingId}/payments/{paymentId}/confirm
```

Booking transition:

```text
PENDING -> CONFIRMED
paymentId = Payment.id
confirmedAt = now
```

Replay with same `paymentId` is idempotent.

Payment then persists:

```text
bookingConfirmationStatus = CONFIRMED
bookingConfirmedAt != null
bookingConfirmationLastError = null
```

---

# 14. Definitive vs Ambiguous Booking Failures

## Definitive

Examples:

```text
404 / 409 / 410 / 422
```

Target:

```text
Payment = SUCCEEDED
BookingConfirmationStatus = REJECTED
```

Eventually compensate with technical refund.

## Ambiguous

Examples:

```text
401 / 403 internal auth/config issue
5xx
timeout
connection error
unknown infrastructure failure
```

Target:

```text
Payment = SUCCEEDED
BookingConfirmationStatus = PENDING
bookingConfirmationLastError = ...
```

Do not refund on ambiguity; reconciliation owns recovery.

---

# 15. Transaction Boundaries

```text
PaymentServiceImpl
= orchestration
= no long transaction
```

Short durable operations live in transaction services.

```text
TX A: client idempotency / Payment / Attempt claim -> COMMIT
Stripe API call
TX B: Attempt -> OPEN -> COMMIT

Webhook:
TX C: Attempt -> SUCCEEDED + Payment -> SUCCEEDED + Booking sync -> PENDING -> COMMIT
NO DB TX around Booking remote call
TX D: persist CONFIRMED / REJECTED / PENDING error -> COMMIT
```

---

# 16. Concurrency Controls

Database protections include:

```text
Payment.bookingId UNIQUE
PaymentIdempotencyRecord(userId, idempotencyKeyHash) UNIQUE
StripeWebhookEvent.stripeEventId UNIQUE
provider checkout/payment uniqueness where applicable
```

Payment-row locking serializes active-attempt decisions.

Goal:

```text
At most one unresolved active Attempt:
INITIALIZING / OPEN / UNKNOWN
```

Entities also use optimistic `@Version` where appropriate.

Booking confirmation relies on atomic status transitions/CAS semantics.

---

# 17. Three Idempotency Layers

## Client API idempotency

```text
Idempotency-Key
```

Protects `/api/payments` from frontend retries/double clicks.

## Stripe provider idempotency

Stable key stored on `PaymentAttempt` protects provider mutation/recovery.

## Webhook deduplication

```text
UNIQUE(stripe_event_id)
```

Protects repeated Stripe deliveries.

These solve three different failure boundaries.

---

# 18. Manual E2E Test Results

## A. Golden Happy Path — USD 150

```text
[PASS] Stripe Checkout opens
[PASS] Stripe payment succeeds
[PASS] checkout.session.completed arrives
[PASS] Stripe signature verifies
[PASS] webhook HTTP 200
[PASS] StripeWebhookEvent = PROCESSED
[PASS] PaymentAttempt = SUCCEEDED
[PASS] Payment = SUCCEEDED
[PASS] Booking confirmation = CONFIRMED
[PASS] Booking = CONFIRMED
[PASS] Booking.paymentId matches Payment.id
```

Observed logs included:

```text
Payment succeeded with canonical attempt
Canonical Stripe financial success committed
Booking confirmation call succeeded
Booking confirmation persisted successfully
Stripe webhook processed successfully
```

## B. Invalid Webhook Signature

Observed:

```text
checkout.session.completed arrived
signature verification failed
HTTP 400
```

Safety result:

```text
Payment unchanged
Attempt unchanged
untrusted event not used as financial truth
```

Result: **PASS**

## C. Stripe SDK / API Compatibility Failure

Initial Stripe event API:

```text
2025-07-30.basil
```

Old SDK could verify signature but failed model deserialization:

```text
Stripe webhook data object could not be deserialized
```

Safe observed state:

```text
Webhook = FAILED
Payment = PENDING
Attempt = OPEN
```

Fix:

```text
Upgrade Stripe Java SDK to compatible 29.x line
```

After upgrade, real E2E passed.

## D. Duplicate Webhook Replay

Event replayed:

```text
evt_1UHkVRDMK7TQCRel1rpyYx6n
```

Observed:

```text
HTTP 200
same logical webhook row
Payment remained SUCCEEDED
Attempt remained SUCCEEDED
Booking remained CONFIRMED
no duplicate financial side effect
```

Result: **PASS**

## E. Concurrent Duplicate Webhook — USD 230

Event:

```text
evt_1UHmggDMK7TQCRelFKm4jjSK
```

The same event was resent concurrently/multiple times.

Observed:

```text
multiple deliveries
HTTP 200 responses
one logical StripeWebhookEvent
Webhook = PROCESSED
Payment = SUCCEEDED
Attempt = SUCCEEDED
Booking = CONFIRMED
```

Result: **PASS**

## F. Payment API Idempotency — Same Key + Same Request

```text
Idempotency-Key: payment-idem-001
```

Observed:

```text
same Payment
same Attempt
same redirectUrl
no extra Payment row
no extra Attempt row
no extra idempotency mapping
no extra Stripe Checkout
```

Result: **PASS**

## G. Payment API Idempotency — Same Key + Different Request

Observed:

```http
409 CONFLICT
```

Message:

```text
The same Idempotency-Key was already used with a different request
```

DB unchanged; no Stripe Checkout created.

Result: **PASS**

## H. Concurrent Payment API Idempotency

Two same-key requests were sent almost simultaneously.

Observed:

```text
Request #1 -> success
Request #2 -> 409 while shared Attempt was INITIALIZING
```

Expected invariant:

```text
1 Payment
1 Attempt
1 idempotency mapping
1 Stripe Checkout operation
```

This matches the current design: a request racing the active initiator gets a safe conflict while `INITIALIZING`; later replay after `OPEN` returns the existing Checkout.

Result: **PASS**

---

# 19. Historical Diagnostic Case Kept Intentionally — USD 120

Earlier flow:

```text
Stripe payment succeeded
first webhook delivery -> 400 due wrong CLI signing secret
after secret fix -> accepted
old Stripe SDK then failed deserialization -> 500
WebhookEvent -> FAILED
Booking later -> EXPIRED
Payment remained PENDING
Attempt remained OPEN
Stripe financial truth remained succeeded
```

Do not treat it as Happy Path data.

Keep it as a future test case for:

```text
reconciliation
stranded financial truth
late payment
technical refund
```

---

# 20. Booking Expiry vs Stripe Checkout Expiry

```text
Booking.expiresAt = inventory hold lifetime
Stripe expiresAt  = Checkout lifetime
```

These are intentionally different responsibilities.

Payment Service does not release seats.

---

# 21. Timezone Note

Stripe Workbench commonly displays UTC.
Spring/local logs may show `+03:00`.

Example:

```text
15:29 UTC = 18:29 Jordan local time
```

Hardening recommendation:

```text
Prefer Instant / OffsetDateTime for distributed financial timestamps.
```

---

# 22. Stripe Dashboard Areas Used

## Workbench -> Events

Used for:

```text
checkout.session.completed
payment_intent.succeeded
charge.succeeded
charge.updated
```

Core webhook events currently listened for:

```text
checkout.session.completed
checkout.session.expired
```

## Workbench -> Logs

Used to verify provider calls such as:

```text
POST /v1/checkout/sessions
```

This provided proof that API idempotency did not create duplicate Stripe Checkout Sessions.

---

# 23. Bugs / Issues Found by Real E2E

## Wrong CLI webhook signing secret

Symptom:

```text
400 Invalid Stripe webhook signature
```

Fix:

```text
use the whsec from the active stripe listen process
restart payment-service
```

## Stripe SDK too old

Symptom:

```text
500
Stripe webhook data object could not be deserialized
```

Fix:

```text
upgrade Stripe Java SDK to compatible 29.x
```

## Multi-catch hierarchy changes after SDK upgrade

`RateLimitException` / `PermissionException` became related to exceptions already present in the same catch hierarchy.

Fix:

```text
Do not include parent and child exception alternatives in the same Java multi-catch.
```

## HTTP wrapper status mismatch

Observed:

```text
real HTTP status = 201 Created
body status field = 200 OK
```

Not a payment-domain failure, but should be cleaned up.

---

# 24. Security Model Verified in Core Flow

Customer initiation:

```text
CUSTOMER JWT
```

Internal Payment -> Booking confirmation:

```text
SERVICE JWT
sub = payment-service
role/type = SERVICE
```

Webhook:

```text
JWT bypassed
Stripe signature required
```

`permitAll` for the webhook route does not mean financial trust; Stripe signature verification is the authentication boundary.

---

# 25. Current Package Responsibilities

```text
client/
  Booking integration / Feign / integration exceptions

config/
  Security / Stripe / OpenAPI

controller/
  Payment initiation / Stripe webhook

dto/
  public request/response contracts

entity/
  Payment / PaymentAttempt / PaymentIdempotencyRecord / StripeWebhookEvent

enums/
  payment / attempt / webhook / booking-sync statuses

gateway/
  provider abstraction / Stripe adapter / SDK isolation

mapper/
  API response mapping

repository/
  persistence / locking / CAS queries

security/
  customer JWT / internal SERVICE JWT

service/
  business interfaces

service/impl/
  payment orchestration / DB transaction boundaries / webhook orchestration / booking confirmation

webhook/
  Stripe signature verification / event handlers
```

---

# 26. What the Current Sprint Proved

```text
1. Booking is the source of truth for amount/currency.
2. Customer cannot choose arbitrary payment amount.
3. Payment creation is idempotent.
4. Concurrent initiation does not duplicate provider operations.
5. Stripe Checkout creation is outside the long DB transaction.
6. Stripe webhook signature is required.
7. Duplicate webhook replay is safe.
8. Concurrent duplicate webhook delivery is safe.
9. Stripe financial success commits before Booking confirmation.
10. Payment success is not rolled back by Booking synchronization.
11. Booking confirmation uses SERVICE-to-SERVICE authentication.
12. Successful real Stripe sandbox E2E works.
13. Provider SDK compatibility was validated in real integration.
14. No raw card data is stored.
```

---

# 27. Deferred Work

## Technical Refund

Still needed for:

```text
Stripe SUCCEEDED + Booking definitively cannot CONFIRM
late payment after Booking EXPIRED
duplicate real financial success
```

Expected refund statuses:

```text
PENDING
SUCCEEDED
UNKNOWN
FAILED
```

`Payment` should become `REFUNDED` only after provider-confirmed refund success.

## Reconciliation

Still needed for:

```text
Attempt INITIALIZING too long
Attempt UNKNOWN
WebhookEvent FAILED
Payment SUCCEEDED + BookingConfirmation PENDING
Refund UNKNOWN
```

Reconciliation must inspect durable state and take only safe/idempotent recovery actions.

---

# 28. Sprint Closure Decision

If the **original Sprint 5 Definition of Done remains strict**, Sprint 5 is not 100% complete because technical refund and reconciliation were originally included in closure criteria.

If the team chooses to move forward now, use this explicit status:

```text
SPRINT 5 CORE PAYMENT INTEGRATION — CLOSED

Completed:
- Stripe Checkout
- payment-service domain
- Booking payment context
- API idempotency
- provider idempotency foundation
- webhook signature verification
- webhook inbox/deduplication
- financial success
- Booking confirmation
- concurrency protections
- real Stripe sandbox E2E
- manual failure/idempotency tests

Deferred:
- technical refund execution
- reconciliation scheduler/recovery engine
```

This lets the project continue without pretending the deferred reliability work is already implemented.

---

# 29. Suggested Sprint 6 Handoff

Do not redesign Payment core.

Treat current Payment state transitions as stable business truth and add messaging around them.

Likely Sprint 6 direction:

```text
Kafka + Outbox
```

Potential future domain events:

```text
PaymentSucceeded
BookingPaymentConfirmed
PaymentRefunded
PaymentReconciliationRequired
```

Important rule:

```text
Never publish Kafka directly as the only side effect inside the financial DB transaction.
Persist business state + Outbox atomically, then publish asynchronously.
```

---

# 30. Final Mental Model

```text
Booking decides WHAT must be paid.
Payment tracks WHETHER money moved.
PaymentAttempt tracks HOW one provider attempt progressed.
Stripe performs the card transaction.
Webhook reports provider financial truth.
Client Idempotency-Key prevents duplicate commands.
Stripe idempotency prevents duplicate provider mutation.
Webhook event ID prevents duplicate event processing.
Payment locking prevents competing active attempts.
Booking CAS decides one booking lifecycle winner.
SERVICE JWT protects internal confirmation APIs.
Refund compensates definitive post-payment business failure.
Reconciliation repairs ambiguous or stranded distributed state.
```

---

# 31. Final Test Checklist Snapshot

```text
[PASS] Stripe hosted Checkout opens
[PASS] Real sandbox card payment succeeds
[PASS] checkout.session.completed received
[PASS] Valid signature accepted
[PASS] Invalid signature rejected
[PASS] Webhook FAILED safely on incompatible SDK model
[PASS] SDK compatibility fixed
[PASS] Attempt becomes SUCCEEDED
[PASS] Payment becomes SUCCEEDED
[PASS] Booking becomes CONFIRMED
[PASS] Payment bookingConfirmation becomes CONFIRMED
[PASS] Same webhook replay is safe
[PASS] Concurrent duplicate webhook delivery is safe
[PASS] Same API idempotency key + same request is safe
[PASS] Same API idempotency key + different request returns 409
[PASS] Concurrent same-key initiation does not duplicate Payment/Attempt/Stripe Checkout
[PASS] Stripe API logs used to verify one Checkout creation
[PASS] No raw card data stored

[DEFERRED] Technical refund execution
[DEFERRED] Late-payment automatic refund
[DEFERRED] Reconciliation scheduler/recovery
[DEFERRED] Refund ambiguity recovery
```

---

**Recommended repository filename**

```text
SPRINT5_PAYMENT_SERVICE_FINAL_AS_BUILT_AND_TEST_REFERENCE.md
```
