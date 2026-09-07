# 📖 Project Context — سجل القرارات الكامل

> هذا الملف مرجع شامل لكل قرار تقني وبزنسي اتخذناه بالمشروع، ولماذا.
> استخدمه كسياق كامل عند بدء محادثة جديدة مع أي AI Agent، أو عند إشراك مطور جديد بالفريق.

---

## 1. فلسفة المشروع

- **WHY قبل HOW** — كل تقنية بتنزل لازم يكون إلها مبرر بزنسي، مش لأنها موجودة بكل كورس.
- **Start cohesive, split when the business justifies it** — نبدأ بخدمات مدمجة بسيطة، ونفصلها فقط لما تكبر المسؤولية فعلياً.
- **كل Sprint لازم ينتج نظام شغّال** — لا للـ Analysis Paralysis.
- **Database per Service** — كل خدمة قاعدة بياناتها الخاصة، بدون Shared DB وبدون Foreign Keys حقيقية بين الخدمات.

---

## 2. البنية المعمارية (Architecture)

### دورة حياة الحجز (Booking State Machine) — Sprint 4

```
IN_PROGRESS (مؤقت أثناء reserve)
        ↓
     PENDING (مقاعد محجوزة — TTL 10min)
     /     \
    ↓       ↓
CANCELLED  EXPIRED
    \       /
     ↓     ↓
   release seats
```

**ما لا يوجد في Sprint 4:** `CONFIRMED` — يأتي مع Payment Sprint فقط.

- **IN_PROGRESS:** حالة مؤقتة أثناء reserve call. إذا فشل reserve → لا يُحفظ الحجز نهائياً.
- **PENDING:** reserve نجح، `expiresAt = NOW() + 10min`. المستخدم يعرف: "عندك 10 دقائق".
- **Scheduler قاعدة صارمة:** لا يُعلم EXPIRED إلا بعد نجاح release. إذا فشل release → يبقى PENDING + log error + retry في الدورة القادمة.
- **Cancel Idempotency:** CANCELLED → CANCELLED = no-op (لا release مرة ثانية).
- **Expire Idempotency:** EXPIRED → لا release مرة ثانية.

### مين بيحكي مع مين (Sync REST vs Async Kafka)

| من | إلى | النوع | السبب |
|----|-----|-------|-------|
| Client | Gateway / Auth / Flight / Booking / Payment | Sync REST | المستخدم مستني رد فوري |
| Booking | Flight (Inventory) | Sync REST | لازم تأكيد فوري لتوفر المقعد |
| Payment → Booking (تأكيد) | Async Kafka | نتيجة جانبية، أكتر من Consumer مهتم |
| Booking (Expired) → Notification | Async Kafka | ليس على المسار الحرج |

**القاعدة الذهبية:** أي شي المستخدم مستني نتيجته فوراً = REST. أي نتيجة جانبية = Kafka.

### مشكلة Dual Write / Distributed Transaction

**Sprint 4:** يُستخدم Best-Effort Compensation Pattern:
```
reserve seats (Flight) → save Booking (booking_db)
                         إذا فشل save: compensating release
                         إذا فشل release أيضاً: CRITICAL structured log + manual recovery
```
لا ندّعي أن النظام distributed-transaction safe. الاعتراف بالمشكلة + توثيقها.
**Sprint Kafka:** استبدال بـ Outbox Pattern / Saga.

---

## 3. Business Rules

- **Rule 1:** لا يمكن حجز رحلة غادرت بالفعل (`departureTime < NOW()`).
- **Rule 2:** لا يمكن حجز مقعد غير متاح (`availableSeats < requestedCount`).
- **Rule 3:** كل حجز PENDING صالح 10 دقائق (`expiresAt`).
- **Rule 4:** بعد انتهاء المهلة → EXPIRED + إعادة المقاعد تلقائياً عبر Scheduler.
- **Rule 5 (Edge Case):** إذا نجح الدفع بعد انتهاء TTL → Auto-refund فوري + إشعار المستخدم.
- **Rule 6 (Booking):** Booking Service لا يخزن بيانات الرحلة الحية — يحفظ Snapshot (`flightId`, `fareClassId`, `priceAtBooking`, `currency`) لحماية بيانات الحجز من التغيير المستقبلي.
- **Rule 7 (Auth):** رابط التفعيل صالح 24 ساعة.
- **Rule 8 (Auth):** لا تسجيل دخول طالما `status != ACTIVE`.

