# Flight Service — Business & Technical Reference
# Airline Booking System

> **Status:** Sprint 2 — Flight Service  
> **Language:** Arabic + English  
> **Purpose:** Living reference document for development, review, and AI-agent continuity.

---

## 1. الهدف / Purpose

### عربي
`Flight Service` مسؤول عن إدارة كل المعلومات المتعلقة بالرحلات والطائرات والمطارات والفئات السعرية والمقاعد المتاحة على مستوى **العدد**.

في هذه المرحلة نريد بناء MVP واقعي لكن بدون تعقيد غير ضروري.

### English
The `Flight Service` owns flight-related business data:
- Airports
- Aircraft
- Flights
- Fare classes
- Seat availability by fare class

It does **not** own bookings, payments, users, tickets, or notifications.

---

# 2. قرارات المشروع / Project Decisions

## Current MVP

| Feature | Decision |
|---|---|
| Direct flights | ✅ Supported |
| Connecting flights | ⏳ Later |
| Economy | ✅ |
| Business | ✅ |
| Individual seat selection (12A, 12B...) | ⏳ Later |
| Seat map | ⏳ Later |
| Seat inventory per physical seat | ⏳ Later |
| Redis | ⏳ Added with Booking/Seat Hold |
| Kafka | ⏳ Later |
| API Gateway | ⏳ Later |
| Eureka / Service Discovery | ⏳ Later |
| Docker | ⏳ Later |
| PostgreSQL | ✅ |
| Testcontainers | ✅ For integration tests |

### Core principle

> **Design for future extension, but implement only what the current business needs.**

لا نبني ميزة فقط لأنها ممكن نحتاجها لاحقاً.

---

# 3. مسؤولية Flight Service / Service Responsibility

```text
Flight Service
│
├── Airport
├── Aircraft
├── Flight
└── FareClass
```

### Flight Service answers:

- What airports exist?
- What aircraft exist?
- What flights are scheduled?
- What is the origin/destination?
- When does the flight depart/arrive?
- Which fare classes are available?
- What is the price?
- How many seats remain in each fare class?

### Flight Service does NOT answer:

- Who is the customer?
- Who is the passenger?
- Is a booking confirmed?
- Was payment successful?
- Should a ticket be generated?
- Should an email be sent?

Those belong to other services.

---

# 4. Business Flow

```text
Client
   │
   ▼
Search Flights
   │
   ▼
Flight Service
   │
   ├── Airport
   ├── Flight
   ├── Aircraft
   └── FareClass
   │
   ▼
Available Flights
   │
   ▼
Booking Service
```

Example:

```text
Search:
AMM → JFK
Date: 2026-08-20
Passengers: 2
Class: Economy
```

Response concept:

```json
{
  "flightNumber": "RJ123",
  "origin": "AMM",
  "destination": "JFK",
  "departureTime": "2026-08-20T10:00:00",
  "arrivalTime": "2026-08-20T18:00:00",
  "fares": [
    {
      "classType": "ECONOMY",
      "price": 700,
      "currency": "USD",
      "availableSeats": 180
    },
    {
      "classType": "BUSINESS",
      "price": 1800,
      "currency": "USD",
      "availableSeats": 25
    }
  ]
}
```

---

# 5. Domain Model / ERD

## Main entities

```text
AIRPORT
   │
   │ origin / destination
   ▼
FLIGHT ───────────< FARE_CLASS
   │
   │
   └──────────────> AIRCRAFT
```

### Database

```text
flight_db
│
├── airports
├── aircraft
├── flights
└── fare_classes
```

---

# 6. Airport

## Business meaning

An airport is a physical airport/location used as the origin or destination of flights.

Example:

```text
Queen Alia International Airport
IATA: AMM
ICAO: OJAI
City: Amman
Country: Jordan
```

## Table: `airports`

```text
id
iata_code
icao_code
name
city
country
timezone
status
created_at
updated_at
```

## Relationships

One airport can be the origin of many flights.

One airport can be the destination of many flights.

```text
Airport 1 ───────< Flight
Airport 1 ───────< Flight
```

