# Sprint 5 — Distributed Failure Matrix

> **Status:** FINALIZED — Phase 4 Domain Model locked  
> **Rule:** Must be CLOSED before creating any Payment entity or DB schema.  
> **Purpose:** Every scenario must have a known, deterministic outcome. If a row is unclear, it means the state machine is incomplete.

---

## Legend

| Column | Meaning |
|---|---|
| **Payment DB** | State of Payment/Attempt after this scenario |
| **Stripe** | What Stripe actually did |
| **Booking** | State of Booking after this scenario |
| **Response** | What caller receives |
| **Retry?** | Can/should the same operation be retried? |
| **Refund?** | Is a compensation refund required? |
| **Reconcile?** | Does reconciliation job need to act? |

Attempt status short-codes: `INIT` = INITIALIZING, `UNK` = UNKNOWN

---

## Group A — Payment Initiation Failures (before Stripe call)

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| A1 | Missing/blank `Idempotency-Key` header | none | none | none | PENDING | 400 | client fixes header | no | no |
| A2 | `Idempotency-Key` > 100 chars | none | none | none | PENDING | 400 | client fixes header | no | no |
| A3 | `bookingId` not found | none | none | none | — | 404 | client fixes bookingId | no | no |
| A4 | Booking belongs to different user | none | none | none | PENDING | 403 | no | no | no |
| A5 | Booking status = CANCELLED | none | none | none | CANCELLED | 409 / definitive rejection | no | no | no |
| A6 | Booking status = EXPIRED | none | none | none | EXPIRED | 409 / definitive rejection | no | no | no |
| A7 | Booking status = CONFIRMED | none | none | none | CONFIRMED | 409 / already paid | no | no | no |
| A8 | Booking status = IN_PROGRESS | none | none | none | IN_PROGRESS | 409 | no | no | no |
| A9 | Booking has zero `totalAmount` | none | none | none | PENDING | 500 / CRITICAL log | no | no | manual |
| A10 | Booking Service DOWN (get payment context) | none | none | none | unknown | 503 | client retries full request | no | no |
| A11 | Same `Idempotency-Key` + same `bookingId` → existing `OPEN` Attempt | PENDING | OPEN (reuse) | session exists | PENDING | 200 — same checkoutUrl | idempotent replay | no | no |
| A12 | Same key + different `bookingId` | existing record | existing | — | — | 409 IdempotencyConflict | no | no | no |
| A13 | Same key + same booking → Attempt `INITIALIZING` (concurrent) | PENDING | INITIALIZING | none yet | PENDING | 409 processing | client waits/retries with SAME key | no | no |
| A14 | Same key + same booking → Attempt `UNKNOWN` | PENDING | UNKNOWN | unknown | PENDING | 503 reconciliation required | no until resolved | no | yes |
| A15 | Two different keys, same booking, concurrent (race) | PENDING | one INITIALIZING wins | none | PENDING | winner 200, loser 409 processing | loser waits, retries with SAME key, gets mapped to OPEN attempt | no | no |
| A16 | Payment DB claim insert fails (DB error) | none | none | none | PENDING | 500 | full retry safe | no | no |

---

## Group B — Stripe Session Creation Failures

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| B1 | Stripe rejects definitively (invalid currency, bad config) | PENDING | INITIALIZING → FAILED (terminal) | no session | PENDING | 500 / CRITICAL | no blind retry — fix config, client can initiate new attempt | no | no |
| B2 | Stripe timeout / connection reset (outcome unknown) | PENDING | INITIALIZING → UNKNOWN | unknown | PENDING | 503 | same Stripe idem key only when safe | no | yes |
| B3 | Stripe creates session, TX #2 (INITIALIZING→OPEN) fails | PENDING | INITIALIZING (stale) | session exists | PENDING | 503 | reconciliation retries INITIALIZING→OPEN | no | yes |
| B4 | Stripe creates session, local crash between TX #1 and TX #2 | PENDING | INITIALIZING (stale) | session exists | PENDING | — (no response) | reconciliation resolves | no | yes |
| B5 | Stripe creates session, TX #2 succeeds → normal OPEN | PENDING | OPEN | session exists | PENDING | 200 — checkoutUrl | idempotent replay safe | no | no |

---