---

## 4. قرارات الـ Maven / البنية التقنية

### Root Parent POM
- `packaging=pom`, يستورد `spring-boot-dependencies` كـ BOM عبر `<dependencyManagement>`.
- **الإصدارات المعتمدة:** Spring Boot `4.1.0`، Spring Cloud `2025.1.2` (إذا أُضيف لاحقاً — أول patch مستقر مع Boot 4.x).

### هيكل الـ Modules
```
cloud/     → api-gateway (Spring Cloud Gateway, Reactive/WebFlux, منفذ 8080)
services/  → auth-service (7171) + flight-service (7172) + booking-service (7373 — Sprint 4)
common-lib/→ مكتبة مشتركة (packaging=jar)
```

### common-lib — ماذا يدخل وماذا لا يدخل
✅ **يدخل:** `ApiResponse<T>`, `ApiBaseException` + فروعه, `GlobalExceptionHandler`, `ErrorDetails`, `ValidationError`.
❌ **لا يدخل:** DTOs أو Entities — تكسر Service Autonomy وتُنشئ Tight Coupling.

### Dependencies معتمدة بالمشروع

| المكتبة | الإصدار | الملاحظة |
|---------|---------|----------|
| Spring Boot | 4.1.0 | BOM بالـ Parent |
| Spring Cloud | 2025.1.2 | للـ Gateway فقط (إذا لزم لاحقاً) |
| jjwt | 0.12.6 | API الجديد (`parser()`, `getPayload()`) |
| springdoc-openapi-starter-webmvc-ui | 3.1.0 | متوافق مع Boot 4.x |
| spring-data-commons | (BOM) | في common-lib لـ `PropertyReferenceException` |
| spring-tx | (BOM) | في common-lib لـ `InvalidDataAccessApiUsageException` |

### أخطاء تقنية شائعة — تنبيه دائم
- `spring-boot-starter-webmvc` **غير موجود** — الصحيح: `spring-boot-starter-web`.
- `jjwt 0.11.5` API قديم — الصحيح: `0.12.6`.
- `PropertyReferenceException` في Spring Data 4.x انتقلت من `org.springframework.data.mapping` إلى `org.springframework.data.core`.

---

## 5. قرار: Auth Service يبلع User مؤقتاً

**القرار المعتمد:** لا `user-service` منفصل الآن.

- Auth Service يحتوي: Register, Login, JWT, Refresh Token, Roles, Users (بيانات هوية فقط).
- **لن يحتوي على:** بيانات بروفايل الراكب (جواز سفر، تفضيلات) — تنتقل لاحقاً إلى `user-service` منفصل.

### حقول جدول `users` (auth_db)
```
id (UUID, PK)
email (unique, not null)
password_hash (not null, BCrypt)
first_name
last_name
role (ENUM: CUSTOMER, ADMIN)
status (ENUM: PENDING_VERIFICATION, ACTIVE, SUSPENDED)
verification_token (unique, nullable)
verification_token_expiry (nullable)
created_at
updated_at
```

---

## 6. قرار قاعدة البيانات: PostgreSQL للجميع

- دعم native لـ `UUID`.
- علائقي بطبيعته (FKs، Transactions، Many-to-Many).
- استثناء مستقبلي: Elasticsearch **بجانب** Postgres (وليس بديلاً) لـ Sprint البحث المتقدم.

---

## 7. حالة التنفيذ الحالية (Progress Snapshot)

| العنصر | الحالة |
|--------|--------|
| Root Parent POM | ✅ جاهز |
| common-lib | ✅ مكتملة — `ApiResponse<T>`, Exceptions, `GlobalExceptionHandler` (يشمل `PropertyReferenceException` + `InvalidDataAccessApiUsageException` → 400) |
| cloud/api-gateway | ✅ Sprint 3 مكتمل — انظر القسم 12 |
| auth-service | ✅ Sprint 1 مكتمل — انظر القسم 9 |
| flight-service | ✅ Sprint 2 مكتمل — انظر القسم 10 |
| **booking-service** | 🔜 **Sprint 4 — الخطوة التالية** |

