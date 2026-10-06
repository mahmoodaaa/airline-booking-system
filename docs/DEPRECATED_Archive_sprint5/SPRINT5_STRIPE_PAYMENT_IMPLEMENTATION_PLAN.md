# Sprint 5 — Stripe Payment Integration Implementation Plan

> **Project:** Airline Booking System  
> **Sprint:** 5  
> **Scope:** Payment Service + Stripe only  
> **Document type:** Living execution plan  
> **Status:** PLANNING / NOT STARTED  
> **Rule:** Update this file continuously during implementation, testing, design changes, and sprint closure.

---

# 0. Sprint 5 Scope

Sprint 5 is intentionally limited to:

```text
Booking Service
      ↓
Payment Service
      ↓
Stripe Checkout
      ↓
Stripe Webhook
      ↓
Payment Service
      ↓
Booking CONFIRMED
```

## In Scope

- Separate `payment-service`
- Separate `payment_db`
- Stripe only
- Stripe-hosted Checkout
- Card payments only
- Payment idempotency
- Stripe idempotency
- Stripe webhook signature verification
- Webhook deduplication
- Booking ↔ Payment internal contract
- Booking confirmation
- Late-payment compensation via Stripe Refund
- Payment reconciliation
- Stripe CLI local webhook testing
- Automated tests
- Sprint documentation

## Explicitly Out of Scope

- PayPal
- MEPS
- Kafka
- Outbox
- Redis
- Eureka
- Resilience4j / Circuit Breaker
- Subscriptions
- Saved cards
- Apple Pay
- Google Pay
- FX conversion
- Partial refunds
- Customer refund policies
- Accounting settlement
- Chargeback automation

---

# 1. Core Architectural Rules

## 1.1 Ownership

```text
Booking Service
    owns:
    - booking lifecycle
    - reserved seats relationship
    - payable booking snapshot
    - totalAmount
    - currency
    - expiration
    - CONFIRMED / CANCELLED / EXPIRED

Payment Service
    owns:
    - payment lifecycle
    - payment attempts
    - Stripe references
    - webhook processing
    - payment reconciliation
    - refund compensation

Stripe
    owns:
    - card collection
    - card validation
    - 3DS
    - payment processing
    - Checkout Session
    - PaymentIntent
    - Charge
    - Refund execution
```

## 1.2 Payment truth

```text
Browser redirect != payment truth
Frontend message != payment truth

Verified Stripe webhook / Stripe API
= payment truth
```

## 1.3 No client-controlled amount

Client sends only:

```json
{
  "bookingId": "..."
}
```

Amount/currency must come from Booking Service.

## 1.4 No card data in backend

The Payment Service must never receive or persist:

```text
card number
CVC
full card details
```

Stripe-hosted Checkout handles card entry.

## 1.5 No remote call inside long DB transaction

```text
COMMIT BEFORE NETWORK
```

Pattern:

```text
TX #1
local durable state
COMMIT

Stripe / Booking remote call

TX #2
local finalization
COMMIT
```

## 1.6 Never guess distributed state

If Stripe/network outcome is uncertain:

```text
DO NOT invent success
DO NOT invent failure
DO NOT blindly create a duplicate operation
```

Persist a recoverable state and reconcile.

---

# 2. Stripe Integration Strategy

Use:

```text
Stripe Checkout Sessions
+
Stripe-hosted Checkout
```

Conceptually:

```text
Checkout Session
      ↓
PaymentIntent
      ↓
Charge
```

Why:
- hosted checkout UI
- card data outside backend
- 3DS handled by Stripe
- lower PCI scope
- easier end-to-end testing
- less frontend work initially

---

# 3. Booking Service Changes

## 3.1 Booking Status

Add:

```text
CONFIRMED
```

Final relevant flow:

```text
                   PENDING
                /     |      \
               /      |       \
              v       v        v
       CANCELLING  EXPIRING  CONFIRMED
            |          |
            v          v
       CANCELLED     EXPIRED
```

