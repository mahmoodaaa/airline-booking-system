# Airline Booking System — Final Payment Hold & Booking Expiry Design

> **Status:** APPROVED / FINAL REFERENCE  
> **Scope:** Booking expiry + `PAYMENT_PENDING` + Stripe Checkout coordination  
> **Purpose:** Keep the payment flow safe, simple, scalable, and easy to maintain without creating a timer/job per booking.  
> **Supersedes:** Any older design that uses `paymentHoldUntil` as a second operational deadline, or allows normal `PENDING -> CONFIRMED` after entering Stripe.

---

# 1. Final Decision

The system will use:

```text
ONE Booking row
ONE effective expiration deadline: expiresAt
ONE Booking expiry worker/sweeper
NO timer per booking
NO second payment-hold timer
NO queue for expiry at the current scale
```

`PAYMENT_PENDING` remains an important Booking state.

`paymentHoldUntil` is removed.

The meaning of `expiresAt` becomes:

> **The time when the current reservation lease ends, based on the Booking's current state.**

---

# 2. Final Booking State Machine

```text
                         create booking
                              |
                              v
                           PENDING
                              |
              +---------------+----------------+
              |                                |
              | Proceed to Payment             | expiresAt reached
              v                                v
       PAYMENT_PENDING                      EXPIRING
              |                                |
              |                                v
        +-----+------+                       EXPIRED
        |            |
        | payment    | expiresAt reached
        | succeeds   |
        v            v
    CONFIRMED     EXPIRING
                     |
                     v
                  EXPIRED
```

Other existing states remain as required by the project:

```text
IN_PROGRESS
FAILED
CANCELLING
CANCELLED
COMPENSATION_FAILED
...
```

---

# 3. Meaning of `expiresAt`

`expiresAt` is no longer interpreted only as the original `PENDING` TTL.

It is the **current effective reservation deadline**.

## While `status == PENDING`

```text
expiresAt = normal booking reservation deadline
```

Example:

```text
Booking created: 12:00
status:          PENDING
expiresAt:       12:30
```

If the customer does nothing:

```text
12:30+
PENDING -> EXPIRING -> EXPIRED
```

## While `status == PAYMENT_PENDING`

When the customer legitimately starts payment, the same `expiresAt` column is atomically replaced with the protected payment-window deadline.

Example:

```text
Booking created:       12:00
Original expiresAt:    12:30
Proceed to Payment:    12:29

Atomic transition:
PENDING -> PAYMENT_PENDING

New expiresAt:
13:14
```

The Booking is now protected until `13:14`.

There is no second operational timestamp such as:

```text
paymentHoldUntil
```

---

# 4. Recommended Time Windows

Initial recommended configuration:

```properties
booking.ttl-minutes=30
booking.payment-window-minutes=45
stripe.checkout-expiry-minutes=35
```

These values are configurable.

The important invariant is:

```text
Stripe Checkout expiry
<
Booking expiresAt while PAYMENT_PENDING
```

Example:

```text
12:29  Proceed to Payment

Booking PAYMENT_PENDING:
expiresAt = 13:14

Stripe Checkout:
expires around 13:04

Approximate margin:
10 minutes
```

The margin exists for:

```text
provider processing
webhook delivery
Payment Service DB work
Booking confirmation
normal network latency
```

---

# 5. Start Payment — Final Business Flow

When the customer clicks:

```text
Proceed to Payment
```

the frontend calls:

```http
POST /api/payments
```

The final ordering is:

```text
Customer
   |
   v
POST /api/payments
   |
   v
Validate CUSTOMER JWT
   |
   v
Validate request
   |
   v
Validate Idempotency-Key
   |
   v
Calculate requestHash
   |
   v
Claim/check client idempotency
   |
   v
BookingClient.startPayment(bookingId, userId)
   |
   v
Atomic Booking transition
PENDING -> PAYMENT_PENDING
expiresAt = now + paymentWindow
   |
   v
Booking returns authoritative payment context
   |
   v
Create/reuse Payment
   |
   v
Create/reuse PaymentAttempt
Attempt = INITIALIZING
   |
   v
COMMIT
   |
   v
NO DB TRANSACTION
   |
   v
Stripe Checkout creation
   |
   v
Attempt = OPEN
   |
   v
Return redirectUrl
```

Critical invariant:

```text
Booking MUST be protected as PAYMENT_PENDING
before Stripe Checkout is created.
```