The two relationships are logically:

```text
origin_airport_id
destination_airport_id
```

## Business Rules

- `iata_code` must be unique.
- `icao_code` should be unique.
- Name is required.
- City is required.
- Country is required.
- Airport must be `ACTIVE` to be used for new flights.
- An airport used by flights should not be physically deleted.

## Status

```text
ACTIVE
INACTIVE
```

---

# 7. Aircraft

## Business meaning

Aircraft represents the actual aircraft used to operate flights.

Example:

```text
Registration: JY-ABC
Manufacturer: Boeing
Model: 787-9
Capacity: 280
```

An aircraft can operate many flights over time.

```text
Aircraft
   │
   ├── Flight RJ123
   ├── Flight RJ456
   └── Flight RJ789
```

## Table: `aircraft`

```text
id
registration_number
manufacturer
model
total_capacity
status
created_at
updated_at
```

## Business Rules

- Registration number must be unique.
- Capacity must be greater than zero.
- Aircraft must be `ACTIVE` before being assigned to a new flight.
- Aircraft in `MAINTENANCE` cannot be assigned to new flights.
- Retired aircraft cannot be used.

## Status

```text
ACTIVE
MAINTENANCE
RETIRED
```

---

# 8. Flight

## Business meaning

A Flight is a scheduled operation between two airports using an aircraft.

Example:

```text
RJ123
AMM → JFK

Departure: 2026-08-20 10:00
Arrival:   2026-08-20 18:00
Aircraft:  Boeing 787-9
```

## Table: `flights`

```text
id
flight_number
origin_airport_id
destination_airport_id
aircraft_id
departure_time
arrival_time
status
created_at
updated_at
```

## Relationships

```text
Airport ───────< Flight
Airport ───────< Flight
Aircraft ──────< Flight

Flight ────────< FareClass
```

## Important distinction

`Flight` is NOT the same as `Aircraft`.

Aircraft:

> The physical plane.

Flight:

> A scheduled trip operated by that plane.

## Business Rules

- Origin and destination cannot be the same.
- Arrival must be after departure.
- Origin airport must exist.
- Destination airport must exist.
- Aircraft must exist.
- Aircraft must be active.
- Flight cannot be created with an invalid time range.
- A flight number must be valid.
- Business rules involving existing database records belong in the service layer.

## Status

MVP:

```text
SCHEDULED
DELAYED
CANCELLED
COMPLETED
```

Can be expanded later:

```text
BOARDING
DEPARTED
IN_AIR
LANDED
```

---

# 9. Fare Class

## Why FareClass?

We deliberately do NOT put:

```text
flight.price
flight.availableSeats
```

directly inside `Flight`.

Because one flight can have multiple fare classes.

Example:

```text
RJ123

ECONOMY
Price: 700 USD
Total: 250
Available: 180

BUSINESS
Price: 1800 USD
Total: 30
Available: 25
```

## Table: `fare_classes`

```text
id
flight_id
class_type
price
currency
total_seats
available_seats
created_at
updated_at
```

## Relationship

```text
Flight 1 ─────────< FareClass
```

## Enum

```text
ECONOMY
BUSINESS
```

Can be expanded later:

```text
PREMIUM_ECONOMY
FIRST
```

## Business Rules

- Price must be greater than zero.
- Total seats must be greater than zero.
- Available seats cannot be negative.
- Available seats cannot exceed total seats.
- The same fare class cannot appear twice for the same flight.
- A fare class must belong to an existing flight.

---

# 10. Why We Are NOT Building Individual Seats Yet

We intentionally postpone:

```text
aircraft_seats
flight_seat_inventory
```

and therefore we do NOT track:

```text
12A
12B
12C
```

individually.

## Current model

We track:

```text
Economy:
250 total
180 available
```

not:

```text
12A AVAILABLE
12B BOOKED
12C HELD
```

### Why?

Individual seat selection adds:

- Aircraft seat configuration
- Physical seat mapping
- Seat classes
- Seat status per flight
- Seat map APIs
- Seat selection UI
- More concurrency rules
- More database rows
- More Booking complexity

