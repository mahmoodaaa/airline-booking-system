# Sprint 5 — Final Failure Matrix

> **Project:** Airline Booking System  
> **Sprint:** 5 — Booking + Payment + Stripe  
> **Status:** CANONICAL / FINAL  
> **Date:** 2026-10-07  
> **Rule:** This matrix reflects the final single-`expiresAt`, `PAYMENT_PENDING`, sequential-attempt, minimal-refund design.

---

## 1. Status Legend

### Payment

```text
PENDING
SUCCEEDED
```

### PaymentAttempt

```text
INITIALIZING
OPEN
SUCCEEDED
FAILED
EXPIRED
UNKNOWN
```

### BookingConfirmationStatus

```text
NOT_STARTED
PENDING
CONFIRMED
REJECTED
```

### RefundStatus

```text
NOT_STARTED
PENDING
SUCCEEDED
UNKNOWN
FAILED
```

---

## 2. Booking / Start-Payment

| # | Scenario | Booking | Payment | Action |
|---|---|---|---|---|
| A1 | Missing/invalid customer auth | unchanged | none | 401/403 |
| A2 | Booking not found | — | none | definitive rejection |
| A3 | Wrong owner | unchanged | none | 403 |
| A4 | Booking `EXPIRED` / `CANCELLED` | terminal | none/new attempt blocked | definitive rejection |
| A5 | `PENDING` but `expiresAt <= now` | due/expiry wins | none | reject start-payment |
| A6 | First valid start-payment | `PENDING -> PAYMENT_PENDING`, replace `expiresAt` once | continue | success |
| A7 | Repeated start-payment while valid | stays `PAYMENT_PENDING`, same `expiresAt` | continue | idempotent replay |
| A8 | Concurrent start-payment | one CAS wins, loser reloads same state | continue | converge safely |
| A9 | Booking status CHECK missing `PAYMENT_PENDING` | DB rejects update | none | schema bug |
| A10 | Booking call ambiguous/down before Payment creation | unknown | none | retry full idempotent request |

Invariant:

```text
Booking must be PAYMENT_PENDING before Stripe Checkout creation.
```

---

## 3. Client Idempotency

| # | Scenario | Result |
|---|---|---|
| B1 | Missing/blank key | 400 |
| B2 | Same key + same request | immutable replay |
| B3 | Same key + different request | 409 |
| B4 | Concurrent same key | one claim wins; other safely observes/conflicts while processing |
| B5 | Old key mapped to `FAILED`/`EXPIRED` | replay same terminal attempt |
| B6 | New key after `FAILED`/`EXPIRED` | may create new attempt only if time guard passes |

---

## 4. Active Attempt Gate

| # | Existing state | New request | Result |
|---|---|---|---|
| C1 | `INITIALIZING`, same provider/method | valid key | reuse/processing; no new Stripe call |
| C2 | `OPEN`, same provider/method | valid key | same Attempt + same redirect URL |
| C3 | `UNKNOWN` | any key | block new attempt |
| C4 | active attempt with different provider/method | new request | 409 |
| C5 | `FAILED`, no active attempt | new key | fresh attempt only if time guard passes |
| C6 | `EXPIRED`, no active attempt | new key | fresh attempt only if time guard passes |
| C7 | Payment already `SUCCEEDED` | new key | reject new provider attempt |

Invariant:

```text
at most one INITIALIZING / OPEN / UNKNOWN per Payment
```

---

## 5. Fresh Attempt Time Guard

Current timing:

```text
Booking PAYMENT_PENDING = 45m
Stripe Checkout         = 40m
Safety margin           = 1m
```

Fresh attempt allowed only when:

```text
now < booking.expiresAt - 41m
```

| # | Scenario | Result |
|---|---|---|
| D1 | Enough time remains | create new `INITIALIZING` |
| D2 | Exactly at latest start boundary | reject |
| D3 | Too little time remains | reject; no Stripe call |
| D4 | Existing `OPEN` session still provider-valid | return same URL even near Booking expiry |

---

## 6. Stripe Checkout Creation

