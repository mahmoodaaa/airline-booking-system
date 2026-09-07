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