This is a separate feature.

## Future feature

```text
Seat Selection Feature
```

Possible future tables:

```text
aircraft_seats
flight_seat_inventory
```

We can add them later without replacing the current `fare_classes` design.

---

# 11. Direct Flights vs Connecting Flights

## Current MVP

Only direct flights:

```text
AMM ───────────> JFK
```

## Future

Connecting itinerary:

```text
AMM ───> IST ───> JFK
        Flight 1
        Flight 2
```

A future `Itinerary` / `FlightSegment` concept can contain multiple flights.

Example:

```text
Itinerary
│
├── Segment 1
│     AMM → IST
│
└── Segment 2
      IST → JFK
```

We do NOT implement this now.

The current Flight model should remain compatible with this future extension.

---

# 12. Database Ownership

Each microservice owns its own database.

```text
Auth Service
    ↓
auth_db

Flight Service
    ↓
flight_db

Booking Service
    ↓
booking_db

Payment Service
    ↓
payment_db
```

There is NO shared database.

Flight Service must not directly query:

```text
auth_db
booking_db
payment_db
```

---

# 13. PostgreSQL

We use PostgreSQL for Flight Service.

Example:

```text
Database: flight_db
Host: localhost
Port: 5432
```

Spring configuration concept:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/flight_db
spring.datasource.username=postgres
spring.datasource.password=********
```

Credentials should not be committed to Git.

---

# 14. Architecture

Recommended package structure:

```text
com.project.flightservice
│
├── controller
│
├── service
│   └── impl
│
├── repository
│
├── model
│   ├── entity
│   └── enums
│
├── dto
│   ├── request
│   └── response
│
├── mapper
│
├── exception
│
├── config
│
└── FlightServiceApplication
```

## Layer responsibilities

### Controller

Handles HTTP requests.

```text
HTTP
 ↓
Controller
```

### Service

Contains business logic.

```text
Controller
 ↓
Service
 ↓
Repository
```

### Repository

Database access.

```text
Service
 ↓
Repository
 ↓
PostgreSQL
```

### Entity

Database representation.

### DTO

API request/response contract.

### Mapper

Converts:

```text
Entity ↔ DTO
```

---

# 15. API Design

## Airports

```http
POST   /api/airports
GET    /api/airports
GET    /api/airports/{id}
PUT    /api/airports/{id}
DELETE /api/airports/{id}
```

## Aircraft

```http
POST /api/aircraft
GET  /api/aircraft
GET  /api/aircraft/{id}
PUT  /api/aircraft/{id}
```

## Flights

```http
POST /api/flights
GET  /api/flights
GET  /api/flights/{id}
GET  /api/flights/search
PUT  /api/flights/{id}
```

## Availability

```http
GET /api/flights/{flightId}/availability
```

Example:

```json
{
  "flightId": "uuid",
  "fares": [
    {
      "classType": "ECONOMY",
      "price": 700,
      "availableSeats": 180
    },
    {
      "classType": "BUSINESS",
      "price": 1800,
      "availableSeats": 25
    }
  ]
}
```

---

# 16. Search API

Example:

```http
GET /api/flights/search?origin=AMM&destination=JFK&date=2026-08-20
```

Search criteria may later include:

```text
origin
destination
date
passengers
fareClass
```

For MVP, keep the search simple.

---

# 17. REST Communication

At this stage:

```text
Client
   │
   │ REST
   ▼
Flight Service
```

Booking will later communicate with Flight Service synchronously when it needs current availability.

Example:

```text
Booking Service
      │
      │ REST
      ▼
Flight Service
      │
      ▼
Check available seats
```

---

# 18. Redis — Later

Redis is NOT a microservice.

It is infrastructure.

We introduce it when Booking needs temporary seat holds.

Example:

```text
Booking Service
      │
      ▼
Flight Service
      │
      ▼
Redis

seat_hold:flight123:economy
TTL = 10 minutes
```

Redis is not required for the current Flight CRUD implementation.

---

# 19. Kafka — Later

Kafka will be introduced when asynchronous events become useful.

Examples:

```text
BookingConfirmed
BookingCancelled
BookingExpired
PaymentSucceeded
PaymentFailed
```

Possible future flow:

```text
Payment
   │
   │ Kafka Event
   ▼