Payment lifecycle remains inside Payment Service.

## 3.2 Add totalAmount

```java
BigDecimal totalAmount;
```

Example:

```text
priceAtBooking = 120.00
passengerCount = 2
totalAmount = 240.00
currency = USD
```

Payment Service consumes the final total only.

## 3.3 Payment reference on Booking

After successful confirmation:

```text
paymentId
confirmedAt
status = CONFIRMED
```

No cross-service FK.

## 3.4 Internal payment context endpoint

Conceptual endpoint:

```http
GET /internal/bookings/{bookingId}/payment-context
```

Returns:

```text
bookingId
userId
status
totalAmount
currency
expiresAt
```

## 3.5 Internal confirmation endpoint

```http
POST /internal/bookings/{bookingId}/payments/{paymentId}/confirm
```

Protected with:

```text
type = SERVICE
sub = payment-service
```

Behavior:

```text
PENDING -> CONFIRMED
```

using atomic CAS.

Same `bookingId + paymentId` must be idempotent.

---

# 4. Payment Service Architecture

Create:

```text
services/payment-service
```

Suggested port:

```text
7174
```

Database:

```text
payment_db
```

Architecture:

```text
PaymentController
      |
      v
PaymentServiceImpl
   /       |          \
  v        v           v
Booking   Payment     PaymentGateway
Client    TX Service       |
                           v
                 StripePaymentGateway
                           |
                           v
                    Stripe Java SDK
```

Webhook path:

```text
Stripe
   ↓
StripeWebhookController
   ↓
StripeWebhookService
   ↓
signature verification
deduplication
payment update
Booking confirmation
```

---

# 5. Payment Domain

## 5.1 Payment

Conceptual fields:

```text
id
bookingId
userId
amount
currency
status
bookingConfirmationStatus
createdAt
updatedAt
version
```

**`PaymentStatus`** — locked:

```text
PENDING    = payment initiated, Stripe not yet confirmed
SUCCEEDED = Stripe confirmed payment
REFUNDED   = technical compensation refund completed
```

Note: `REFUND_PENDING` is intentionally absent.
Refund lifecycle lives in `Refund.status`, not `PaymentStatus`.
Payment remains `SUCCEEDED` until Refund is confirmed `SUCCEEDED`.

**`BookingConfirmationStatus`** — locked:

```text
NOT_STARTED = payment not yet attempted
PENDING     = confirmation in flight / outcome unknown / retryable
CONFIRMED   = Booking confirmed via CAS (PENDING → CONFIRMED)
REJECTED    = Booking definitively refused (EXPIRED, CANCELLED)
              → triggers technical refund
```

Note: `FAILED` replaced by `REJECTED` (definitive) + `PENDING` (ambiguous/retryable).

Important state:

```text
Payment = SUCCEEDED
BookingConfirmationStatus = PENDING
```

means money succeeded but Booking sync is not finished — reconciliation retries.

## 5.2 PaymentAttempt

Conceptual fields:

```text
id
paymentId
status
stripeIdempotencyKey     ← persisted BEFORE Stripe call
stripeCheckoutSessionId
stripePaymentIntentId
checkoutUrl
stripeExpiresAt
createdAt
updatedAt
version
```

**Not included (belongs to PaymentIdempotencyRecord):**
- `clientIdempotencyKey` ❌
- `requestHash` ❌

Reason: One Attempt can be reached by multiple external API keys (K1, K2 → same A1).
A single `clientIdempotencyKey` field cannot represent that correctly.

Suggested statuses:

```text
INITIALIZING
OPEN
SUCCEEDED
EXPIRED
UNKNOWN
FAILED
```

## 5.3 StripeWebhookEvent

Conceptual fields:

```text
id
stripeEventId
eventType
stripeCheckoutSessionId
processingStatus     ← RECEIVED, PROCESSING, PROCESSED, FAILED
receivedAt
processedAt
payloadHash
lastError
```