## Group C — Customer on Stripe Checkout (no backend involvement)

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| C1 | Customer enters card — Stripe declines | PENDING | OPEN (unchanged) | declined, session still OPEN | PENDING | — (Stripe UI shows decline) | customer retries within same session | no | no |
| C2 | Customer closes browser without paying | PENDING | OPEN (unchanged) | session still OPEN | PENDING | — | customer can reopen same checkoutUrl | no | no |
| C3 | Customer waits → Stripe Session expires (30 min) | PENDING | OPEN → EXPIRED (via webhook) | session expired | PENDING | — (webhook-driven) | customer must initiate new attempt | no | no |
| C4 | Booking expires (TTL) while Checkout Session still OPEN | PENDING | OPEN (unchanged) | session OPEN | EXPIRED (scheduler) | — | late payment scenario (see Group E) | no | no |

---

## Group D — Successful Payment & Booking Confirmation

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| D1 | Happy path: webhook received, all succeeds | SUCCEEDED | SUCCEEDED | paid | CONFIRMED | webhook: 200 | no | no | no |
| D2 | Webhook received, TX #1 (mark SUCCEEDED) fails | PENDING | OPEN | paid | PENDING | webhook: 500 (Stripe retries) | Stripe retries webhook; dedup allows retry if not PROCESSED | no | yes — dedup on retry |
| D3 | Webhook received, TX #1 succeeds, Booking confirmation call times out | SUCCEEDED | SUCCEEDED | paid | unknown | webhook: 200 (financial truth persisted) | reconciliation retries confirm | no | yes |
| D4 | Webhook received, TX #1 succeeds, Booking Service DOWN | SUCCEEDED | SUCCEEDED | paid | unknown | webhook: 200 | reconciliation retries confirm | no | yes |
| D5 | Webhook received, TX #2 (BookingConfirmation→CONFIRMED) fails | SUCCEEDED | SUCCEEDED | paid | CONFIRMED (already confirmed) | webhook: 200 | no | no | **YES** — job retries idempotent confirmPayment, fixes local status |
| D6 | Duplicate webhook (same `stripeEventId`) | unchanged | unchanged | paid | already CONFIRMED | webhook: 200 | if PROCESSED → ignore; if RECEIVED/FAILED → process safely | no | no |
| D7 | Concurrent duplicate webhooks (race) | one wins with UNIQUE constraint | one winner | paid | CONFIRMED once | one 200, one dedup (if PROCESSING → block) | no | no | no |
| D8 | Booking confirmation — same `bookingId + paymentId` retry | unchanged | unchanged | paid | CONFIRMED | 200 idempotent | safe to retry | no | no |
| D9 | Booking confirm — Booking returns 404 (deleted?) | SUCCEEDED | SUCCEEDED | paid | MISSING | 200 (money persisted) — CRITICAL log | no | yes — refund if definitive | yes |
| D10 | **Sequential:** second distinct attempt reports success after another attempt is already canonical success | SUCCEEDED (canonical unchanged) | incoming attempt → SUCCEEDED (provider truth preserved) | two charges | CONFIRMED (already) | webhook: 200 (money truth persisted) | no | **YES — refund non-canonical attempt** | yes — CRITICAL log + reconciliation until refund resolved |
| D11 | **Concurrent:** two distinct attempts report success simultaneously | SUCCEEDED (one winner via CAS) | winner → canonical; loser → SUCCEEDED (preserved) | two charges | CONFIRMED once | both webhooks: 200 | no | **YES — refund losing attempt** | yes — CRITICAL log |
| D12 | **Stale-state correction:** previously locally terminal/unresolved attempt later has verified provider success, while another attempt is already canonical | SUCCEEDED (canonical unchanged) | stale attempt corrected to SUCCEEDED after Stripe verification | two charges | CONFIRMED (already) | webhook: 200 | no — verify Stripe first; do NOT correct local state from webhook alone | **YES — refund non-canonical attempt** | yes — CRITICAL log + reconciliation |

---

## Group E — Late Payment (Booking EXPIRED before confirmation)

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| E1 | Stripe SUCCEEDED, Booking EXPIRED → confirm rejected | SUCCEEDED, BookingConf=REJECTED | SUCCEEDED | paid | EXPIRED | webhook: 200 (money persisted) | no confirm retry | **YES — technical refund** | refund outcome |
| E2 | Technical refund call to Stripe succeeds | REFUNDED | SUCCEEDED | refunded | EXPIRED | — | no | — | no |
| E3 | Technical refund call times out (outcome unknown) | SUCCEEDED (Refund=UNKNOWN) | SUCCEEDED | unknown | EXPIRED | — | same Stripe refund idem key only | — | yes — check refund |
| E4 | Technical refund — Stripe says already refunded (idem key match) | REFUNDED | SUCCEEDED | refunded | EXPIRED | — | no | — | no |
| E5 | Technical refund — Stripe definitive failure (e.g., amount mismatch) | SUCCEEDED (Refund=FAILED) | SUCCEEDED | not refunded | EXPIRED | CRITICAL log | no blind retry | manual | yes — escalate |