Booking
   │
   │ Kafka Event
   ▼
Notification
```

We do not introduce Kafka during basic Flight Service development.

---

# 20. Validation Strategy

Not every rule belongs in Bean Validation.

## DTO/Input validation

Examples:

```text
@NotBlank
@NotNull
@Positive
@Size
```

Used for simple input constraints.

## Service-level business validation

Examples:

```text
Origin != Destination

Arrival > Departure

Aircraft must be ACTIVE

Airport must be ACTIVE

AvailableSeats <= TotalSeats

FareClass must be unique for a Flight
```

These may require database access and therefore belong in the service/business layer.

---

# 21. Exception Handling

Expected structure:

```text
ResourceNotFoundException
BusinessException
DuplicateResourceException
InvalidFlightException
```

Global handling:

```text
@RestControllerAdvice
```

Example response:

```json
{
  "timestamp": "2026-08-20T10:00:00",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Flight not found",
  "path": "/api/flights/123"
}
```

---

# 22. Testing Strategy

## Unit Tests

Use:

```text
JUnit
Mockito
```

Test business logic without a real database.

## Integration Tests

Use:

```text
Testcontainers
PostgreSQL
```

Reason:

We want to test against the same database technology used in production.

Example:

```text
Test
 ↓
Testcontainers
 ↓
Real PostgreSQL
 ↓
Repository
```

We do not rely on H2 for database behavior.

---

# 23. Technologies

## Current

```text
Java 21
Spring Boot 4.1.x
Spring Web
Spring Data JPA
Hibernate
PostgreSQL
Lombok
Bean Validation
JUnit
Mockito
Testcontainers
Maven
Git
```

## Later

```text
Redis
Kafka
Docker
API Gateway
Service Discovery
OpenFeign
Observability
```

Do not add future technologies before the business actually needs them.

---

# 24. Development Order

## Phase 1 — Domain

1. Airport
2. Aircraft
3. Flight
4. FareClass
5. Enums
6. Relationships
7. Business rules

## Phase 2 — Database

1. PostgreSQL database
2. JPA entities
3. Relationships
4. Constraints
5. Hibernate schema validation/update

## Phase 3 — Repository

Create repositories:

```text
AirportRepository
AircraftRepository
FlightRepository
FareClassRepository
```

## Phase 4 — DTO

Create:

```text
request DTOs
response DTOs
```

Do not expose JPA entities directly as API contracts.

## Phase 5 — Mapper

```text
Entity → Response DTO
Request DTO → Entity
```

## Phase 6 — Service

Implement business rules.

## Phase 7 — Controller

Expose REST APIs.

## Phase 8 — Validation & Exceptions

Add:

```text
Bean Validation
Global Exception Handler
Business Exceptions
```

## Phase 9 — Testing

```text
Unit tests
Integration tests
Testcontainers
```

---

# 25. Example End-to-End Creation Flow

Creating an Airport:

```text
POST /api/airports
        │
        ▼
AirportController
        │
        ▼
AirportService
        │
        ├── Validate IATA uniqueness
        ├── Validate fields
        │
        ▼
AirportRepository
        │
        ▼
PostgreSQL
```

Creating an Aircraft:

```text
POST /api/aircraft
        │
        ▼
AircraftController
        │
        ▼
AircraftService
        │
        ├── Validate registration
        ├── Validate capacity
        │
        ▼
AircraftRepository
```

Creating a Flight:

```text
POST /api/flights
        │
        ▼
FlightController
        │
        ▼
FlightService
        │
        ├── Find origin airport
        ├── Find destination airport
        ├── Validate airports
        ├── Find aircraft
        ├── Validate aircraft status
        ├── Validate dates
        ├── Validate origin != destination
        │
        ▼
Create Flight
        │
        ▼
Create FareClasses
        │
        ▼