---

## 8. منهجية بناء كل خدمة

```
1. Business Analysis  (لماذا هذا الـ Service موجود؟ ماذا يملك/لا يملك؟)
2. Database Design    (رسم الجدول ومناقشة كل قرار)
3. Project Structure  (packages)
4. Entities
5. DTOs
6. Repository
7. Service
8. Controller
9. Testing
10. Refactoring
```

**ممنوع:** البدء مباشرة بـ Entity بدون فهم البزنس أولاً.

---

## 9. Sprint 1 — Auth Service

### 9.1 Tech Stack
| كان مخطط | صار فعلياً | السبب |
|----------|-----------|-------|
| jjwt 0.11.5 | **jjwt 0.12.6** | API قديم مختلف |
| springdoc 2.8.5 | **springdoc-starter-webmvc-ui 3.1.0** | متوافق مع Boot 4.x |

### 9.2 هيكل Packages (auth-service)
```
com.project.authservice/
├── entity/          (User)
├── enums/           (UserRole, UserStatus)
├── mapper/          (UserMapper)
├── dto/request/     (RegisterRequest, LoginRequest, RefreshTokenRequest, UpdateUserRequest, ChangePasswordRequest)
├── dto/response/    (UserResponse, AuthResponse, RefreshTokenResponse)
├── repository/      (UserRepository)
├── service/impl/    (AuthServiceImpl, UserServiceImpl)
├── security/        (CustomUserDetailsService)
├── config/          (AppConfig, JwtConstant, JwtProvider, JwtTokenValidator, OpenApiConfig, SecurityConfig)
├── exception/       (AuthExceptionHandler)
└── controller/      (AuthController, UserController)
```

### 9.3 User Entity — القرارات النهائية
```java
@Entity @Table(name = "users")
public class User implements UserDetails {
    UUID id;
    String firstName, lastName, email, passwordHash;
    UserRole role;       // CUSTOMER | ADMIN — بدون ROLE_ prefix
    UserStatus status;   // PENDING_VERIFICATION | ACTIVE | SUSPENDED
    String verificationToken;
    LocalDateTime verificationTokenExpiry, createdAt, updatedAt;

    // UserDetails:
    getAuthorities() → SimpleGrantedAuthority(role.name())  // بدون ROLE_
    isEnabled()      → status == ACTIVE   // يمنع PENDING_VERIFICATION تلقائياً
    isAccountNonLocked() → status != SUSPENDED
}
```

**قرار Authority:** `hasAuthority("ADMIN")` لا `hasRole("ADMIN")` — لأن `hasRole` يضيف `ROLE_` prefix تلقائياً.

### 9.4 Email Verification
- `ConsoleEmailService` — يطبع الرابط بالـ Log فقط (يُستبدل بـ SMTP عند Docker Sprint).
- Token: `UUID.randomUUID()` — Single-use، صالح 24 ساعة.

### 9.5 JWT
- Access Token: 24h | Refresh Token: 7 أيام.
- Secret بـ `application.properties` قابل للاستبدال بـ `${JWT_SECRET:default}`.
- `JwtProvider` — المصدر الوحيد لمنطق التوقيع/التحقق.

### 9.6 Exception Handling
| common-lib `GlobalExceptionHandler` | auth-service `AuthExceptionHandler` |
|-------------------------------------|-------------------------------------|
| `MethodArgumentNotValidException` | `BadCredentialsException` → 401 |
| `ApiBaseException` | `DisabledException` → 403 |
| `PropertyReferenceException` → 400 | |
| `InvalidDataAccessApiUsageException` → 400 | |
| `Exception` (fallback → 500) | |

### 9.7 Endpoints (auth-service :7171)
```
POST   /api/auth/register
GET    /api/auth/verify-email?token=xxx    (Public)
POST   /api/auth/login
POST   /api/auth/refresh-token
GET    /api/auth/me

GET    /api/users/profile                  (CUSTOMER/ADMIN — authenticated)
PUT    /api/users/profile
PATCH  /api/users/change-password
DELETE /api/users/deactivate
GET    /api/users/                         (ADMIN only)
GET    /api/users/{userId}                 (ADMIN only)
PATCH  /api/users/{userId}/status          (ADMIN only)
```

