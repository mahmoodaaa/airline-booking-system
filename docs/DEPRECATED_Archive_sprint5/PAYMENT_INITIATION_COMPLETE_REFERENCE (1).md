# Payment Service — Payment Initiation Complete Reference

> **Project:** Airline Booking System  
> **Sprint:** 5 — Payment Service / Stripe Integration  
> **Scope:** Payment initiation flow before implementing the real `StripePaymentGateway`  
> **Status:** CANONICAL REFERENCE  
> **Last updated:** 2026-09-15

---

# 1. Purpose

This is the canonical reference for the **payment initiation side** of the Payment Service.

It explains:

- what `PaymentServiceImpl` does
- how idempotency works
- how `Payment` and `PaymentAttempt` differ
- how concurrency is controlled
- why `COMMIT BEFORE NETWORK` is mandatory
- how existing attempts are replayed
- how provider outcomes are classified
- how double-charge prevention works before Stripe
- what the Stripe adapter must implement next

This document intentionally stops before webhook processing, refund execution, and reconciliation jobs.

---

# 2. Architecture Overview

```text
Controller
    |
    | userId from JWT
    | raw Idempotency-Key
    | PaymentInitiationRequest
    v
PaymentServiceImpl
    |
    | application orchestrator
    | NO class-level @Transactional
    |
    +--> PaymentRequestHashService
    |      hashing only
    |
    +--> BookingClient
    |      authoritative Booking context
    |
    +--> PaymentTransactionService
    |      short REQUIRES_NEW transactions
    |      claimIdempotency()            COMMIT #1
    |      getOrCreatePayment()          COMMIT #2
    |      claimAttemptForInitiation()   COMMIT #3
    |      markAttemptOpen()             COMMIT #4a
    |      markAttemptUnknown()          COMMIT #4b
    |      markAttemptFailed()           COMMIT #4c
    |
    +--> PaymentGatewayResolver
    |      provider -> gateway bean
    |
    +--> PaymentGateway
           provider-neutral contract
              |
              v
      StripePaymentGateway
           Stripe-specific adapter
```

## Golden Rule

```text
COMMIT BEFORE NETWORK
```

No DB transaction should remain open while calling Stripe or another service.

---

# 3. Layer Responsibilities

| Layer | Responsibility | Must NOT own |
|---|---|---|
| `PaymentServiceImpl` | Business orchestration | Repositories, Stripe SDK, long transactions |
| `PaymentTransactionService` | DB locks, CAS, atomic state changes | HTTP/provider calls |
| `PaymentGateway` | Provider-neutral contract | Stripe SDK, DB |
| `StripePaymentGateway` | Stripe SDK + request/response translation | Payment DB mutations |
| `BookingClient` | Payment -> Booking boundary | Payment orchestration |
| `PaymentMapper` | Entity -> API response | State transitions |

Mental model:

```text
PaymentServiceImpl
= WHAT should happen

PaymentTransactionService
= HOW local state is persisted safely

PaymentGateway
= WHAT a provider must support

StripePaymentGateway
= HOW Stripe specifically performs it
```

---

# 4. Main Use Case

```java
PaymentInitiationResponse initiatePayment(
        UUID userId,
        String idempotencyKey,
        PaymentInitiationRequest request
)
```

Client-controlled fields:

```text
bookingId
provider
paymentMethod
```

Not client-controlled:

```text
userId
amount
currency
Payment status
providerIdempotencyKey
success/cancel URLs
```

---

# 5. PaymentServiceImpl — Full Flow

## Step 1 — Validate input

Validate:

```text
userId != null
request != null
Idempotency-Key present
Idempotency-Key not blank
Idempotency-Key length <= 100
bookingId present
provider present
paymentMethod present
```

No DB write and no Stripe call yet.

## Step 2 — Generate two hashes

### Idempotency-Key hash

```text
raw Idempotency-Key
        |
      SHA-256
        |
idempotencyKeyHash
```

### Request hash

Built from:

```text
bookingId + provider + paymentMethod
```

Rules:

```text
same key + same requestHash
-> replay

same key + different requestHash
-> 409 Conflict
```

## Step 3 — Resolve gateway

```text
STRIPE
  |
  v
PaymentGatewayResolver
  |
  v
StripePaymentGateway
```

This only resolves the adapter. It does not call Stripe.

## Step 4 — Fetch authoritative Booking context

```text
PaymentServiceImpl
  |
  v
BookingClient
  |
  v
Booking Service
```

Booking returns:

```text
bookingId
userId
status
totalAmount
currency
expiresAt
```

Booking is the source of truth for `amount` and `currency`.

## Step 5 — Validate Booking context

Checks:

```text
bookingId matches request
booking belongs to authenticated user
status == PENDING
expiresAt > now
totalAmount > 0
currency exists
```