Constraint:

```text
UNIQUE(stripe_event_id)
```

---

# 6. Money Model

Use:

```java
BigDecimal
```

Never `double` or `float`.

Start base Stripe flow with:

```text
USD
```

Provider-specific minor-unit conversion should live in a Stripe adapter/helper, not business code.

---

# 7. External Payment API

```http
POST /api/payments
Authorization: Bearer <CUSTOMER JWT>
Idempotency-Key: ...
```

Body:

```json
{
  "bookingId": "..."
}
```

Response:

```json
{
  "paymentId": "...",
  "attemptId": "...",
  "status": "PENDING",
  "checkoutUrl": "https://checkout.stripe.com/..."
}
```

Potential reads:

```http
GET /api/payments/{paymentId}
GET /api/payments/booking/{bookingId}
```

Ownership must be enforced.

---

# 8. Payment Idempotency

## 8.1 Client -> Payment Service

Persist:

```text
userId
idempotencyKey
requestHash
```

Initial hash:

```text
bookingId
```

Rules:

```text
same key + same hash
→ same logical attempt

same key + different hash
→ 409 Conflict
```

## 8.2 Payment Service -> Stripe

Stable Stripe idempotency key per operation.

Example:

```text
checkout-attempt:{attemptId}:create
```

Future refund:

```text
refund:{refundId}
```

Never generate a new Stripe idempotency key when retrying the same logical provider operation.

---

# 9. Booking Client

Use the Sprint 4.5 pattern:

```text
BookingClient
     ↓
FeignBookingClient
     ↓
BookingFeignClient
```

Internal token:

```text
sub = payment-service
type = SERVICE
```

Initial calls:

```text
getPaymentContext(...)
confirmPayment(...)
```

---

# 10. PaymentGateway Abstraction

Business layer must not depend directly on Stripe SDK classes.

```text
PaymentGateway
      ↓
StripePaymentGateway
      ↓
Stripe Java SDK
```

Conceptual interface:

```java
public interface PaymentGateway {

    CheckoutCreationResult createCheckoutSession(
            CheckoutCreationCommand command
    );

    CheckoutStatusResult getCheckoutSession(
            String stripeSessionId
    );

    RefundResult refund(
            RefundCommand command
    );
}
```

Exact signatures are deferred until implementation.

No provider registry yet because Sprint 5 is Stripe only.

---

# 11. Stripe Checkout Creation Flow

```text
Customer
   ↓
POST /api/payments
   ↓
validate API idempotency
   ↓
BookingClient.getPaymentContext()
   ↓
validate:
- owner
- PENDING
- not expired
- amount
- currency
   ↓
TX #1
- create/reuse Payment
- create Attempt INITIALIZING
- persist Stripe idempotency key
COMMIT
   ↓
StripePaymentGateway.createCheckoutSession()
   ↓
Stripe returns:
- sessionId
- checkoutUrl
- expiresAt
   ↓
TX #2
Attempt INITIALIZING -> OPEN
save Stripe references
COMMIT
   ↓
return checkoutUrl
```

---

# 12. Stripe Checkout Request

Conceptually:

```text
mode = payment
amount = Booking.totalAmount
currency = Booking.currency
payment method = card

metadata:
paymentId
attemptId
bookingId

success_url
cancel_url
```

---

# 13. success_url / cancel_url

These are UX only.

Never treat redirect as payment truth.

Frontend can show:

```text
Payment is being verified...
```

and query Payment Service.

---

# 14. Stripe Webhook

Endpoint:

```http
POST /api/webhooks/stripe
```

No Customer JWT.

Trust comes from Stripe signature verification using:

```text
raw request body
Stripe-Signature
STRIPE_WEBHOOK_SECRET
```

Primary events:

```text
checkout.session.completed
checkout.session.expired
```

---

# 15. Webhook Deduplication

Flow:

```text
receive webhook
   ↓
verify signature
   ↓
insert StripeWebhookEvent
   ↓
duplicate?
   ├─ yes -> return safely
   └─ no  -> process
```

Unique:

```text
stripeEventId
```

Webhook processing must also be safe concurrently.

---

# 16. Successful Payment Flow

```text
Stripe webhook
   ↓
verify + dedupe
   ↓
validate Checkout Session/payment status
   ↓
TX #1
Attempt -> SUCCEEDED
Payment -> SUCCEEDED
BookingConfirmationStatus -> PENDING
COMMIT
   ↓
BookingClient.confirmPayment()
   ↓
success
   ↓
TX #2
BookingConfirmationStatus -> CONFIRMED
COMMIT
```

If Booking is unavailable:

```text
Payment remains SUCCEEDED
BookingConfirmationStatus remains PENDING
```

Reconciliation handles it later.

---

# 17. Scheduler / Cancel / Payment Race

Possible atomic transitions from `PENDING`:

```text
PENDING -> CANCELLING
PENDING -> EXPIRING
PENDING -> CONFIRMED
```

All use conditional DB update / CAS.

Only one actor wins.

---

# 18. Late Payment

Critical scenario:

```text
Customer paying on Stripe

Scheduler:
PENDING -> EXPIRING -> EXPIRED
seats released

Stripe later:
payment succeeds

Payment tries Booking confirm
Booking rejects because EXPIRED
```

Correct action:

```text
technical Stripe refund
```

Never force:

```text
EXPIRED -> CONFIRMED
```

---

# 19. Technical Refund

Only compensation refund is in scope.

Reason:

```text
PAYMENT_SUCCEEDED_BUT_BOOKING_CANNOT_CONFIRM
```

Not in scope:
- user refund policy
- partial refunds
- airline fees

Suggested `Refund` later:

```text
id
paymentId
paymentAttemptId
amount
currency
stripeRefundId
stripeIdempotencyKey     ← stable, persisted before Stripe refund call
status
reason
createdAt
updatedAt
version
```

**`RefundStatus`** — locked:

```text
PENDING    = refund claim created, Stripe call not yet made
SUCCEEDED = Stripe confirmed refund
UNKNOWN   = Stripe outcome uncertain → reconciliation required
FAILED    = Stripe definitively failed → manual escalation
```

Note: `Refund.PENDING` is intentionally different from `PAYMENT_PENDING` (rejected from Booking).
`Refund.PENDING` follows the same COMMIT-BEFORE-NETWORK pattern as Booking's CANCELLING/EXPIRING —
a durable local claim before the remote Stripe side effect.

---

# 20. Definitive vs Ambiguous Failures

## Definitive Booking failures

Examples:

```text
EXPIRED
CANCELLED
not payable
wrong owner
```

Before payment:
→ reject initiation.

After Stripe success:
→ technical refund if Booking definitively cannot confirm.

## Ambiguous Stripe/network failures

Examples:

```text
timeout
connection reset
local crash after Stripe may have created a session
```

Do not create another logical operation blindly.

Use stable provider idempotency and reconciliation.

---

# 21. Stripe Success + Local Finalization Failure

Scenario:

```text
TX #1:
Attempt INITIALIZING
Stripe idempotency key saved
COMMIT

Stripe:
Checkout Session created

TX #2:
local finalize fails
```

Result:

```text
Attempt -> UNKNOWN
```

Recovery should use stable Stripe idempotency and/or Stripe retrieval.

Exact SDK recovery behavior will be finalized during implementation.

---

# 22. Reconciliation

Create:

```text
PaymentReconciliationService
PaymentReconciliationJob
```

Candidates:

```text
Attempt INITIALIZING too long
Attempt UNKNOWN
Payment SUCCEEDED + BookingConfirmationStatus=PENDING
Refund UNKNOWN
WebhookEvent FAILED
```

Use bounded batches.

---

# 23. Checkout Expiration

On:

```text
checkout.session.expired
```