---

## Group F — Checkout Session Expiry Webhook

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| F1 | `checkout.session.expired` webhook received, Attempt was OPEN | PENDING | OPEN → EXPIRED | expired | PENDING (scheduler owns expiry) | webhook: 200 | new payment attempt allowed | no | no |
| F2 | `checkout.session.expired` webhook, Attempt already SUCCEEDED | SUCCEEDED | SUCCEEDED | paid (not really expired) | CONFIRMED | webhook: 200 (idempotent ignore) | no | no | no |
| F3 | `checkout.session.expired` webhook — duplicate | unchanged | EXPIRED (already) | expired | PENDING | webhook: 200 | no | no | no |

---

## Group G — Security & Integrity Violations

| # | Scenario | Payment DB | Attempt | Stripe | Booking | Response | Retry? | Refund? | Reconcile? |
|---|---|---|---|---|---|---|---|---|---|
| G1 | Webhook arrives with invalid Stripe signature | none | none | — | — | 400 — rejected immediately | no | no | no |
| G2 | Customer JWT missing on `POST /api/payments` | none | none | none | — | 401 | client fixes auth | no | no |
| G3 | Customer JWT valid but booking belongs to another user | none | none | none | — | 403 | no | no | no |
| G4 | Payment-service SERVICE JWT missing/invalid on internal Booking calls | — | — | — | — | 401 from Booking — CRITICAL | fix JWT config | no | yes — retry if ambiguous |

---

## Group H — Reconciliation Candidates

| Candidate State | Trigger Condition | Reconciliation Action |
|---|---|---|
| Attempt `INITIALIZING` too long (> threshold) | createdAt + N minutes old | Reissue SAME POST create-session with SAME params + SAME Stripe idem key. If recovered → save `sessionId` & mark OPEN; else FAILED/delete. Must do fast before Stripe 24h key retention ends. |
| Attempt `UNKNOWN` | created, Stripe outcome unknown | If `sessionId` exists → retrieve by ID. If no `sessionId` → reissue SAME POST with SAME idem key. Resolve to OPEN/SUCCEEDED/EXPIRED/FAILED. |
| Payment `SUCCEEDED` + `BookingConfirmationStatus = PENDING` | age > threshold | Retry idempotent Booking confirm |
| Payment `SUCCEEDED` + `BookingConfirmationStatus = REJECTED` | no refund started | Start technical refund |
| `Refund.status = UNKNOWN` | age > threshold | Query Stripe refund by idem key (or reissue); resolve to SUCCEEDED/FAILED |
| `StripeWebhookEvent.processingStatus = FAILED` | processing error | Retry processing (idempotent) |

---

## Decisions Extracted from This Matrix

### ① `BookingConfirmationStatus` final values

From the matrix: `NOT_STARTED`, `PENDING`, `CONFIRMED`, `REJECTED`

**Remove `FAILED`** — replaced by `REJECTED` (Booking definitively refused) + `PENDING` (transient/ambiguous).

```
NOT_STARTED  = payment not yet attempted
PENDING      = confirmation in progress / outcome unknown / retryable
CONFIRMED    = Booking confirmed (CAS won)
REJECTED     = Booking definitively refused (EXPIRED, CANCELLED)
              → triggers technical refund
```

### ② `PaymentStatus` final values

```
PENDING    = payment initiated, outcome pending
SUCCEEDED  = Stripe confirmed payment
REFUNDED   = technical refund completed
```

**Remove `REFUND_PENDING`** — refund lifecycle lives in `Refund.status`.

### ③ `RefundStatus`

```
PENDING    = refund claim created, Stripe call not yet made
SUCCEEDED  = Stripe confirmed refund
UNKNOWN    = Stripe outcome uncertain — reconciliation required
FAILED     = Stripe definitively failed — manual escalation
```

### ④ `PaymentAttempt` fields — confirmed clean version

```
id
paymentId
status                   ← INITIALIZING / OPEN / SUCCEEDED / EXPIRED / UNKNOWN / FAILED
stripeIdempotencyKey     ← persisted BEFORE Stripe call
stripeCheckoutSessionId
stripePaymentIntentId
checkoutUrl
stripeExpiresAt
createdAt
updatedAt
version
```