| # | Provider outcome | Attempt | Result |
|---|---|---|---|
| E1 | Checkout created | `OPEN` | return URL |
| E2 | clear provider rejection | `FAILED` | definitive error |
| E3 | timeout / uncertain transport | `UNKNOWN` | no blind new attempt |
| E4 | Stripe creates session but local OPEN finalization fails | stale `INITIALIZING` | future reconciliation candidate |
| E5 | replay of `OPEN` | unchanged | same URL; no new Stripe call |

Golden rule:

```text
provider idempotency key exists durably before network
```

---

## 7. Customer on Stripe

| # | Scenario | Attempt | Booking | Action |
|---|---|---|---|---|
| F1 | Card decline inside hosted Checkout | `OPEN` | `PAYMENT_PENDING` | retry card inside same session |
| F2 | Customer closes browser | `OPEN` | `PAYMENT_PENDING` | reopen same URL |
| F3 | Checkout expires | `EXPIRED` via webhook | `PAYMENT_PENDING` | Payment does not release seats |
| F4 | Booking deadline later expires | terminal attempt unchanged | `PAYMENT_PENDING -> EXPIRING -> EXPIRED` | Booking releases seats |
| F5 | New attempt after `EXPIRED` | old `EXPIRED`; maybe new attempt | Booking must still be valid | time guard decides |

---

## 8. Completed Checkout / Financial Success

Before state mutation validate:

```text
Stripe signature
mode = payment
payment_status = paid
metadata paymentId / attemptId / bookingId
client reference
PaymentIntent
amount
currency
local Payment/Attempt mapping
```

Then:

```text
Attempt -> SUCCEEDED
Payment -> SUCCEEDED
Payment.succeededAttemptId = canonical Attempt
BookingConfirmationStatus -> PENDING
COMMIT
```

---

## 9. Booking Confirmation

| # | Booking outcome | Payment truth | BookingConfirmationStatus | Webhook behavior |
|---|---|---|---|---|
| G1 | success | `SUCCEEDED` | `CONFIRMED` | 200 / PROCESSED |
| G2 | idempotent replay same paymentId | `SUCCEEDED` | `CONFIRMED` | safe success |
| G3 | timeout / connection / 5xx | `SUCCEEDED` | `PENDING` + error | rethrow so Stripe can retry |
| G4 | local persist after remote success fails | `SUCCEEDED` | may remain `PENDING` | retry idempotent confirmation |
| G5 | Booking definitively rejects | `SUCCEEDED` | `REJECTED` | start minimal refund |
| G6 | expiry CAS won first | `SUCCEEDED` | `REJECTED` | full technical refund |
| G7 | Booking confirmed with different paymentId | `SUCCEEDED` | abnormal | CRITICAL/manual |

Never:

```text
mark Payment FAILED after verified Stripe success
```

---

## 10. Confirmation vs Expiry Race

```text
Webhook:
PAYMENT_PENDING -> CONFIRMED

Scheduler:
PAYMENT_PENDING -> EXPIRING
```

Exactly one conditional atomic transition wins.

If confirmation wins:

```text
Booking = CONFIRMED
no seat release
```

If expiry wins:

```text
Booking -> EXPIRED
later financial success cannot force confirmation
minimal refund required
```

---

## 11. Webhook Inbox

| # | Scenario | Result |
|---|---|
| H1 | Invalid Stripe signature | reject; no financial mutation |
| H2 | First valid event | persist inbox row and process |
| H3 | Same event replay after PROCESSED | 200; no duplicate side effect |
| H4 | Concurrent duplicate | one logical processing winner |
| H5 | Processing throws | inbox `FAILED`; webhook non-2xx |
| H6 | Replayed FAILED event | safe to claim/process again |

Invariant:

```text
UNIQUE(stripe_event_id)
```

---

## 12. Checkout Expiry Webhook

| # | Scenario | Result |
|---|---|
| I1 | `OPEN` receives `checkout.session.expired` | `OPEN -> EXPIRED` |
| I2 | duplicate expired webhook | idempotent |
| I3 | Attempt already `SUCCEEDED` | never downgrade |
| I4 | Booking still valid `PAYMENT_PENDING` | leave Booking to Booking Service |
| I5 | user requests fresh payment with new key | allowed only if fresh 40m + safety fit |

---