Payment Service does:

```text
PaymentAttempt -> EXPIRED
```

It does NOT call Flight Service.

Booking Scheduler remains the owner of inventory release.

---

# 24. Security

External Payment API:

```text
CUSTOMER JWT
```

Internal Booking calls:

```text
SERVICE JWT
sub = payment-service
type = SERVICE
```

Stripe webhook:

```text
no Customer JWT
Stripe signature verification
```

Never commit:
- Stripe secret key
- Stripe webhook secret
- JWT secret

---

# 25. Logging

Allowed:

```text
paymentId
attemptId
bookingId
Stripe session ID
Stripe PaymentIntent ID
status
amount
currency
event ID
```

Never log:
- Stripe secret
- webhook secret
- Authorization header
- full card data
- CVC

---

# 26. Local Stripe Testing

Use test mode:

```text
sk_test_...
```

Stripe CLI:

```bash
stripe login
```

```bash
stripe listen --forward-to http://localhost:7174/api/webhooks/stripe
```

Use the CLI-generated:

```text
whsec_...
```

as local webhook secret.

ngrok is not required for the first Stripe implementation.

---

# 27. Manual E2E Scenarios

## Happy path

```text
1. Login CUSTOMER
2. Create Booking -> PENDING
3. POST /api/payments
4. Receive checkoutUrl
5. Open Stripe Checkout
6. Pay using test card
7. Stripe webhook reaches Payment Service
8. Attempt -> SUCCEEDED
9. Payment -> SUCCEEDED
10. Booking -> CONFIRMED
11. Seats remain reserved
```

## Decline

```text
Stripe displays decline
Attempt stays OPEN while Checkout remains usable
Booking stays PENDING
```

## Session expiry

```text
Checkout Session expires
Attempt -> EXPIRED
Booking Scheduler still owns Booking expiry/release
```

## Late payment

```text
Booking -> EXPIRED
Stripe -> successful payment
Booking confirmation rejected
technical refund starts
```

---

# 28. Automated Testing Baseline

Required critical cases:

- [ ] Happy Checkout Session creation
- [ ] Amount comes only from Booking
- [ ] Cannot pay another user's Booking
- [ ] EXPIRED Booking cannot start payment
- [ ] CANCELLED Booking cannot start payment
- [ ] Same idempotency key + same request returns same attempt
- [ ] Same key + different booking returns `409`
- [ ] Concurrent same idempotency key creates one logical attempt
- [ ] Stripe create timeout handled without duplicate logical operation
- [ ] Stripe success + local finalization failure is recoverable
- [ ] Valid webhook signature accepted
- [ ] Invalid webhook signature rejected
- [ ] Duplicate webhook processed once
- [ ] Concurrent duplicate webhook processed once
- [ ] Completed Checkout marks Payment successful
- [ ] Expired Checkout marks Attempt expired
- [ ] Payment success confirms Booking
- [ ] Duplicate Booking confirmation is safe
- [ ] Booking down after payment keeps Payment `SUCCEEDED`
- [ ] Reconciliation confirms later
- [ ] Late success against EXPIRED Booking starts refund
- [ ] Scheduler vs confirmation has one CAS winner
- [ ] Refund ambiguous result is not blindly duplicated
- [ ] Reconciliation resolves stuck states

---

# 29. Execution Checklist

## Phase 0 — Design Lock

- [x] Stripe only
- [x] Stripe-hosted Checkout
- [x] `payment-service` as separate bounded context
- [x] Booking gets `CONFIRMED`
- [x] Booking keeps Stripe-specific lifecycle out
- [x] Payment / Attempt / Webhook concepts defined
- [x] Two-layer idempotency defined
- [x] Webhook defined as payment truth
- [x] Late payment refund required
- [x] Reconciliation required

## Phase 1 — Booking Payment Model