**⚠️ Refactoring مؤجلة:**
- `PUT /profile` → الأصح `PATCH` (Partial Update فعلي).
- `DELETE /deactivate` → الأصح `POST` أو `PATCH` (Soft State Change).
- `/api/auth/me` و `/api/users/profile` متشابهان — يستحق التوحيد لاحقاً.

### 9.8 UserMapper — نمط Partial Update
`updateEntity(User, UpdateUserRequest)` — **ترجع void**، تعدّل Entity بالمرجع، تتحقق من `!= null` لكل حقل.

### 9.9 CORS
```java
config.setAllowedOriginPatterns(List.of("*"));  // ⚠️ يجب تضييقه قبل النشر
```

### 9.10 لا Multi-Tenancy
النظام يخدم شركة طيران واحدة. `airline_id` كـ FK عادي إذا احتيج مستقبلاً.

---

## 10. Sprint 2 — Flight Service

### 10.1 هيكل Packages (flight-service :7172)
```
com.project.flightservice/
├── entity/          (Flight, Airport, Aircraft, FareClass)
├── enums/           (FlightStatus, AirportStatus, AircraftStatus, FareClassType, Currency)
├── mapper/          (FlightMapper, FareClassMapper, AirportMapper, AircraftMapper)
├── dto/request/     (FlightRequest, FareClassRequest, UpdateFlightStatusRequest,
│                     AirportRequest, AircraftRequest)
├── dto/response/    (FlightResponse, FlightSearchResponse, AvailabilityResponse,
│                     FareClassResponse, AirportResponse, AircraftResponse)
├── repository/      (FlightRepository, AirportRepository, AircraftRepository, FareClassRepository)
├── service/impl/    (FlightServiceImpl, AirportServiceImpl, AircraftServiceImpl)
├── security/        (JwtValidator, JwtAuthenticationFilter,
│                     SecurityAuthEntryPoint, SecurityAccessDeniedHandler)
├── config/          (SecurityConfig, OpenApiConfig)
└── controller/      (FlightController, AirportController, AircraftController)
```

### 10.2 Entities — القرارات المهمة

**Flight:**
```java
UUID id; String flightNumber; // unique, uppercase
Airport originAirport, destinationAirport;
Aircraft aircraft;
LocalDateTime departureTime, arrivalTime;
FlightStatus status;   // SCHEDULED | DELAYED | CANCELLED | COMPLETED
@Version Long version; // Optimistic Locking لـ availableSeats
Set<FareClass> fareClasses;
```

**FareClass:**
```java
UUID id;
Flight flight;
FareClassType classType;   // ECONOMY | BUSINESS | FIRST
@Column(precision=10, scale=2) BigDecimal price;  // BigDecimal لا double
@Enumerated(EnumType.STRING) Currency currency;   // USD | JOD | EUR | GBP | SAR | AED
int totalSeats, availableSeats;
@Version Long version;  // Optimistic Locking حساس على availableSeats
```

**قرارات عامة:**
- `BigDecimal` لكل الأسعار (ليس `double`) — تجنباً لأخطاء الفاصلة العائمة.
- `@Enumerated(EnumType.STRING)` لكل الـ Enums — ليس `ORDINAL` (محمي من إعادة الترتيب).
- Constraint: `origin != destination` عبر `@AssertTrue` Validation.
- Constraint: `arrivalTime > departureTime` عبر Validation.
- `@Version` على `Flight` + `FareClass` — Optimistic Locking.

### 10.3 Endpoints (flight-service :7172)

**Flight Controller (`/api/flights`):**
```
POST   /                          → 201 ADMIN only — إنشاء رحلة
GET    /                          → 200 ADMIN only — كل الرحلات (Paginated)
GET    /{id}                      → 200 Public
GET    /search?origin&destination&date → 200 Public
GET    /{id}/availability         → 200 Public
PATCH  /{id}/status               → 200 ADMIN only
```

**Airport Controller (`/api/airports`):**
```
POST   /       → 201 ADMIN only
GET    /       → 200 ADMIN only
GET    /{id}   → 200 ADMIN only
PUT    /{id}   → 200 ADMIN only
DELETE /{id}   → 204 ADMIN only
```