**Not included:**
- `clientIdempotencyKey` ❌ — belongs to `PaymentIdempotencyRecord`
- `requestHash` ❌ — belongs to `PaymentIdempotencyRecord`

**CRITICAL enum semantics (enforced in code via Javadoc):**
- `UNKNOWN` = local network timeout / response lost. Provider truth unresolved. Must be reconciled via Stripe API lookup. **NEVER used for Stripe-confirmed session expiry.**
- `EXPIRED` = Stripe confirmed the Checkout Session expired (via webhook or reconciliation). This is a Stripe business state, not a local timeout label.

### ⑤-b `Payment.succeededAttemptId` — LOCKED (added Phase 4)

```
Payment answers two distinct questions:

  status = SUCCEEDED
  → "Did this logical Payment succeed?"

  succeededAttemptId = <UUID>
  → "Which provider attempt is the accepted canonical success?"
```

**Invariants:**
- Assigned exactly once via atomic CAS (`claimFirstSuccessfulAttempt`) with `WHERE status = 'PENDING' AND succeeded_attempt_id IS NULL`.
- Never overwritten, even in abnormal cases (D10/D11/D12).
- No DB FK → avoids circular PaymentAttempt ↔ Payment JPA lifecycle complexity. Integrity enforced by service transaction + CAS + tests.
- `refundedAt` removed from `Payment` in Phase 4. Will be re-evaluated in Phase 11 alongside `Refund` entity (may be redundant given `Refund.succeededAt`).

**D10/D11/D12 CAS flow:**
```
claimFirstSuccessfulAttempt(paymentId, incomingAttemptId)
  updatedRows == 1  →  this attempt is canonical winner ✅
  updatedRows == 0  →  reload Payment:
    payment.succeededAttemptId == incomingAttemptId  →  idempotent replay ✅
    payment.succeededAttemptId != incomingAttemptId  →  DUPLICATE FINANCIAL SUCCESS
                                                        persist incoming as SUCCEEDED
                                                        do NOT replace canonical
                                                        do NOT re-confirm Booking
                                                        create Refund for incoming attempt
                                                        CRITICAL log
```

### ⑤ `totalAmount` formula — LOCKED

```
FareClass.price = per-seat unit price (confirmed from FareClassMapper.toSeatReservationResponse)
SeatReservationResult.price = FareClass.price (no multiplication in flight-service)
priceAtBooking = SeatReservationResult.price = per-passenger unit price

totalAmount = priceAtBooking × passengerCount
```

Stored as `BigDecimal totalAmount` field on `Booking` entity.  
Calculated once at `PENDING` finalization. Never recomputed.

### ⑥ Stripe Session TTL — LOCKED

```
Stripe Session TTL = 30 minutes (minimum allowed by Stripe)
Booking TTL = unchanged (authoritative for inventory)

NOT synchronized — Stripe cannot accept < 30 min.
Late payment window = possible by design → handled via CAS + technical refund.

Optional hardening: reconciliation may proactively expire Stripe Session
when bookingExpiresAt < now AND Attempt is OPEN.
This reduces the window but is NOT the safety mechanism.
Safety = CAS + refund.
```

### ⑦ Webhook Event Statuses — LOCKED

```
RECEIVED   = Webhook saved to inbox, not processed yet
PROCESSING = Currently being processed (blocks concurrent duplicates)
PROCESSED  = Successfully processed (duplicates return 200 immediately)
FAILED     = Processing failed, safe for retry
```

---

## Matrix Status

- [x] Group A — Payment Initiation Failures
- [x] Group B — Stripe Session Creation Failures
- [x] Group C — Customer on Stripe Checkout
- [x] Group D — Successful Payment & Booking Confirmation (D10/D11/D12 added — duplicate financial success)
- [x] Group E — Late Payment
- [x] Group F — Checkout Session Expiry
- [x] Group G — Security & Integrity Violations
- [x] Group H — Reconciliation Candidates
- [x] Decisions extracted
- [x] `Payment.succeededAttemptId` added — canonical success pointer (Phase 4)
- [x] `Payment.refundedAt` removed — deferred to Phase 11 Refund entity
- [x] `UNKNOWN` vs `EXPIRED` semantics locked in enum Javadoc

**Matrix Status: CLOSED ✅ — Phase 4 Domain Model finalized.**