If any check fails, payment initiation stops.

---

# 6. Step 6 — Claim Client Idempotency

```java
claimIdempotency(
    userId,
    idempotencyKeyHash,
    requestHash,
    bookingId
)
```

Runs in `REQUIRES_NEW` and commits before the next phase.

Invariant:

```text
same user + same Idempotency-Key
cannot represent two different logical requests
```

---

# 7. Step 7 — Get or Create Logical Payment

```java
getOrCreatePayment(
    bookingId,
    userId,
    amount,
    currency
)
```

Invariant:

```text
1 Booking -> 1 logical Payment
```

The Payment stores the authoritative financial snapshot:

```text
bookingId
userId
amount
currency
```

First creation uses:

```text
INSERT
+ UNIQUE(booking_id)
+ reload winner
```

because `SELECT FOR UPDATE` cannot lock a row that does not exist yet.

---

# 8. Payment vs PaymentAttempt

## Payment

Represents the logical financial lifecycle of one Booking.

```text
Booking B1
   |
   v
Payment P1
```

## PaymentAttempt

Represents one concrete provider attempt.

```text
Payment P1
   |
   +-- Attempt A1 -> STRIPE / CARD / EXPIRED
   |
   +-- Attempt A2 -> STRIPE / CARD / SUCCEEDED
```

A Payment may have multiple attempts over time, but unresolved attempts must never compete blindly.

---

# 9. Step 8 — claimAttemptForInitiation()

This is the **financial concurrency gate**.

It runs in:

```text
@Transactional(REQUIRES_NEW)
```

and locks the Payment row:

```text
SELECT ... FOR UPDATE
```

---

# 10. claimAttemptForInitiation() Decision Tree

```text
findByIdForUpdate(paymentId)
        |
        v
Payment lock acquired
        |
        v
load IdempotencyRecord
        |
        v
record.paymentId != null
AND != paymentId?
        |
        +-- YES -> IllegalStateException
        |
        v
Payment.status == SUCCEEDED or REFUNDED?
        |
        +-- YES
        |    |
        |    +-- succeededAttemptId == null
        |    |      -> IllegalStateException
        |    |
        |    +-- record.attemptId == succeededAttemptId
        |           -> canonical replay
        |           -> return existing Attempt
        |
        |    otherwise
        |           -> 409 Conflict
        |
        v
record.attemptId != null?
        |
        +-- YES -> immutable replay
        |          return mapped Attempt
        |
        v
Payment.status != PENDING?
        |
        +-- YES -> 409 Conflict
        |
        v
find active Attempt:
INITIALIZING | OPEN | UNKNOWN
        |
        +-- found, different provider/method
        |      -> 409 Conflict
        |
        +-- found, same provider/method
        |      -> bind key
        |      -> return existing Attempt
        |
        v
no active Attempt
        |
        v
create INITIALIZING Attempt
persist providerIdempotencyKey
bind IdempotencyRecord
COMMIT
return createdNewAttempt=true
```

---

# 11. Why the Terminal Payment Guard Matters

Possible state:

```text
Payment = SUCCEEDED
Booking = still PENDING
```

This can happen if payment succeeds but Booking confirmation times out.

Without the Payment-level guard:

```text
new Idempotency-Key
-> Booking still says PENDING
-> no active INITIALIZING/OPEN/UNKNOWN attempt
-> previous attempt is SUCCEEDED
-> create second attempt
-> risk second charge
```

Therefore:

```text
Payment.status == SUCCEEDED or REFUNDED
-> NEVER create a new provider attempt
```

Only a pre-existing canonical replay is allowed.

---

# 12. Canonical Successful Replay

Example:

```text
K1 --      > A1 -> SUCCEEDED
K2 --/

Payment.succeededAttemptId = A1
```

Then:

```text
K1 replay -> allowed
K2 replay -> allowed
K3 new key -> 409
```

An old key mapped to a failed attempt also must not create a new attempt after the Payment is already successful.

---

# 13. Default-Deny Guard

Creation rule:

```text
ONLY PaymentStatus.PENDING
may create a new Attempt
```

Not:

```text
anything except SUCCEEDED/REFUNDED
```

This protects future statuses such as:

```text
DISPUTED
REFUND_PENDING
MANUAL_REVIEW
```

---

# 14. Active Attempt Gate

Active unresolved statuses:

```text
INITIALIZING
OPEN
UNKNOWN
```

If one exists:

```text
same provider + same method
-> reuse it

different provider/method
-> 409 Conflict
```

This prevents competing payment channels.

---

# 15. Provider Idempotency Key

Every new Attempt gets one stable:

```text
providerIdempotencyKey
```

It is generated once and persisted before the provider call.

Never generate a new provider key for the same Attempt.

Purpose:

```text
same logical provider operation
+
same provider idempotency key
=
provider-side deduplication
```

---

# 16. COMMIT BEFORE NETWORK

Wrong:

```text
BEGIN TX
create Attempt
call Stripe
update Attempt
COMMIT
```

Correct:

```text
TX #1
persist INITIALIZING Attempt
persist providerIdempotencyKey
COMMIT

Stripe call

TX #2
persist provider result
COMMIT
```

At provider-call time, local intent is already durable.

---

# 17. Existing Attempt Handling in PaymentServiceImpl

If:

```text
createdNewAttempt == false
```

the provider must NOT be called blindly.

| Status | Behavior |
|---|---|
| `OPEN` | return same redirect URL |
| `INITIALIZING` | 409 processing |
| `UNKNOWN` | 503 reconciliation required |
| `FAILED` | 409 |
| `EXPIRED` | 409 |
| `SUCCEEDED` | idempotent replay |

Only:

```text
createdNewAttempt == true
```

authorizes a new provider creation call.

---

# 18. Provider-Neutral CheckoutRequest

```java
public record CheckoutRequest(
    UUID paymentId,
    UUID attemptId,
    UUID bookingId,
    BigDecimal amount,
    String currency,
    PaymentMethodType paymentMethod,
    String providerIdempotencyKey
) {}
```

Not included:

```text
Stripe SDK types
Stripe Session ID
successUrl
cancelUrl
userId
```

Redirect URLs belong in Stripe configuration.

---

# 19. PaymentGateway Three-Outcome Contract

```java
CheckoutResult createCheckout(CheckoutRequest request);
```

## Success

```text
returns CheckoutResult
INITIALIZING -> OPEN
```

## Definitive failure

```text
GatewayDefinitiveException
INITIALIZING -> FAILED
```

Meaning: provider clearly rejected the create operation.

## Ambiguous outcome

```text
GatewayAmbiguousException
INITIALIZING -> UNKNOWN
```

Examples:

```text
timeout
connection reset
response lost
uncertain transport failure
```

Important:

```text
timeout != FAILED
timeout != EXPIRED
```

---

# 20. Provider Success + Local DB Failure

Flow:

```text
Stripe creates Session successfully
        |
        v
CheckoutResult returned
        |
        v
markAttemptOpen() fails locally
```

Provider truth is known:

```text
Session EXISTS
```

Therefore:

```text
DO NOT mark UNKNOWN
DO NOT create another Attempt
```

The local Attempt may remain:

```text
INITIALIZING
```

and becomes a reconciliation candidate.

---

# 21. Outcome Matrix

| Scenario | Provider truth | Local state | Action |
|---|---|---|---|
| successful checkout | known success | `OPEN` | return URL |
| definitive provider rejection | known rejection | `FAILED` | stop |
| provider timeout | unknown | `UNKNOWN` | reconcile |
| provider success + DB finalization failure | known success | stale `INITIALIZING` | reconcile |
| replay of `OPEN` | known existing session | `OPEN` | same URL |
| replay of `UNKNOWN` | unknown | `UNKNOWN` | 503 |

---

# 22. CheckoutResult

```java
public record CheckoutResult(
    String providerCheckoutId,
    String providerPaymentId,
    String redirectUrl,
    LocalDateTime expiresAt
) {}
```

For Stripe:

```text
providerCheckoutId = cs_...
providerPaymentId = pi_... (may be null initially)
redirectUrl = hosted Checkout URL
expiresAt = Stripe session expiry
```

---

# 23. BookingPaymentContext

Fields:

```text
bookingId
userId
totalAmount
currency
status
expiresAt
```

Validation:

| Field | Rule |
|---|---|
| `bookingId` | must match request |
| `userId` | must match authenticated user |
| `status` | must be `PENDING` |
| `totalAmount` | must be > 0 |
| `currency` | required |
| `expiresAt` | must be in future if present |

---

# 24. What PaymentServiceImpl Must NOT Do

```text
direct repository access                     NO
direct Stripe SDK usage                      NO
long @Transactional orchestration method     NO
trust frontend amount                        NO
trust frontend currency                      NO
blind retry after UNKNOWN                    NO
call provider for existing Attempt           NO
new provider key for same Attempt            NO
mark known provider success as UNKNOWN       NO
```

---

# 25. Financial Safety Invariants

```text
1 Booking -> 1 logical Payment

same Idempotency-Key -> immutable mapping

same key + different request -> conflict

ONLY PENDING may create a new Attempt

SUCCEEDED -> no new provider Attempt

REFUNDED -> no new provider Attempt

canonical successful mapping -> replay only

at most one unresolved active Attempt

providerIdempotencyKey is durable before network

provider call happens after COMMIT

UNKNOWN means genuinely unknown provider outcome

known provider success is never downgraded to UNKNOWN
```