---

# 6. Atomic `startPayment`

The Booking Service owns the payment-window duration.

Payment Service must NOT send:

```text
holdMinutes
paymentWindowMinutes
expiresAt
paymentHoldUntil
```

Recommended internal contract:

```http
POST /internal/bookings/{bookingId}/start-payment
Authorization: Bearer <SERVICE JWT>
```

Body:

```json
{
  "userId": "..."
}
```

Conceptual atomic update:

```sql
UPDATE bookings
SET
    status = 'PAYMENT_PENDING',
    expires_at = :newDeadline,
    version = version + 1
WHERE id = :bookingId
  AND user_id = :userId
  AND status = 'PENDING'
  AND expires_at > :now;
```

If one row is updated:

```text
startPayment succeeded
```

If zero rows are updated, reload the Booking and determine the reason.

---

# 7. Idempotent `startPayment`

If the Booking is already:

```text
status = PAYMENT_PENDING
expiresAt > now
```

return the current payment context.

Do NOT extend `expiresAt`.

Example:

```text
12:29 first Pay
expiresAt = 13:14

12:32 second Pay
expiresAt remains 13:14

12:45 third Pay
expiresAt remains 13:14
```

Never:

```text
12:32 -> now + 45 min
12:45 -> now + 45 min
```

Otherwise the customer could keep inventory locked indefinitely.

---

# 8. Concurrent `startPayment` Requests

Example:

```text
Thread A                         Thread B
--------                         --------
reads PENDING                    reads PENDING

CAS succeeds:
PENDING -> PAYMENT_PENDING

                                 CAS returns 0
```

Thread B must reload the Booking.

If it now sees:

```text
status = PAYMENT_PENDING
expiresAt > now
same user
```

then this is an idempotent concurrent replay.

Return the existing payment context.

Do not return an unnecessary conflict.

---

# 9. Booking Payment Context

The authoritative response from Booking Service should contain only what Payment Service needs.

Example:

```json
{
  "bookingId": "...",
  "userId": "...",
  "status": "PAYMENT_PENDING",
  "totalAmount": 150.00,
  "currency": "USD",
  "expiresAt": "2026-09-30T12:30:00"
}
```

Remove:

```text
paymentHoldUntil
```

Payment Service must validate:

```text
status == PAYMENT_PENDING
expiresAt > now
amount valid
currency valid
booking belongs to expected user
```

---

# 10. Stripe Checkout Rule

Final simplification:

> **One protected payment window = one Stripe Checkout attempt/session.**

During one `PAYMENT_PENDING` window:

```text
OPEN
    -> reuse same redirectUrl

INITIALIZING
    -> payment initiation is still processing

SUCCEEDED
    -> already financially successful

UNKNOWN
    -> reconciliation required

EXPIRED
    -> do NOT create another Stripe Checkout inside the same Booking window
```

This avoids unnecessary complexity around:

```text
second Checkout
remaining minutes
extending Booking again
multiple payment windows
multiple provider sessions
```

---

# 11. Customer Opens Stripe and Leaves It Open

Example:

```text
12:29
Booking -> PAYMENT_PENDING
Booking expiresAt = 13:14

Stripe Checkout expires ≈ 13:04
```

The customer may leave the browser open for ten hours.

This creates no continuous server-side work.

There is no:

```text
waiting thread
per-user timer
per-booking scheduled task
polling loop per browser
```

Stripe eventually makes the Checkout unusable.

The Booking remains protected only until its own `expiresAt`.

The Booking expiry worker later processes it.

---

# 12. Customer Leaves Stripe and Returns

Example:

```text
12:29
Attempt A1 = OPEN
redirectUrl = Stripe URL X

12:30
Customer closes the page

12:31
Customer presses Pay again
```

If:

```text
Booking = PAYMENT_PENDING
Booking.expiresAt > now
Attempt = OPEN
Attempt.providerExpiresAt > now
```

return:

```text
same Payment
same Attempt
same redirectUrl
```

Do NOT:

```text
extend Booking
create new Payment
create new Attempt
create new Stripe Session
```

---

# 13. Stripe Checkout Expires

If Stripe Checkout expires:

```text
Attempt OPEN -> EXPIRED
```

The Booking does not need an immediate state change in the first implementation.

It may remain:

```text
PAYMENT_PENDING
```