**Aircraft Controller (`/api/aircraft`):**
```
POST   /       → 201 ADMIN only
GET    /       → 200 ADMIN only
GET    /{id}   → 200 ADMIN only
PUT    /{id}   → 200 ADMIN only
DELETE /{id}   → 204 ADMIN only
```

### 10.4 Pagination (GET /api/flights)
- `@ParameterObject @PageableDefault(size=20, sort="departureTime", direction=ASC) Pageable pageable`
- `@ParameterObject` من `springdoc-core` — يفكك الـ Pageable لـ 3 حقول منفصلة في Swagger (page, size, sort).
- Sort يُكتب كـ نص حر: `departureTime,asc` (بدون أقواس).
- الحقول الصالحة للـ sort: `departureTime`, `arrivalTime`, `flightNumber`, `status`.

### 10.5 Defense in Depth (Security)
كل خدمة تتحقق من JWT بشكل مستقل (لا تعتمد على الـ Gateway وحده):
```java
// JwtValidator.java — يستخدم نفس jwt.secret من application.properties
// JwtAuthenticationFilter — OncePerRequestFilter
// SecurityConfig — يطبق نفس rules بشكل صريح
// SecurityAuthEntryPoint — 401 JSON body (ليس HTML)
// SecurityAccessDeniedHandler — 403 JSON body (ليس HTML)
```

**ملاحظة مهمة:** 401/403 لا يُعالجان بـ `@ControllerAdvice` — يجب `AuthenticationEntryPoint` + `AccessDeniedHandler` منفصلين لأنهما يُطلقان قبل وصول الطلب للـ Controller.

**مشكلة JSON Serialization (محلولة):** `ApiResponse` يحتوي `HttpStatus status` (Spring enum) لا يُعرّفه Jackson العادي (`new ObjectMapper()`). الحل: كتابة JSON مباشرةً كـ String في الـ Handler بدلاً من استخدام ObjectMapper.

---

## 11. Sprint 3 — API Gateway

### 11.1 البنية (api-gateway :8080)
```
cloud/api-gateway/
├── filter/     (JwtAuthenticationFilter — GlobalFilter, Ordered(-1))
├── security/   (JwtValidator)
└── config/     (application.yml — routing)
```

### 11.2 Routing (application.yml)
```yaml
routes:
  - id: auth-service
    uri: http://localhost:7171
    predicates: [Path=/api/auth/**, /api/users/**]

  - id: flight-service
    uri: http://localhost:7172
    predicates: [Path=/api/flights/**, /api/airports/**, /api/aircraft/**]
```

### 11.3 منطق الـ Filter (JwtAuthenticationFilter)

**الـ Flow:**
1. Strip X-User-Id / X-User-Role من الـ Client (Anti-Spoofing).
2. إذا Public endpoint → forward مباشرة.
3. إذا لا يوجد JWT → 401.
4. إذا JWT غير صالح → 401.
5. إذا endpoint يتطلب ADMIN ودور المستخدم ليس ADMIN → 403.
6. أضف X-User-Id / X-User-Role (من JWT) → forward.

**Public Endpoints:**
```java
"/api/auth/register", "/api/auth/login", "/api/auth/refresh-token",
"/api/auth/verify-email", "/api/flights/search"
```

**منطق الـ Flights (مهم — ثغرة كانت موجودة وتم إصلاحها):**
```java
// GET /api/flights         → Admin-only (list كل الرحلات)
// GET /api/flights/{id}    → Public
// GET /api/flights/search  → Public (في القائمة أعلاه)
// POST/PUT/PATCH/DELETE /api/flights/** → Admin-only

private boolean isPublicEndpoint(String path, String method) {
    // ...
    if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/flights")) {
        String remainder = path.substring("/api/flights".length());
        if (remainder.isEmpty() || remainder.equals("/")) return false; // Admin-only list!
        return true; // /api/flights/{id} etc. are public
    }
}

private boolean requiresAdmin(String path, String method) {
    // ...
    if (path.startsWith("/api/flights")) {
        String remainder = path.substring("/api/flights".length());
        boolean isExactList = remainder.isEmpty() || remainder.equals("/");
        boolean isMutating = List.of("POST","PUT","PATCH","DELETE").contains(method.toUpperCase());
        return isMutating || (isExactList && "GET".equalsIgnoreCase(method));
    }
}
```

