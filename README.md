# ✈️ Airline Booking System — Microservices

> نظام حجز طيران مبني بمعمارية Microservices حقيقية، بهدف فهم كيف تُبنى الأنظمة الموزعة القابلة للتوسّع في الشركات الحقيقية — وليس مجرد نسخ كورس.

---

## 🎯 هدف المشروع

الهدف **ليس** بناء نظام حجز طيران فقط.

الهدف الحقيقي هو فهم:

- Business Analysis قبل الكود
- Domain-Driven Design (DDD)
- Database per Service
- Microservices Architecture
- REST vs Event-Driven Communication (Kafka)
- Redis Caching & Distributed Locking
- Docker & Containerization
- Security (JWT, Stateless Auth)
- Architecture Decision Records (ADR)

**فلسفة المشروع: WHY قبل HOW.**
كل تقنية بننزلها لازم يكون إلها سبب بزنسي واضح، مش لأنها "كول" أو موجودة بكل كورس.

---

## 🧠 Business Flow الأساسي

```
Customer → Search Flight → Select Flight → Reserve Seat (Hold)
→ Payment → Booking Confirmed → Ticket Generated → Email Sent
```

راجع `PROJECT_CONTEXT.md` لتفاصيل الـ State Machine الكاملة لحالة الحجز.

---

## 🏗 الخدمات (Services)

### الوضع الحالي — Sprint 1

| الخدمة | الحالة | المسؤولية |
|--------|--------|-----------|
| **auth-service** | 🚧 قيد البناء | Register, Login, JWT, Roles, Users (User مدمج مؤقتاً داخل Auth) |
| **flight-service** | ⏳ لسا Maven skeleton بس | Airports, Aircraft, Flights, Seat Inventory, Search |
| **booking-service** | ⏳ لسا Maven skeleton بس | Booking Lifecycle, Passengers, Booking Status |
| **common-lib** | ✅ فاضية جاهزة | مكتبة مشتركة (Exceptions, ApiResponse, Utils) — **بدون** DTOs أو Entities |

### خدمات مستقبلية (حسب الـ Roadmap)

Payment, API Gateway (`cloud/`), Notification, Ticket, User Service (منفصل عن Auth)، Search, Analytics، وفصل Flight إلى Airport/Aircraft/Seat.

**القاعدة:** ما بننزل أي خدمة أو تقنية قبل ما نحتاجها فعلياً. راجع `PROJECT_ROADMAP.md` للتسلسل الكامل بالـ Sprints.

---

## 🛠 Tech Stack

| الطبقة | التقنية | ليش |
|--------|---------|-----|
| Language | Java 21 (LTS) | دعم طويل الأمد |
| Framework | Spring Boot 4.1.0 | أحدث نسخة مستقرة |
| Build | Maven Multi-Module | Parent POM مركزي + BOM لضبط النسخ |
| Database | PostgreSQL (لكل الخدمات) | دعم native لـ UUID، JSONB، Concurrency قوي |
| Cache / Distributed Lock | Redis | Search caching + Seat Hold بـ TTL |
| Messaging | Apache Kafka | تواصل Async بين الخدمات (Payment → Booking → Notification) |
| Auth | JWT (jjwt 0.12.6) | Stateless Authentication بين كل الخدمات |
| Containerization | Docker + Docker Compose | تشغيل كل الخدمات والبنية التحتية محلياً |

**قاعدة صارمة:** كل خدمة تملك قاعدة بياناتها الخاصة بالكامل (**Database per Service**) — ما في Shared Database أبداً، وما في Foreign Keys حقيقية بين خدمات مختلفة.

---

## 📁 هيكل المشروع

```
airline-booking-system/
├── pom.xml                    ← Root Parent POM (packaging=pom, BOM management)
├── common-lib/                ← مكتبة مشتركة (packaging=jar)
├── cloud/                     ← Aggregator فاضي — Sprint 3 (Gateway, Eureka, Config)
├── services/
│   ├── pom.xml                ← Aggregator
│   ├── auth-service/
│   ├── flight-service/
│   └── booking-service/
└── docs/
    ├── README.md               ← هذا الملف
    ├── PROJECT_CONTEXT.md      ← سجل كل القرارات التقنية والبزنسية (مرجع تفصيلي)
    └── PROJECT_ROADMAP.md      ← خطة الـ Sprints الكاملة
```

---

## 📐 قواعد العمل (Rules)

1. **لا تضيف تقنية قبل ما تحتاجها فعلياً.**
2. **افهم الـ WHY قبل الـ HOW.**
3. **كل Sprint لازم ينتج نظام شغّال فعلياً** — مش وثائق بس.
4. **ابسط أولاً، حسّن لاحقاً.**
5. **الخدمة اللي تملك البيانات هي الوحيدة اللي تعدّلها** (Encapsulation).

---

## 📚 وثائق إضافية

- **`PROJECT_CONTEXT.md`** — كل قرار تقني اتخذ ولماذا (استخدمه كمرجع كامل عند التحدث مع أي AI Agent أو أي مطور جديد ينضم للمشروع)
- **`PROJECT_ROADMAP.md`** — تسلسل الـ Sprints الكامل من Sprint 0 لغاية Sprint 10
 