until the Booking's `expiresAt`.

The Booking expiry worker remains the authoritative inventory cleanup mechanism.

Possible future optimization:

```text
checkout.session.expired
    ->
early Booking release
```

This is deferred.

It is not required for correctness.

---

# 14. Customer Returns After Stripe Checkout Expired

If:

```text
Booking = PAYMENT_PENDING
Attempt = EXPIRED
```

do not create a second Checkout within the same payment window.

Return a clear business response such as:

```text
The payment session has expired.
The reservation payment window is no longer usable.
Please create a new booking after the reservation is released.
```

A future UX improvement may release the Booking early.

Not required now.

---

# 15. Successful Payment Confirmation

After Stripe confirms financial success:

```text
Attempt -> SUCCEEDED
Payment -> SUCCEEDED
```

Payment Service commits financial truth first.

Then Payment Service calls Booking Service.

Normal transition:

```text
PAYMENT_PENDING -> CONFIRMED
```

Conceptual CAS:

```sql
UPDATE bookings
SET
    status = 'CONFIRMED',
    payment_id = :paymentId,
    confirmed_at = :now,
    version = version + 1
WHERE id = :bookingId
  AND status = 'PAYMENT_PENDING'
  AND expires_at > :now;
```

Replay:

```text
already CONFIRMED
+ same paymentId
= idempotent success
```

Conflict:

```text
already CONFIRMED
+ different paymentId
= suspicious/conflicting financial state
```

---

# 16. Confirmation vs Expiry Race

There is still a legitimate concurrency race:

```text
Thread A:
Payment webhook -> confirm Booking

Thread B:
Booking expiry worker
```

Both may initially observe:

```text
PAYMENT_PENDING
```

Only one transition can win.

## Confirmation path

```text
PAYMENT_PENDING -> CONFIRMED
```

requires:

```text
expiresAt > now
```

## Expiry path

```text
PAYMENT_PENDING -> EXPIRING
```

requires the Booking to be due.

Once one transition changes the status, the other CAS fails.

This guarantees:

```text
no double terminal outcome
no seat release after successful Booking confirmation
```

---

# 17. One Expiry Worker

Remove the split design:

```text
expirePendingBookings()
expirePaymentPendingBookings()
```

Use one operation:

```text
expireDueBookings()
```

Conceptual query:

```sql
SELECT *
FROM bookings
WHERE status IN ('PENDING', 'PAYMENT_PENDING')
  AND expires_at <= :now
ORDER BY expires_at
LIMIT :batchSize;
```

For every candidate:

```text
PENDING
       \
        -> EXPIRING -> releaseSeats -> EXPIRED
       /
PAYMENT_PENDING
```

The status claim must remain atomic.

If the claim fails:

```text
another transition already won
-> skip
```

---

# 18. Expiry Processing and Remote Seat Release

The safe flow remains:

```text
current active state
        |
        v
atomic CAS
-> EXPIRING
        |
        v
Flight Service releaseSeats()
        |
        v
EXPIRING -> EXPIRED
```

If `releaseSeats()` has an ambiguous failure:

```text
leave Booking in EXPIRING
```

Do not guess that seats were released.

This state is a reconciliation candidate.

---

# 19. No Timer Per Booking

Even if the database contains:

```text
5,000,000 Bookings
```

the system does NOT create:

```text
5,000,000 Java Timer objects
5,000,000 threads
5,000,000 scheduler jobs
```

It stores timestamps in normal database rows.

A small number of workers query only due work.

The correct mental model is:

```text
DB rows with deadlines
        +
indexed due-work query
        +
bounded workers
```

---

# 20. Important Scaling Clarification

Do NOT claim:

```text
Millions of users create zero DB load.
```

That is false.

If one million customers perform a real Pay action, there will be real application and database work.

What this design avoids is **unnecessary background load**.

It avoids:

```text
per-booking timers
per-booking threads
full-table expiry scans
one scheduler object per user
```

Real user actions still require normal durable state changes.

---

# 21. PostgreSQL Partial Index

Recommended production index:

```sql
CREATE INDEX idx_booking_due
ON bookings (expires_at)
WHERE status IN ('PENDING', 'PAYMENT_PENDING');
```

Benefits:

```text
Only active temporary reservations are indexed.
CONFIRMED rows are excluded.
EXPIRED rows are excluded.
CANCELLED rows are excluded.
Historical data does not continuously bloat this expiry index.
```