---

# 26. Unit Test Baseline

```text
TC-01 PENDING + no active Attempt
-> create INITIALIZING
-> createdNewAttempt=true

TC-02 mapped Attempt
-> return same Attempt

TC-03 mapped Attempt different provider/method
-> ConflictException

TC-04 SUCCEEDED + canonical mapped key
-> replay canonical Attempt

TC-05 SUCCEEDED + new key
-> ConflictException

TC-06 SUCCEEDED + succeededAttemptId null
-> IllegalStateException

TC-07a REFUNDED + canonical mapped key
-> replay only

TC-07b REFUNDED + new/non-canonical key
-> ConflictException

TC-08 unexpected non-PENDING status
-> ConflictException

TC-09 INITIALIZING same provider/method
-> reuse

TC-10 OPEN same provider/method
-> reuse

TC-11 UNKNOWN same provider/method
-> reuse

TC-12 active Attempt different provider/method
-> ConflictException

TC-13 IdempotencyRecord paymentId mismatch
-> IllegalStateException
```

---

# 27. StripePaymentGateway — Next Phase

Flow:

```text
CheckoutRequest
    |
    v
Stripe SessionCreateParams
    |
    v
Stripe API
    |
    v
Stripe Session
    |
    v
CheckoutResult
```

Responsibilities:

```text
Stripe SDK usage
Stripe request building
Stripe response mapping
Stripe exception translation
Stripe metadata
Stripe redirect configuration
Stripe provider idempotency
```

It must never mutate Payment DB entities directly.

---

# 28. Intended Stripe Exception Mapping

| Stripe exception | Gateway outcome |
|---|---|
| `CardException` | definitive |
| `InvalidRequestException` | definitive |
| `AuthenticationException` | definitive |
| `PermissionException` | definitive |
| `IdempotencyException` | definitive |
| `RateLimitException` | definitive |
| `ApiConnectionException` | ambiguous |
| `ApiException` with uncertain outcome | ambiguous |
| unexpected/unclassified exception | conservative ambiguous |

Core rule:

```text
clear provider rejection
-> DEFINITIVE

uncertain provider side effect
-> AMBIGUOUS
```

---

# 29. Stripe Redirect URLs

Redirect URLs are server configuration, not client input.

Conceptually:

```properties
stripe.success-url=http://localhost:5173/payment/success?session_id={CHECKOUT_SESSION_ID}
stripe.cancel-url=http://localhost:5173/payment/cancel
```

Important:

```text
browser redirect != payment truth
```

Payment truth later comes from:

```text
verified Stripe webhook
or
verified Stripe API reconciliation
```

---

# 30. Multi-Currency Direction

The architecture is multi-currency capable.

Booking provides:

```text
amount + currency
```

Stripe-specific minor-unit conversion belongs in:

```text
StripeAmountConverter
```

Examples:

```text
USD 150.00 -> 15000
EUR  99.50 ->  9950
AED 120.75 -> 12075
JPY    500 ->   500
```

No FX conversion belongs in `PaymentServiceImpl`.

---

# 31. Current Completion State

```text
Payment domain model                  DONE
Payment repositories                  DONE
Payment idempotency                   DONE
PaymentTransactionService             DONE
terminal financial guard              DONE
PaymentServiceImpl                    DONE
PaymentMapper                         DONE
BookingClient                         DONE
PaymentGateway                        DONE
PaymentGatewayResolver                DONE
Gateway exception model               DONE
unit tests for attempt gate           DONE

StripeProperties                      NEXT
StripeClient configuration            NEXT
StripeAmountConverter                 NEXT
StripePaymentGateway.createCheckout   NEXT
Stripe adapter tests                  NEXT
```

---

# 32. Final Mental Model

```text
Customer
   |
   v
Controller
   |
   v
PaymentServiceImpl
   |
   +-- validate
   +-- fetch Booking truth
   +-- claim client idempotency
   +-- get/create logical Payment
   +-- claim/reuse PaymentAttempt
   |
   +-- COMMIT
   |
   v
PaymentGateway
   |
   v
StripePaymentGateway
   |
   v
Stripe
```

Provider result:

```text
SUCCESS
-> OPEN

DEFINITIVE FAILURE
-> FAILED

AMBIGUOUS OUTCOME
-> UNKNOWN
```

Most important safety rule:

```text
NO PROVIDER CALL

until the PaymentAttempt intent
and providerIdempotencyKey
exist durably in the database.
```

---

# 33. One-Sentence Summary

`PaymentServiceImpl` safely coordinates Booking truth, client idempotency, logical Payment ownership, concurrency-safe PaymentAttempt creation, and provider invocation while ensuring that double-charge prevention and durable local intent exist before any external payment network call.