PostgreSQL
```

---

# 26. Important Architectural Rules

### Rule 1 — Database per Service

```text
Auth → auth_db
Flight → flight_db
Booking → booking_db
Payment → payment_db
```

### Rule 2 — No shared entities

Do NOT create:

```text
common-lib
    BookingEntity
    FlightEntity
    UserEntity
```

Each service owns its own domain model.

### Rule 3 — DTO contracts belong to the service

Flight Service defines its own:

```text
FlightResponse
FlightSearchResponse
```

Booking Service should not import Flight Service DTOs.

### Rule 4 — Why before How

Before implementing a class, answer:

> Why does this entity/service/table exist?

### Rule 5 — Don't add technology prematurely

Do not add:

```text
Kafka
Redis
Gateway
Eureka
Docker
```

until we reach the business problem they solve.

---

# 27. Future Roadmap

After the basic Flight Service:

```text
Flight Service
      ↓
Booking Service
      ↓
Redis Seat Hold
      ↓
Payment Service
      ↓
Kafka
      ↓
Ticket Service
      ↓
Notification Service
      ↓
API Gateway
      ↓
Service Discovery
      ↓
Docker
```

Future advanced features:

```text
Connecting Flights
Seat Selection
Dynamic Pricing
Fare Rules
Baggage
Multi-city trips
Refund/Cancellation rules
Flight rescheduling
```

---

# 28. Current Scope — DO NOT FORGET

At the current stage:

```text
                FLIGHT SERVICE

        ┌───────────────┐
        │    Airport    │
        └───────┬───────┘
                │
                ▼
        ┌───────────────┐
        │    Flight     │
        └───────┬───────┘
                │
                ▼
        ┌───────────────┐
        │   FareClass   │
        └───────────────┘
                ▲
                │
        ┌───────┴───────┐
        │    Aircraft   │
        └───────────────┘
```

### We currently support:

```text
Direct Flights
Economy
Business
Fare-level seat availability
Airport management
Aircraft management
Flight management
Flight search
```

### We currently DO NOT support:

```text
Individual seat selection
Seat maps
Connecting flights
Redis holds
Kafka events
Payment
Booking
Tickets
Notifications
```

---

# 29. Definition of Done — Flight Service MVP

The Flight Service is considered complete when:

- [ ] PostgreSQL `flight_db` exists
- [ ] Airport CRUD works
- [ ] Aircraft CRUD works
- [ ] Flight creation works
- [ ] Fare classes can be created with flights
- [ ] Flight search works
- [ ] Availability can be retrieved
- [ ] Business validation works
- [ ] Global exception handling works
- [ ] DTOs are used instead of exposing entities
- [ ] Repository tests exist
- [ ] Service unit tests exist
- [ ] Integration tests use Testcontainers
- [ ] No unnecessary Redis/Kafka/Gateway dependencies exist
- [ ] README/business decisions are updated when decisions change

---

# 30. Living Document Rule

This README is intentionally **editable**.

Whenever we make an important business or architecture decision, update this document.

Especially changes involving:

```text
Business rules
Database schema
Entities
Relationships
API contracts
Service responsibilities
Redis
Kafka
Booking flow
Connecting flights
Seat selection
```

The README should remain the project's **single human-readable reference**.

---

# Final Principle

> **We are not building a tutorial clone. We are building an airline booking system while learning the business and architecture step by step.**

> **WHY → Business → Domain → Database → API → Code → Infrastructure**

Do not reverse this order.


─ enums
    ├── AirportStatus.java
    ├── AircraftStatus.java
    ├── FlightStatus.java
    ├── FareClassType.java
    └── Currency.java

وفي FareClass:

@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 3)
private Currency currency;

ومهم جدًا: نستخدم:

EnumType.STRING

وليس ORDINAL، حتى لا يصير عندنا مشاكل إذا غيرنا ترتيب الـ Enum لاحقًا.

وبالنسبة للسعر نفسه، لا نستخدم double؛ نستخدم:

BigDecimal price;

لأن أسعار المال لازم تكون دقيقة.

يعني:

private BigDecimal price;

@Enumerated(EnumType.STRING)
private Currency currency;    