**Anti-Spoofing:** الـ Gateway يحذف `X-User-Id` و `X-User-Role` القادمين من العميل ثم يُعيد ضخّهم من الـ JWT الموثوق.

---

## 12. ما تم تحقيقه (Sprint 3 + نهاية Sprint 2)

✅ **مكتمل ومُختبر:**
- [x] API Gateway مع JWT Validation + Role Authorization
- [x] Anti-Spoofing (`X-User-*` headers)
- [x] Routing لـ auth-service و flight-service
- [x] Defense in Depth — flight-service يتحقق من JWT بشكل مستقل
- [x] 401/403 كـ JSON body (لا HTML من Tomcat)
- [x] إصلاح ثغرة: `GET /api/flights` كانت Public بالغلط → صارت Admin-only
- [x] `GET /api/flights` — Admin Paginated list مع `@ParameterObject`
- [x] `PropertyReferenceException` → 400 (ليس 500)
- [x] `InvalidDataAccessApiUsageException` → 400 (sort expression مشوّه من Swagger)

⚠️ **معلّق — لازم يُصلح قبل Docker/نشر فعلي:**
- [ ] CORS مفتوح `allowedOriginPatterns("*")` في auth-service — يجب تضييقه.
- [ ] `PUT /api/users/profile` → الأصح `PATCH` (Partial Update دلالياً).
- [ ] `DELETE /api/users/deactivate` → الأصح `POST` أو `PATCH` (Soft State Change).

---

## 13. Sprint 4 — Booking Service (الخطة النهائية المعتمدة)

### 13.1 المسؤوليات
**يملك:** دورة حياة الحجز، passenger data، booking snapshot، idempotency، expiration، cancellation.
**لا يملك:** availableSeats، Flight live data، Payment، User profile data.

### 13.2 القرارات النهائية المحسومة