## 13. Minimal Booking-Rejection Refund

Trigger only:

```text
Payment = SUCCEEDED
BookingConfirmationStatus = REJECTED
```

Flow:

```text
NOT_STARTED
   ↓ claim
PENDING
   ↓ COMMIT
Stripe full refund
```

| # | Refund outcome | Local state | Action |
|---|---|---|---|
| J1 | success | `SUCCEEDED` + providerRefundId + refundedAt | done |
| J2 | definitive failure | `FAILED` + lastError | manual intervention |
| J3 | timeout / uncertain response | `UNKNOWN` + lastError | rethrow/retry same logical refund |
| J4 | retry after ambiguity | same stable provider idempotency key | provider deduplicates |
| J5 | already `SUCCEEDED` | no-op | no second refund |

Payment remains:

```text
SUCCEEDED
```

No generic Refund entity.

---

## 14. Duplicate Financial Success

Scenario:

```text
A1 = canonical SUCCEEDED
A2 later reports real provider success
```

Current policy:

```text
preserve provider truth
do not replace succeededAttemptId
do not reconfirm Booking
CRITICAL log
manual/operational intervention
```

The minimal automatic booking-rejection refund does not automatically handle this anomaly.

---

## 15. Booking Expiry Worker

Due query:

```text
status IN (PENDING, PAYMENT_PENDING)
AND expiresAt <= now
```

Per row:

```text
active -> EXPIRING
release seats
EXPIRING -> EXPIRED
```

| # | Scenario | Result |
|---|---|---|
| K1 | CAS loses | another transition won; skip |
| K2 | seat release succeeds | `EXPIRED` |
| K3 | seat release ambiguous/fails | remain `EXPIRING` |
| K4 | multiple workers read same row | one CAS winner |
| K5 | large backlog | bounded repeated batches |

No timer per Booking.

---

## 16. UTC Failures

Rule:

```text
all internal backend financial/system timestamps are UTC
```

Never mix:

```text
JVM local +03
PostgreSQL local CURRENT_TIMESTAMP
UTC business timestamps
```

Current implementation passes UTC from Java for native inserts and uses UTC lifecycle callbacks.

A row like:

```text
createdAt = local +03
succeededAt = UTC
```

is a bug.

---

## 17. Schema Risks

Verify:

```text
bookings.payment_id type = uuid
bookings_status_check includes PAYMENT_PENDING
refund_status initialized
obsolete payment_hold_until removed
```

Recommended partial indexes:

```text
idx_booking_due
uk_payment_one_active_attempt
```

Do not assume `ddl-auto=update` repairs old constraints or removes obsolete schema.

---

## 18. Non-Blocking Cleanup

HTTP wrapper mismatch:

```text
HTTP 201 Created
body.status = 200 OK
```

Fix later.

Do not map every `DataIntegrityViolationException` to an idempotency message. Only the actual idempotency unique constraint should use that response.

---

## 19. Future Reconciliation Candidates

Generic scheduled reconciliation remains deferred.

Candidates:

```text
stale INITIALIZING Attempt
UNKNOWN Attempt
Payment SUCCEEDED + BookingConfirmation PENDING
WebhookEvent FAILED not naturally recovered
Refund UNKNOWN not resolved by replay
Booking EXPIRING
```

Rule:

```text
inspect durable state
perform only idempotent action
never guess remote truth
```

---

## 20. Final Safety Invariants

```text
1 Booking -> 1 logical Payment

same client key -> immutable mapping

same key + different request -> conflict

one active unresolved Attempt at a time

FAILED/EXPIRED may get a new sequential attempt
only with a new key and enough remaining Booking time

OPEN is reused

UNKNOWN blocks new attempts

Payment SUCCEEDED blocks new attempts

provider idempotency key is durable before network

Stripe financial truth commits before Booking confirmation

Booking confirmation requires PAYMENT_PENDING + unexpired expiresAt

confirmation and expiry are competing CAS transitions

Payment never releases Flight seats

definitive post-payment Booking rejection -> minimal full refund

ambiguous Booking result -> no refund

all internal system/financial timestamps -> UTC
```

This matrix supersedes the older Sprint 5 failure matrix.