- [x] Add `CONFIRMED`
- [x] Add `totalAmount`
- [x] Add `paymentId`
- [x] Add `confirmedAt`
- [x] Add payment-context DTO
- [x] Add payment-context internal endpoint
- [x] Authorize `payment-service` SERVICE JWT
- [x] Add idempotent confirm endpoint
- [x] Add atomic `PENDING -> CONFIRMED`
- [x] Ensure Scheduler ignores `CONFIRMED`
- [x] Define Cancel behavior for `CONFIRMED`
- [x] Add/update tests
- [x] Re-run Sprint 4 regression

## Phase 2 — Distributed Failure Matrix

- [x] Booking invalid
- [x] Booking expired
- [x] Booking cancelled
- [x] Invalid/missing amount
- [x] Payment DB claim failure
- [x] Stripe definitive error
- [x] Stripe timeout
- [x] Stripe success + local finalize failure
- [x] User closes Checkout
- [x] Card decline
- [x] Checkout expires
- [x] Success webhook
- [x] Duplicate webhook
- [x] Booking confirmation response lost
- [x] Booking Service down
- [x] Booking expired before confirmation
- [x] Scheduler vs payment race
- [x] Refund success
- [x] Refund timeout
- [x] Reconciliation recovery

## Phase 3 — Payment Service Setup

- [ ] Create Maven module
- [ ] Add to services aggregator
- [ ] Configure port `7174`
- [ ] Create `payment_db`
- [ ] Configure JPA
- [ ] Configure Security
- [ ] Configure OpenAPI
- [ ] Add OpenFeign
- [ ] Add Stripe Java SDK
- [ ] Configure Stripe env variables
- [ ] Verify startup

## Phase 4 — Payment Domain

- [ ] Create `Payment`
- [ ] Create `PaymentAttempt`
- [ ] Create enums
- [ ] Add `@Version`
- [ ] Add timestamps
- [ ] Add idempotency fields
- [ ] Add Stripe reference fields
- [ ] Add DB constraints
- [ ] Create repositories
- [ ] Create DTOs
- [ ] Create mappers
- [ ] Add tests

## Phase 5 — BookingClient

- [ ] Create `BookingClient`
- [ ] Create `BookingFeignClient`
- [ ] Create Feign adapter
- [ ] Create Feign config
- [ ] Add SERVICE JWT interceptor
- [ ] Define explicit retry policy
- [ ] Add timeouts
- [ ] Map definitive errors
- [ ] Map ambiguous errors
- [ ] Add tests

## Phase 6 — Payment Idempotency

- [ ] Implement deterministic hash
- [ ] Same key + same request replay
- [ ] Same key + different request `409`
- [ ] DB unique constraint
- [ ] Concurrent key race
- [ ] Persist Stripe idempotency key before Stripe call
- [ ] Add tests

## Phase 7 — Stripe Gateway

- [ ] Create `PaymentGateway`
- [ ] Create `StripePaymentGateway`
- [ ] Configure Stripe SDK
- [ ] Create checkout command/result DTOs
- [ ] Create amount converter
- [ ] Define metadata
- [ ] Configure success/cancel URLs
- [ ] Add stable Stripe idempotency key
- [ ] Create Checkout Session
- [ ] Add tests

## Phase 8 — Initiate Payment

- [ ] Create PaymentController
- [ ] CUSTOMER auth
- [ ] Fetch Booking context
- [ ] Validate payable state
- [ ] Create Payment/Attempt claim
- [ ] Commit before Stripe call
- [ ] Create Stripe Session
- [ ] Finalize Attempt -> `OPEN`
- [ ] Return checkout URL
- [ ] Handle ambiguous creation
- [ ] Manual happy path

## Phase 9 — Stripe Webhook

- [ ] Create webhook endpoint
- [ ] Permit webhook path
- [ ] Preserve raw body
- [ ] Verify signature
- [ ] Create StripeWebhookEvent
- [ ] Add unique event ID
- [ ] Deduplicate
- [ ] Handle `checkout.session.completed`
- [ ] Handle `checkout.session.expired`
- [ ] Persist processing status
- [ ] Add tests