//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
أنا بفضّل نعتمدها رسميًا كـ canonical roadmap من Sprint 1 إلى Sprint 15، ونعتبر Sprint 4.5 جزءًا من Sprint 4 حتى يظل الترقيم نظيف.

هيك تكون الخطة النهائية:

Sprint 1 — Auth & User Security
Users, roles, JWT, Spring Security، login/register/refresh، أساس الـauthentication.
Sprint 2 — Flight Service Core
Flights, Aircraft, Airports, FareClass، availability/inventory والأساس الخاص بالرحلات.
Sprint 3 — API Gateway & Distributed Security
Spring Cloud Gateway، JWT validation، routing، CUSTOMER/ADMIN access، ومنع الوصول غير المصرح.
Sprint 4 — Booking + Inter-Service Communication
Booking lifecycle، passengers، seat reservation count، TTL/expiry/cancel، concurrency، idempotency، OpenFeign، SERVICE JWT، Booking ↔ Flight.

Sprint 5 — Payment + Refund + Reconciliation
اللي إحنا فيه الآن:

Stripe Checkout
Payment / Attempt
API Idempotency
Webhooks
Inbox/Deduplication
Booking confirmation
Technical Refund
Reconciliation

وبعد Refund + Reconciliation نقفله بالكامل.

Sprint 6 — Kafka + Transactional Outbox
مش مجرد producer/consumer demo. بدنا:

Business TX
   +
Outbox row
   ↓
Publisher
   ↓
Kafka

مع event contracts، retries، consumer idempotency، delivery semantics.

Sprint 7 — Notification Service
Service مستقل يستهلك Kafka events مثل:

BookingConfirmed
BookingCancelled
PaymentSucceeded
PaymentRefunded

ونبدأ Email notifications، وبعدها قابل للتوسع SMS/Push.

Sprint 8 — Redis / Distributed Cache
Cache للبيانات المناسبة، TTL، invalidation، cache-aside، وربما distributed locking فقط إذا ظهر له use case حقيقي.

Sprint 9 — Seat Management
وهذي إضافة ممتازة من فكرة الدورة:

Cabin
Seat
FlightSeatInstance
AVAILABLE / HELD / BOOKED / BLOCKED
Seat Hold TTL
Concurrent seat selection

وهذي رح تكون قوية جدًا تقنيًا.

Sprint 10 — Pricing / Fare Rules
بدل السعر الثابت فقط:

Base fare
Fare rules
Cabin
passenger type
route/date rules
fees
    ↓
Quote
    ↓
Booking price snapshot

Sprint 11 — Flight Search / Read Model
Search API يجمع:

Flight
+ Price
+ Availability

وهون ممكن نتعلم API Composition أو event-driven read model، ونستخدم Redis بشكل فعلي.

Sprint 12 — Ancillaries
مثل:

Extra baggage
Meals
Insurance
Priority boarding
Other extras

ونربطها بالـBooking والـPricing بدون تخريب core domains.

Sprint 13 — Observability + Resilience
أنا أضيف كلمة Resilience هنا، لأنها مهمة جدًا:

Actuator
Metrics
Prometheus
Grafana
Distributed tracing
Correlation IDs
Centralized logs

Timeouts
retries where safe
Circuit Breaker
failure monitoring

مش شرط كل technology؛ نختار اللي إله داعي.

Sprint 14 — Docker + CI/CD
Dockerfiles/Jib، Docker Compose، health checks، profiles/config، automated build/test pipeline، وربما GitHub Actions/Jenkins حسب اللي نختاره.
Sprint 15 — Production Deployment
VPS/cloud deployment، domain، Nginx reverse proxy، HTTPS/SSL، environment secrets، PostgreSQL/Kafka/Redis deployment strategy، monitoring، final production smoke tests.

وفي شغلتين متعمدين مش موجودين كسبرنت لحالهم:

Eureka
Config Server

ما رح نحطهم فقط لأن course استخدمهم. إذا أثناء Docker/Deployment ظهر عندنا سبب حقيقي لـservice discovery أو centralized config، وقتها نضيفهم. Otherwise Docker DNS + environment configuration ممكن يكونوا كافيين.

وكمان Testing مش Sprint منفصل. كل Sprint لازم ينتهي بـ:

Unit Tests
Integration Tests
Concurrency/Failure Tests حسب الحاجة
Manual E2E
DB verification
Documentation

مثل اللي عملناه بالـPayment، وهذا بصراحة هو اللي أعطى المشروع قيمته.

فأنا أعتمد هذا من هسا كـroadmap الرئيسي:

1  Auth
2  Flight
3  Gateway/Security
4  Booking + Inter-Service
5  Payment + Refund + Reconciliation
6  Kafka + Outbox
7  Notification
8  Redis
9  Seat Management
10 Pricing
11 Flight Search / Read Model
12 Ancillaries
13 Observability + Resilience
14 Docker + CI/CD
15 Deployment + Nginx + HTTPS

والجميل إن بعد Sprint 8 تقريبًا نكون أنهينا معظم distributed-systems infrastructure، ومن Sprint 9 نبدأ نثري الـairline domain نفسه بشكل أقوى