# Sprint 5 — Final Canonical Reference

> **Project:** Airline Booking System  
> **Sprint:** 5 — Booking + Payment + Stripe  
> **Status:** CANONICAL / FINAL AS-BUILT REFERENCE  
> **Date:** 2026-10-07  
> **Rule:** This file supersedes all older Sprint 5 payment/booking design documents for current implementation decisions.

---

## 1. Final Scope

```text
Create Booking
    ↓
PENDING
    ↓
Proceed to Payment
    ↓
PAYMENT_PENDING
    ↓
Stripe Checkout
    ↓
verified Stripe webhook
    ↓
Payment SUCCEEDED
    ↓
Booking CONFIRMED
```

Minimal compensation:

```text
Stripe SUCCEEDED
+
Booking definitively REJECTED
    ↓
FULL Stripe refund
```

Out of current scope:

```text
customer refund policy
partial refunds
admin refund API
generic Refund domain/entity
PayPal
Kafka / Outbox
generic reconciliation engine
```

---

## 2. Ownership

### Booking Service owns

```text
Booking lifecycle
Passenger data
Seat reservation lifecycle
Payable snapshot
totalAmount / currency
reservation deadline
expiry / seat release
Booking confirmation
```

### Payment Service owns

```text
Payment lifecycle
PaymentAttempt lifecycle
client API idempotency
provider idempotency
Stripe Checkout creation
verified webhook processing
webhook inbox/deduplication
financial truth
Booking confirmation orchestration
minimal booking-rejection refund
```

### Flight Service owns

```text
flight inventory
fare classes
seat reserve/release
```

Correct direction:

```text
Payment -> Booking -> Flight
```

---

## 3. Time Policy

All backend/system/financial timestamps are UTC.

Current project convention:

```java
LocalDateTime.now(ZoneOffset.UTC)
```

Used for:

```text
createdAt
updatedAt
expiresAt
succeededAt
resolvedAt
bookingConfirmedAt
refundedAt
webhook timestamps
idempotency timestamps
```

Native inserts receive UTC values from Java instead of PostgreSQL `CURRENT_TIMESTAMP`.

The current project still uses:

```text
LocalDateTime + timestamp without time zone
```

So every stored `LocalDateTime` is interpreted by convention as UTC.

Future improvement only:

```text
Instant / OffsetDateTime + timestamptz
```

---

## 4. Booking State Machine

Relevant states:

```text
IN_PROGRESS
PENDING
PAYMENT_PENDING
FAILED
CANCELLING
CANCELLED
EXPIRING
EXPIRED
CONFIRMED
COMPENSATION_FAILED
```

Main flow:

```text
                PENDING
                /     \
               /       \
      Proceed to Pay    expiresAt reached
             ↓                 ↓
      PAYMENT_PENDING       EXPIRING
          /      \               ↓
         /        \           EXPIRED
payment success   expiresAt reached
       ↓                 ↓
   CONFIRMED          EXPIRING
                         ↓
                      EXPIRED
```

`PAYMENT_PENDING` is required.

---

## 5. One Booking Deadline Only

There is exactly one operational reservation deadline:

```text
Booking.expiresAt
```

There is no:

```text
paymentHoldUntil
second payment timer
timer/job per booking
```

While `PENDING`:

```properties
booking.ttl-minutes=30
```

First valid `startPayment`:

```text
PENDING -> PAYMENT_PENDING
expiresAt = now + 45 minutes
```

Current config:

```properties
booking.payment-window-minutes=45
```

Replay while already valid `PAYMENT_PENDING` returns the same context and never extends `expiresAt`.

---

## 6. Booking `start-payment`

Internal call:

```http
POST /internal/bookings/{bookingId}/start-payment
```

Body:

```json
{
  "userId": "..."
}
```

Response:

```text
bookingId
userId
status = PAYMENT_PENDING
totalAmount
currency
expiresAt
```

Payment Service does not choose the Booking payment-window duration.

Conceptual CAS:

```sql
UPDATE bookings
SET
    status = 'PAYMENT_PENDING',
    expires_at = :newDeadline
WHERE id = :bookingId
  AND user_id = :userId
  AND status = 'PENDING'
  AND expires_at > :now;
```

If a concurrent valid call already moved the row to `PAYMENT_PENDING`, reload and return the existing payment window.

---

## 7. Booking Expiry

One unified expiry path handles:

```text
PENDING
PAYMENT_PENDING
```

Due rule:

```text
status IN ('PENDING', 'PAYMENT_PENDING')
AND expires_at <= now
```

Processing:

```text
active state
   ↓
CAS -> EXPIRING
   ↓
Flight.releaseSeats()
   ↓
EXPIRING -> EXPIRED
```

Ambiguous seat release leaves Booking in `EXPIRING`.

Recommended partial index:

```sql
CREATE INDEX IF NOT EXISTS idx_booking_due
ON bookings (expires_at)
WHERE status IN ('PENDING', 'PAYMENT_PENDING');
```

No per-booking timers.

---

## 8. Booking Confirmation

After verified financial success:

```text
PAYMENT_PENDING -> CONFIRMED
```

with:

```text
paymentId = Payment.id
confirmedAt = UTC now
```

Conceptual CAS:

```sql
UPDATE bookings
SET
    status = 'CONFIRMED',
    payment_id = :paymentId,
    confirmed_at = :now
WHERE id = :bookingId
  AND status = 'PAYMENT_PENDING'
  AND expires_at > :now;
```

Replay with the same `paymentId` is idempotent.

Never force:

```text
EXPIRED -> CONFIRMED
```

---

## 9. Payment Domain

One logical Payment per Booking:

```text
Booking B1 -> Payment P1
```

DB invariant:

```text
UNIQUE(booking_id)
```

Core financial truth:

```text
status = PENDING | SUCCEEDED
succeededAttemptId
succeededAt
bookingConfirmationStatus
bookingConfirmationLastError
bookingConfirmedAt
refundStatus
providerRefundId
refundedAt
refundLastError
version
```

Payment remains `SUCCEEDED` even when a later compensation refund succeeds.

### BookingConfirmationStatus

```text
NOT_STARTED
PENDING
CONFIRMED
REJECTED
```

---

## 10. PaymentAttempt

A Payment may have multiple sequential attempts:

```text
Payment P1
   ├── A1 FAILED
   ├── A2 EXPIRED
   └── A3 SUCCEEDED
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

Active/unresolved:

```text
INITIALIZING
OPEN
UNKNOWN
```

Invariant:

```text
at most one active/unresolved attempt per Payment
```

Recommended PostgreSQL enforcement:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_one_active_attempt
ON payment_attempts (payment_id)
WHERE status IN ('INITIALIZING', 'OPEN', 'UNKNOWN');
```

---

## 11. Client Idempotency

External endpoint:

```http
POST /api/payments
Idempotency-Key: ...
```

Persist:

```text
userId
idempotencyKeyHash
requestHash
bookingId
paymentId
attemptId
```

Rules:

```text
same key + same request -> immutable replay
same key + different request -> 409 Conflict
```

Final ordering:

```text
validate request
normalize key
hash key/request
resolve gateway
claim/check client idempotency
BookingClient.startPayment(...)
validate PAYMENT_PENDING context
get/create Payment
claim/reuse/create PaymentAttempt
COMMIT
call Stripe outside DB transaction
finalize Attempt
```

---

## 12. Attempt Replay and Retry

Previously used client key always maps to the same attempt.

```text
K1 -> A1 FAILED
replay K1 -> A1 FAILED
```

It does not silently create A2.

If an active attempt exists:

```text
same provider/method -> reuse
different provider/method -> 409
```

`OPEN` returns the same Payment, Attempt and redirect URL.

`UNKNOWN` blocks new attempts.

`FAILED` or `EXPIRED` may be followed by a fresh attempt only with a **new client Idempotency-Key** and enough remaining Booking time.

---

## 13. Stripe Timing

Current values:

```text
Booking PAYMENT_PENDING window = 45 minutes
Stripe Checkout window         = 40 minutes
Safety margin                  = 1 minute
```

Fresh-attempt boundary:

```text
latestCheckoutStartAt
    = booking.expiresAt - 41 minutes
```

Reject when:

```java
!now.isBefore(latestCheckoutStartAt)
```

Existing `OPEN` Checkout can still be reused near Booking expiry while:

```text
providerExpiresAt > now
```

Observed local timing:

```text
Payment start      ≈ 16:36 UTC
Stripe expiry      ≈ 17:16 UTC
Booking expiry     ≈ 17:21 UTC
```

---

## 14. COMMIT BEFORE NETWORK

Provider idempotency key is stored before Stripe is called.

Wrong:

```text
BEGIN TX
save
call Stripe
save
COMMIT
```

Correct:

```text
TX #1
persist INITIALIZING
persist provider idempotency key
COMMIT

Stripe network call

TX #2
persist OPEN / FAILED / UNKNOWN
COMMIT
```

---

## 15. Stripe Checkout Outcomes

Success:

```text
INITIALIZING -> OPEN
```

Definitive provider failure:

```text
INITIALIZING -> FAILED
```

Ambiguous provider outcome:

```text
INITIALIZING -> UNKNOWN
```

Timeout is not treated as provider failure.

---

## 16. Webhook Processing

Endpoint:

```http
POST /api/webhooks/stripe
```

Trust boundary:

```text
raw request body
+
Stripe-Signature
+
webhook signing secret
```

Current events:

```text
checkout.session.completed
checkout.session.expired
```

Durable inbox statuses:

```text
RECEIVED
PROCESSING
PROCESSED
FAILED
```

Invariant:

```text
UNIQUE(stripe_event_id)
```

---

## 17. Successful Payment Flow

Verified `checkout.session.completed` validates:

```text
mode
payment_status
metadata
PaymentIntent
amount
currency
local Payment/Attempt mapping
```

Financial TX:

```text
Attempt -> SUCCEEDED
Payment -> SUCCEEDED
Payment.succeededAttemptId = canonical Attempt
Payment.succeededAt = UTC now
BookingConfirmationStatus -> PENDING
COMMIT
```

Then Booking confirmation occurs outside that DB transaction.

On success:

```text
Booking -> CONFIRMED
BookingConfirmationStatus -> CONFIRMED
Webhook -> PROCESSED
```

---

## 18. Ambiguous vs Definitive Booking Confirmation

Ambiguous examples:

```text
timeout
connection error
Booking 5xx
unexpected infrastructure error
```

Persist:

```text
Payment = SUCCEEDED
BookingConfirmationStatus = PENDING
lastError = ...
```

Then rethrow so webhook processing fails and Stripe can retry.

Do not refund on ambiguity.

Definitive rejection:

```text
Payment = SUCCEEDED
BookingConfirmationStatus = REJECTED
```

Then invoke the minimal booking-rejection refund.

---

## 19. Minimal Technical Refund

Automatic trigger only:

```text
Payment.status = SUCCEEDED
AND
BookingConfirmationStatus = REJECTED
```

Refund fields live on `Payment`:

```text
refundStatus
providerRefundId
refundedAt
refundLastError
```

RefundStatus:

```text
NOT_STARTED
PENDING
SUCCEEDED
UNKNOWN
FAILED
```

Payment remains:

```text
SUCCEEDED
```

Small orchestrator:

```text
BookingRejectedRefundService
```

Flow:

```text
claim locally -> PENDING
COMMIT
Stripe full refund
success -> SUCCEEDED
definitive failure -> FAILED
ambiguous -> UNKNOWN + rethrow
```

Stable provider idempotency key:

```text
booking-rejected-refund:{paymentId}
```

There is no generic Refund entity/repository/domain in the current design.

---

## 20. Duplicate Financial Success

Canonical pointer:

```text
Payment.succeededAttemptId
```

If another distinct attempt later reports real provider success:

```text
preserve provider truth
do not overwrite canonical attempt
do not reconfirm Booking
CRITICAL log
manual/operational intervention
```