| القرار | النتيجة |
|--------|---------|
| Seat endpoints | `POST /internal/fare-classes/{id}/reserve` + `/release` — Internal only |
| Service Auth | Service JWT `{sub: "booking-service", type: "SERVICE"}` — Flight يتحقق من الاثنين |
| Flight Down | 503، بدون Retry (non-idempotent — Retry يخصم مرتين) |
| Distributed TX | Best-effort compensation + CRITICAL structured log |
| Idempotency | `(userId, idempotencyKey)` UNIQUE + `requestHash` — يُطبّق الآن |
| Confirm | محذوف من Sprint 4 — يُبنى مع Payment Sprint |
| passengerCount | يُحسب `passengers.size()` — لا يُخزن (لا Data Redundancy) |
| Cancel | Idempotent — CANCELLED→CANCELLED = no-op |
| Expire | لا release مرة ثانية إذا الحجز EXPIRED بالفعل |
| Scheduler safety | لا يُعلم EXPIRED إلا بعد نجاح release |
| /internal/** | لا تُعرَّض بالـ Gateway — Booking يتصل مباشرة بـ Flight :7172 |
| REST Client | `RestClient` (Spring 6.1+) — لا Feign، لا Kafka |

### 13.3 Booking Lifecycle

```
POST /api/bookings
        ↓
   IN_PROGRESS (مؤقت)
        ↓
   reserve seats → Flight Service
   ┌────────────────────┐
   │                    │
 success              failure
   │                    │
   ↓                    ↓
 PENDING            503/409 (لا يُحفظ)
   │
   ├── CANCEL (user) ──────────→ CANCELLED → release seats
   │
   └── TTL 10min (scheduler) ──→ EXPIRED  → release seats
                                  (فقط بعد نجاح release)
```

### 13.4 Idempotency Design

```
نفس userId + نفس key + نفس requestHash → 200 + نفس الحجز
نفس userId + نفس key + requestHash مختلف → 409 Conflict
بدون Idempotency-Key → 400 Bad Request
userId مختلف + نفس key → طبيعي (namespace منفصل)
```

```sql
UNIQUE(user_id, idempotency_key) -- على جدول bookings
```

### 13.5 Service JWT Pattern

```java
// InternalTokenProvider.java — في Booking Service config/
// يولّد JWT بعمر دقيقة: {sub: "booking-service", type: "SERVICE"}
// يُستخدم في كل calls: create→reserve, cancel→release, scheduler→release

// Flight Service SecurityConfig:
// POST /internal/** → يتحقق: type==SERVICE AND sub==booking-service
// أي JWT آخر (CUSTOMER/ADMIN/SERVICE آخر) → 403
```

### 13.6 Distributed Transaction — Compensation Pattern

```java
// reserve seats (Flight)
//        ↓
// save Booking (booking_db)
//        ↓
// if save FAILS:
//   → release seats (compensating call)
//   → if release FAILS:
//       → log.error("CRITICAL: Seats reserved but booking failed. " +
//                   "fareClassId={}, count={}, timestamp={}", ...);
//       // لا حل تلقائي الآن — Outbox/Saga مع Kafka Sprint
```

### 13.7 Scheduler — قواعد صارمة

```java
@Scheduled(fixedDelay = 60_000)
// لكل PENDING حيث expiresAt < NOW():
//   1. release seats (Flight Service)
//   2. إذا نجح release → EXPIRED
//   3. إذا فشل release → يبقى PENDING + log error + retry next cycle
//   (لا نُعلم EXPIRED إلا بعد نجاح release — حماية المقاعد أهم)
```

### 13.8 منفذ وقاعدة البيانات
- Port: **7373**
- DB: `booking_db` (PostgreSQL منفصلة)

### 13.9 Entities

```java
// Booking
UUID id; UUID userId;                           // من JWT — لا FK
UUID flightId; UUID fareClassId;                // UUID references — لا FK
String flightNumber, originIata, destinationIata; // Snapshot
LocalDateTime departureTime, arrivalTime;       // Snapshot
String fareClassType;                           // Snapshot
BigDecimal priceAtBooking; Currency currency;   // Snapshot (يحمي السعر التاريخي)
BookingStatus status;  // IN_PROGRESS | PENDING | CANCELLED | EXPIRED
LocalDateTime expiresAt, createdAt, updatedAt;
String idempotencyKey, requestHash;             // Idempotency
List<BookingPassenger> passengers;
// COMPUTED: getPassengerCount() = passengers.size()

// BookingPassenger
UUID id; Booking booking;
String firstName, lastName, passportNumber, nationality;
LocalDate dateOfBirth;
```

### 13.10 BookingStatus Enum

```java
IN_PROGRESS,  // مؤقت
PENDING,      // مقاعد محجوزة — TTL 10min
CANCELLED,    // ألغاه المستخدم
EXPIRED       // انتهت TTL
// CONFIRMED → مع Payment Sprint
// REFUNDED  → مع Payment Sprint
```

### 13.11 Endpoints

| Method | Path | Access |
|--------|------|--------|
| POST | `/api/bookings` | CUSTOMER |
| GET | `/api/bookings/my` | CUSTOMER |
| GET | `/api/bookings/{id}` | OWNER / ADMIN |
| POST | `/api/bookings/{id}/cancel` | OWNER / ADMIN |
| GET | `/api/bookings` | ADMIN only |

Internal (لا Gateway routing):
- `POST /internal/fare-classes/{id}/reserve`
- `POST /internal/fare-classes/{id}/release`

### 13.12 ترتيب التنفيذ

```
1.  Flight Service: /internal endpoints + InternalSecurityConfig
2.  booking-service: pom.xml + application.properties (7373, booking_db)
3.  Enums: BookingStatus, Currency
4.  Entities: Booking, BookingPassenger
5.  Repository + Custom Queries
6.  DTOs: BookingRequest, PassengerRequest, BookingResponse, PassengerResponse
7.  Config: RestClientConfig, InternalTokenProvider
8.  FlightClient: getAvailability, reserveSeats, releaseSeats
9.  BookingServiceImpl: create, cancel, getById, getMyBookings
10. BookingCleanupJob (@Scheduled)
11. Security (نفس نمط flight-service)
12. BookingController
13. Gateway: إضافة /api/bookings/**
14. Testing
```

### 13.13 ما لن يُبنى في Sprint 4
`CONFIRMED`, Payment, Kafka, Redis, Saga, Circuit Breaker, Feign, Retry.