The expiry query should closely match the partial-index predicate.

JPA `@Index` does not express a PostgreSQL `WHERE` predicate.

Therefore this index belongs in the project's SQL migration/deployment strategy.

If Flyway is not already used, do not introduce Flyway only for this one index.

---

# 22. Bounded Batch Processing

Do not process only:

```text
100 rows once per minute
```

and stop.

That can create a backlog during bursts.

Recommended pattern:

```text
Scheduler run
    |
    v
fetch batch
    |
process
    |
fetch next batch
    |
process
    |
...
```

Stop when:

```text
no due rows remain
OR
maximum batches reached
OR
time budget reached
```

Example tuning:

```text
batchSize = 200
maxBatchesPerRun = 20

maximum per run ≈ 4,000 candidates
```

These are operational tuning values, not business rules.

---

# 23. `now` Must Be Calculated Per Run

Never store current time as a singleton service field.

Wrong:

```java
@Service
class BookingExpirationServiceImpl {

    private final LocalDateTime now =
            LocalDateTime.now(ZoneOffset.UTC);
}
```

This value becomes stale.

Correct:

```java
public void expireDueBookings() {

    LocalDateTime now =
            LocalDateTime.now(ZoneOffset.UTC);

    // query due bookings...
}
```

Future improvement:

```java
Clock
```

may be injected for cleaner deterministic tests.

---

# 24. Multiple Booking-Service Instances

With one Booking Service instance:

```text
one sweeper is sufficient
```

With multiple instances, CAS still protects correctness.

Example:

```text
Instance A reads B1
Instance B reads B1

A claims:
B1 -> EXPIRING

B tries same CAS:
0 rows updated
-> skip
```

This is safe but may waste some work.

When horizontal scaling becomes necessary, improve worker claiming with:

```sql
FOR UPDATE SKIP LOCKED
```

or an equivalent work-claim strategy.

This is Level 2 scaling.

It is not required now.

---

# 25. Historical Booking Table Growth

The partial expiry index solves the due-work lookup.

It does NOT make the whole `bookings` table permanently small.

At very large historical scale, other concerns may appear:

```text
My Bookings queries
backups
vacuum
analytics
admin queries
storage
```

Future options:

```text
partitioning
archiving
cold storage
```

This is a separate long-term data-retention problem.

Do not mix it with payment-window scheduling.

---

# 26. Why No Queue Yet?

A delayed queue does not eliminate Booking state validation.

Example:

```text
12:00
message scheduled:
Expire B1 at 12:30
```

Then:

```text
12:29
PENDING -> PAYMENT_PENDING
expiresAt = 13:14
```

The old delayed message can still arrive at `12:30`.

The consumer must reload the Booking:

```text
message deadline = 12:30
current expiresAt = 13:14

message is stale
-> ignore
```

Therefore a queue introduces additional concerns:

```text
delayed-delivery infrastructure
stale events
consumer recovery
deduplication
message/version validation
```

without removing the database source of truth.

For the current project:

```text
PostgreSQL indexed sweeper
```

is simpler and sufficient.

---

# 27. Scaling Levels

## Level 1 — Current Design

```text
PostgreSQL
partial index
one/few sweepers
bounded batches
CAS
```

## Level 2 — Higher Scale

```text
multiple workers
SKIP LOCKED / claim strategy
horizontal Booking Service scaling
```

## Level 3 — Only If Measurements Require It

```text
distributed scheduling
Redis sorted-set scheduling
delayed queue
dedicated timer infrastructure
```

Do not build Level 3 before Level 1 becomes a measured bottleneck.

---

# 28. Cancellation Policy During Payment

For the simple current design:

```text
PENDING
-> customer cancellation allowed according to existing rules

PAYMENT_PENDING
-> normal cancel endpoint rejects
```

Reason:

Avoid introducing another unnecessary race:

```text
customer cancel
vs
Stripe payment success
```

If the customer merely closes Stripe, the payment window naturally expires.

A future explicit:

```text
Cancel Payment / Release Reservation
```

use case can be designed separately if product requirements demand it.

---

# 29. Final Internal API Contract

Payment Service calls:

```http
POST /internal/bookings/{bookingId}/start-payment
```

Request:

```json
{
  "userId": "..."
}
```

Response:

```json
{
  "bookingId": "...",
  "userId": "...",
  "status": "PAYMENT_PENDING",
  "totalAmount": 150.00,
  "currency": "USD",
  "expiresAt": "2026-09-30T12:30:00"
}
```

Do not expose or use:

```text
paymentHoldUntil
holdMinutes from Payment Service
payment-window ownership in Payment Service
```

Booking Service owns the reservation deadline.

---

# 30. What Is Removed From the Previous Design

Delete:

```text
Booking.paymentHoldUntil
payment_hold_until DB column
paymentHoldUntil DTO fields
payment-hold-specific index
payment-hold-specific scheduler query
expirePaymentPendingBookings()
separate paymentHold expiry path
```

Merge:

```text
expirePendingBookings()
expirePaymentPendingBookings()

into:

expireDueBookings()
```

Keep:

```text
PAYMENT_PENDING
atomic startPayment
Booking-owned payment window
idempotent no-extension behavior
Stripe shorter than Booking window
confirm-vs-expiry CAS
Payment API idempotency
provider idempotency
```

---

# 31. Booking-Service Files Expected to Change

Primary files:

```text
Booking.java
BookingRepository.java

BookingTransactionService.java
BookingTransactionServiceImpl.java

BookingExpirationService.java
BookingExpirationServiceImpl.java

BookingCleanupJob.java

BookingServiceImpl.java

PaymentContextResponse.java
```

Potential tests and configuration will also change.

---

# 32. Payment-Service Files Expected to Change

After Booking cleanup is complete, review:

```text
BookingPaymentContext.java
BookingClient.java
BookingFeignClient.java

PaymentServiceImpl.java

CheckoutRequest.java
StripePaymentGateway.java

Payment / PaymentAttempt orchestration
```

Important checks:

```text
Payment Service expects PAYMENT_PENDING, not PENDING.
Payment Service uses Booking expiresAt as the authoritative payment-window deadline.
Payment Service does not extend Booking time.
OPEN Stripe Checkout is reused.
Expired Checkout does not create a second Checkout in the same protected window.
Stripe expiry remains shorter than Booking expiresAt.
```

---

# 33. Required Tests

## Booking lifecycle

```text
A. Create Booking
   -> PENDING
   -> initial expiresAt

B. Valid PENDING startPayment
   -> PAYMENT_PENDING
   -> expiresAt changes to payment-window deadline

C. Expired PENDING startPayment
   -> rejected

D. PAYMENT_PENDING replay
   -> same expiresAt
   -> no extension

E. Concurrent startPayment
   -> one effective CAS winner
   -> loser safely returns existing payment window

F. PENDING expiry
   -> EXPIRING
   -> release seats
   -> EXPIRED

G. PAYMENT_PENDING expiry
   -> same expiresAt mechanism
   -> EXPIRING
   -> release seats
   -> EXPIRED

H. Original pre-payment deadline passes after startPayment
   -> Booking remains valid because effective expiresAt was replaced

I. confirm vs expiry race
   -> exactly one transition wins
```

## Payment initiation

```text
J. First payment initiation
   -> Booking becomes PAYMENT_PENDING before Stripe call

K. Repeated API request
   -> same Booking window
   -> same logical Payment
   -> same active Attempt

L. User returns while Attempt OPEN
   -> same redirectUrl

M. Stripe Checkout expired
   -> Attempt EXPIRED
   -> no second Checkout in same Booking payment window

N. Stripe success before Booking expiresAt
   -> Payment SUCCEEDED
   -> Booking CONFIRMED
```

## Scheduler/scaling

```text
O. Large due backlog
   -> multiple bounded batches are processed

P. Repeated worker scans
   -> no double seat release

Q. Concurrent workers
   -> CAS ensures only one expiry claim wins

R. `now` changes between scheduler runs
   -> no stale singleton timestamp
```

---

# 34. Final Invariants

The implementation must preserve all of these:

```text
1. Booking has ONE effective operational expiration deadline.

2. expiresAt means:
   "When does the current reservation lease end?"

3. PENDING uses expiresAt for the normal reservation TTL.

4. PAYMENT_PENDING uses the SAME expiresAt
   after one atomic extension.

5. Booking Service owns expiresAt and all Booking reservation timing.

6. Payment Service never chooses the Booking reservation duration.

7. startPayment cannot start after the current PENDING expiresAt.

8. startPayment changes PENDING -> PAYMENT_PENDING atomically.

9. startPayment extends expiresAt only once.

10. Repeated startPayment calls never extend the active payment window.

11. Booking becomes PAYMENT_PENDING before Stripe Checkout creation.

12. Stripe Checkout expiry is shorter than the Booking payment window.

13. One protected payment window gets one Stripe Checkout session.

14. OPEN Checkout is reused.

15. Expired Checkout does not create a second Checkout
    in the same payment window.

16. Booking confirmation requires PAYMENT_PENDING + expiresAt > now.

17. Booking expiry and Booking confirmation are competing atomic transitions.

18. One expiry worker handles both PENDING and PAYMENT_PENDING.

19. Expiry lookup is indexed and processed in bounded batches.

20. There is no timer/thread/job per Booking.

21. Queue infrastructure is not required for the current solution.

22. Scaling may change worker implementation,
    but should not change the Booking business model.

23. Millions of historical rows must not force a full-table expiry scan.

24. Real millions of user operations still produce real load;
    this design removes unnecessary timer/scheduler load, not real business work.
```

---

# 35. Final Mental Model

```text
                     BOOKING SERVICE
                     ───────────────

        ONE ROW
        ONE STATUS
        ONE EFFECTIVE expiresAt

               PENDING
                  |
                  | Proceed to Payment
                  | atomic CAS
                  v
          PAYMENT_PENDING
                  |
          +-------+--------+
          |                |
          | payment        | expiresAt reached
          | succeeds       |
          v                v
      CONFIRMED         EXPIRING
                           |
                     release seats
                           |
                           v
                        EXPIRED


                     PAYMENT SERVICE
                     ───────────────

          Payment
             |
       PaymentAttempt
             |
        Stripe Checkout
             |
           Webhook
             |
       financial truth


                     EXPIRY MODEL
                     ────────────

        PostgreSQL partial index
                  |
             due query
                  |
          bounded batches
                  |
           atomic claim
                  |
            seat release


        NO timer per booking
        NO second deadline
        NO queue required now
```

---

# 36. Final Decision Summary

Adopt:

```text
PAYMENT_PENDING                          ✅
single effective expiresAt              ✅
atomic one-time deadline extension       ✅
one Booking expiry worker               ✅
bounded repeated batches                ✅
PostgreSQL partial due index             ✅
reuse OPEN Stripe Checkout               ✅
one Checkout per protected window        ✅
Stripe window < Booking payment window   ✅
CAS for concurrency                      ✅
Booking owns inventory timing            ✅
Payment owns financial truth             ✅
```

Remove:

```text
paymentHoldUntil                         ❌
second payment deadline                  ❌
separate payment expiry query            ❌
separate payment expiry scheduler path   ❌
timer/job per Booking                    ❌
new Checkout after expiry in same window ❌
queue just for Booking expiry            ❌
```

Deferred until actual scale or product requirements justify them:

```text
SKIP LOCKED multi-worker optimization
early Booking release on Stripe session expiry
distributed delayed scheduling
Redis-based timers
queue-based expiry
historical table partitioning
archiving/cold storage
explicit Cancel Payment flow
```

---

# 37. Implementation Order From Here

```text
1. Update Booking entity
   - remove paymentHoldUntil

2. Update BookingRepository
   - startPayment updates expiresAt
   - confirmPayment checks expiresAt > now
   - unified due-bookings query

3. Update BookingTransactionService / Impl

4. Update BookingExpirationService / Impl
   - one expireDueBookings()
   - bounded batch loop
   - compute now per run

5. Update BookingCleanupJob
   - one expiry call

6. Update BookingServiceImpl.startPayment()
   - one-time effective expiresAt extension
   - idempotent replay
   - concurrent replay

7. Update PaymentContextResponse
   - remove paymentHoldUntil
   - return effective expiresAt

8. Add/prepare PostgreSQL partial index

9. Run Booking regression tests

10. Review Payment Service
    - BookingPaymentContext
    - PaymentServiceImpl
    - CheckoutRequest
    - StripePaymentGateway

11. Enforce one Checkout per payment window

12. Run Payment + Stripe E2E tests

13. Only after core flow is stable:
    continue reconciliation / exceptional reliability hardening
```

---

# 38. Status

```text
Architecture decision: LOCKED ✅

Next step:
Implement Booking-side simplification against this reference,
then review the Payment Service against the same invariants.
```