The minimal automatic refund is not used for this anomaly.

---

## 21. Checkout Expiry

Verified:

```text
checkout.session.expired
```

causes:

```text
Attempt OPEN -> EXPIRED
```

Payment remains `PENDING`.

Booking remains owned by Booking Service.

A new client key may create a fresh sequential attempt only if the fresh 40-minute Checkout plus safety still fits before Booking `expiresAt`.

---

## 22. Security

```text
Customer -> Payment: CUSTOMER JWT
Payment -> Booking:  SERVICE JWT
Stripe -> Payment:   Stripe signature
```

Never log/commit:

```text
Stripe secret key
webhook secret
Authorization header
full card data
CVC
```

---

## 23. Important DB Checks

Verify in PostgreSQL:

```text
Payment.booking_id UNIQUE
PaymentIdempotencyRecord(user_id, idempotency_key_hash) UNIQUE
StripeWebhookEvent.stripe_event_id UNIQUE
Booking status CHECK includes PAYMENT_PENDING
```

Recommended partial indexes:

```sql
CREATE INDEX IF NOT EXISTS idx_booking_due
ON bookings (expires_at)
WHERE status IN ('PENDING', 'PAYMENT_PENDING');
```

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_one_active_attempt
ON payment_attempts (payment_id)
WHERE status IN ('INITIALIZING', 'OPEN', 'UNKNOWN');
```

Do not rely on `ddl-auto=update` to remove old columns or repair historical constraints.

---

## 24. Current Test Evidence

Automated:

```text
BUILD SUCCESS
0 failures
0 errors
```

Latest reported baseline:

```text
65 tests
1 skipped
```

UTC refactor also completed with `BUILD SUCCESS`.

Manual Stripe E2E already proved:

```text
Booking PENDING
-> PAYMENT_PENDING
-> Stripe Checkout
-> same-key replay returns same Payment/Attempt/URL
-> test card succeeds
-> checkout.session.completed
-> webhook 200
-> Attempt SUCCEEDED
-> Payment SUCCEEDED
-> Booking CONFIRMED
```

UTC storage was then validated on new Booking / Payment / PaymentAttempt rows.

Final recommended smoke:

```text
Pay one Checkout created after UTC refactor
verify succeededAt / resolvedAt / confirmedAt are UTC
```

---

## 25. Minor Cleanup, Not Blockers

API wrapper:

```text
HTTP 201 Created
body.status = 200 OK
```

Align later.

`DataIntegrityViolationException` handling must not label every DB integrity error as an idempotency conflict. Only the real idempotency unique constraint should receive that message.

---

## 26. Deferred

Generic scheduled reconciliation is deferred.

Future candidates:

```text
stale INITIALIZING
UNKNOWN Attempt
Payment SUCCEEDED + BookingConfirmation PENDING
WebhookEvent FAILED not naturally retried
Refund UNKNOWN not resolved by replay
Booking EXPIRING
```

Do not redesign Payment core to add these.

---

## 27. Final Mental Model

```text
Booking decides WHAT must be paid.
Payment records WHETHER money moved.
PaymentAttempt records HOW a provider attempt progressed.
Stripe is provider financial truth.
Webhook imports verified provider truth.
Booking CAS decides confirmation vs expiry.
Refund compensates one definitive post-payment Booking failure.
```

Safety:

```text
client idempotency
+
provider idempotency
+
webhook deduplication
+
Payment row locking
+
Booking CAS
+
COMMIT BEFORE NETWORK
+
UTC timestamps
```

---

## 28. Sprint Status

```text
Booking core                  DONE
Payment core                  DONE
Stripe Checkout               DONE
Webhook                       DONE
Booking confirmation          DONE
Sequential attempts           DONE
Single Booking deadline       DONE
Minimal booking-reject refund DONE
UTC consistency refactor      DONE
Automated tests               PASS
Real Stripe happy path        PASS

Generic reconciliation        DEFERRED
Kafka / Outbox                FUTURE
```

This is the canonical Sprint 5 reference going forward.