## Phase 10 — Booking Confirmation

- [ ] Attempt -> `SUCCEEDED`
- [ ] Payment -> `SUCCEEDED`
- [ ] BookingConfirmationStatus -> `PENDING`
- [ ] Commit before Booking call
- [ ] Confirm Booking
- [ ] Persist confirmation result
- [ ] Keep financial truth if Booking down
- [ ] Test duplicate confirm
- [ ] Test lost response
- [ ] Test Scheduler race

## Phase 11 — Technical Refund

- [ ] Create Refund entity
- [ ] Refund reason enum
- [ ] Refund statuses
- [ ] Stable Stripe refund idempotency key
- [ ] Stripe refund call
- [ ] Trigger when Booking definitively cannot confirm
- [ ] Handle ambiguous refund
- [ ] Add reconciliation
- [ ] Add tests

## Phase 12 — Reconciliation

- [ ] Reconciliation service
- [ ] Scheduled job
- [ ] Recover stale `INITIALIZING`
- [ ] Recover `UNKNOWN`
- [ ] Retry idempotent Booking confirmation
- [ ] Reconcile refund `UNKNOWN`
- [ ] Retry failed webhook processing safely
- [ ] Bounded batches
- [ ] Critical logs
- [ ] Add tests

## Phase 13 — Stripe CLI / Sandbox

- [ ] Login Stripe CLI
- [ ] Start webhook forwarding
- [ ] Configure local webhook secret
- [ ] Successful card test
- [ ] Decline test
- [ ] 3DS test
- [ ] Duplicate webhook test
- [ ] Checkout expiry test
- [ ] Booking Service down test
- [ ] Late payment/refund test
- [ ] Capture evidence

## Phase 14 — Hardening

- [ ] Full Booking regression
- [ ] Full Payment tests
- [ ] Repository/concurrency tests
- [ ] Stripe sandbox tests
- [ ] No secrets in Git
- [ ] No sensitive logs
- [ ] No card data persistence
- [ ] Review DB constraints
- [ ] Review idempotency
- [ ] Review transaction boundaries
- [ ] Review failure matrix
- [ ] `mvn clean test` passes

## Phase 15 — Documentation & Closure

- [ ] Update this plan
- [ ] Create Sprint 5 complete reference
- [ ] Update architecture
- [ ] Update roadmap
- [ ] Document Stripe test cases
- [ ] Document limitations
- [ ] Add interview Q&A
- [ ] Mark Sprint 5 CLOSED

---

# 30. Current Decisions

| Decision | Sprint 5 Choice |
|---|---|
| Provider | Stripe only |
| UI | Stripe-hosted Checkout |
| API style | Checkout Sessions |
| Card data | Never handled by backend |
| DB | PostgreSQL `payment_db` |
| Amount source | Booking authoritative snapshot |
| Money type | `BigDecimal` |
| Booking state | Add `CONFIRMED` only |
| Internal communication | OpenFeign + SERVICE JWT |
| Stripe communication | Stripe Java SDK behind `PaymentGateway` |
| Client idempotency | Required |
| Stripe idempotency | Required |
| Webhook | Required |
| Browser redirect | UX only |
| Webhook dedupe | Unique Stripe event ID |
| Remote call inside DB TX | Forbidden |
| Late payment | Technical refund |
| Payment success + Booking down | Keep `SUCCEEDED`, reconcile |
| Reconciliation | Required |
| Kafka / Outbox | Not in Sprint 5 |
| Local webhook | Stripe CLI |
| First test currency | USD |

---

# 31. Open Questions

**LOCKED (no longer open):**
- [x] `Booking.totalAmount` formula: `priceAtBooking × passengerCount` — confirmed per-passenger from FareClassMapper
- [x] Checkout expiry policy: Stripe Session TTL = 30 min (minimum). Not synchronized with Booking TTL. Late payment → CAS + refund.
- [x] `clientIdempotencyKey` / `requestHash` removed from `PaymentAttempt` → belong to `PaymentIdempotencyRecord`
- [x] `BookingConfirmationStatus.FAILED` → replaced by `REJECTED`
- [x] `PaymentStatus.REFUND_PENDING` → removed; refund lifecycle in `Refund.status`

**Still open:**
- [ ] Exact success URL (frontend routing)
- [ ] Exact cancel URL (frontend routing)
- [ ] Exact `UNIQUE` constraints on Payment/Attempt/IdempotencyRecord
- [ ] Store full webhook payload or only audit metadata/hash?
- [ ] Exact Stripe SDK timeout/retry configuration
- [ ] Reconciliation job interval and batch size
- [ ] JOD currency support after USD flow is stable
- [ ] Proactive Stripe Session expiration in reconciliation (optional hardening)

---

# 32. Sprint Success Criteria

Sprint 5 closes only when:

```text
1. CUSTOMER can create PENDING Booking.
2. CUSTOMER can initiate Stripe payment.
3. Amount comes only from Booking.
4. Stripe-hosted Checkout opens.
5. Test payment succeeds.
6. Verified webhook processes once.
7. Payment becomes SUCCEEDED.
8. Booking becomes CONFIRMED.
9. Duplicate payment requests are safe.
10. Duplicate webhooks are safe.
11. Booking confirmation is idempotent.
12. Payment success + Booking outage is recoverable.
13. Late payment against EXPIRED Booking triggers refund.
14. Reconciliation resolves recoverable stuck states.
15. Sprint 4 regression still passes.
16. New Payment automated baseline passes.
17. No payment secrets/card data leak.
18. Documentation matches final implementation.
```

---

# 33. Current Status

```text
Sprint 5 — EXECUTING

Provider:
Stripe only

Implementation:
Phase 0, 1 & 2 — CLOSED

Next:
Phase 3 — Payment Service Setup
```

---

# 34. Change Log

| Date | Change | Reason |
|---|---|---|
| 2026-09-03 | Sprint 5 narrowed to Stripe only | Learn and implement one provider deeply before PayPal/MEPS |
| 2026-09-03 | Stripe-hosted Checkout selected | Production-like hosted card flow with lower backend card-handling complexity |
| 2026-09-03 | Booking gets `CONFIRMED` without Stripe-specific states | Keep payment lifecycle in Payment bounded context |
| 2026-09-03 | Technical refund included | Required for payment-success / booking-failure inconsistency |
| 2026-09-03 | Reconciliation included | Required for ambiguous distributed outcomes |
| 2026-09-03 | Stripe CLI selected for local webhook testing | Direct local webhook forwarding |
| 2026-09-07 | Removed `clientIdempotencyKey` + `requestHash` from `PaymentAttempt` | These belong to `PaymentIdempotencyRecord`; one Attempt can be reached by multiple API keys |
| 2026-09-07 | Removed `PaymentStatus.REFUND_PENDING` | Refund lifecycle in `Refund.status`; Payment stays `SUCCEEDED` until refund confirmed |
| 2026-09-07 | Replaced `BookingConfirmationStatus.FAILED` with `REJECTED` | `REJECTED` = definitive (triggers refund); `PENDING` = ambiguous/retryable |
| 2026-09-07 | Locked `totalAmount = priceAtBooking × passengerCount` | Confirmed `FareClass.price` is per-passenger unit price from FareClassMapper |
| 2026-09-07 | Locked Stripe Session TTL = 30 min, not synchronized with Booking TTL | Stripe minimum is 30 min; synchronization would let provider dictate inventory policy |
| 2026-09-07 | Created `SPRINT5_FAILURE_MATRIX.md` as Phase Gate | Phase 3 (Payment Service Setup) blocked until Matrix is CLOSED |

---

**Sprint 5 — Stripe Payment Integration Living Execution Plan**
