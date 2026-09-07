# Airline Booking System — Sprint 3: API Gateway

**Status:** Final Plan — قابل للتعديل أثناء التنفيذ
**Stack:** Java 21 + Spring Boot 4.1.0 + Spring Cloud Gateway (WebFlux) + JJWT 0.12.6

---

## 1. هدف Sprint 3

الـ Gateway هو نقطة الدخول الخارجية الوحيدة للنظام. مسؤولياته:

- استقبال طلبات الـ Client
- Routing إلى Auth / Flight / Booking
- التحقق من JWT (Validation فقط — لا يُصدر توكنات)
- تمييز الـ Endpoints العامة عن المحمية
- Authorization حسب الدور (Role)
- تمرير هوية المستخدم الموثوقة داخلياً (Trusted Headers)
- معالجة أخطاء مستوى الـ Gateway
- CORS مركزي
- تجهيز البنية لخدمات مستقبلية (Booking)

**لا يحتوي الـ Gateway على أي Business Logic** تخص Auth أو Flight أو Booking أو Payment.

---

## 2. البنية الحالية

```
Client / Frontend
       |
       v
+----------------------+
|     API Gateway      |
|        :8080         |
| Routing / JWT / CORS |
+----------+-----------+
           |
     +-----+-----+----------------+
     |           |                |
     v           v                v
 Auth :7171   Flight :7172   Booking :7173
```

| Service | Port |
|---------|------|
| Auth Service | 7171 |
| Flight Service | 7172 |
| Booking Service | 7173 (محجوز، لم يُبنَ بعد) |
| API Gateway | 8080 |

Auth و Flight منفَّذان بالفعل على المستوى الأساسي. Booking هو الخدمة الرئيسية التالية.

---

## 3. مراحل Sprint 3

1. Basic Gateway configuration
2. Routing
3. Public endpoints
4. JWT validation
5. Authentication filter
6. Role authorization
7. Trusted user headers (+ حماية من الانتحال)
8. Global Gateway error handling
9. CORS
10. Integration testing

---

## 4. Phase 1 — Basic Configuration

- **Port:** 8080
- **Main class:** `ApiGatewayApplication`
- **Configuration:** `src/main/resources/application.properties`
- **لا قاعدة بيانات للـ Gateway إطلاقاً.**

---

## 5. Phase 2 — Routing

**Auth:** `/api/auth/**` → `http://localhost:7171`
مثال: `POST /api/auth/login` عبر `http://localhost:8080/api/auth/login` → يُمرَّر لـ `http://localhost:7171/api/auth/login`

**Flight:** `/api/flights/**`, `/api/airports/**`, `/api/aircraft/**` → `http://localhost:7172`

**Booking (تحضير مسبق، الخدمة لم تُبنَ بعد):** `/api/bookings/**` → `http://localhost:7173`

نستخدم مبدئياً URLs مباشرة وثابتة. قرار الـ Service Discovery (Eureka) مؤجَّل لمرحلة لاحقة.

---

## 6. Public Endpoints (بدون JWT)

**Auth:**
```
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh-token
GET  /api/auth/verify-email
```

**Flight:**
```
GET /api/flights/search
GET /api/flights/{id}
GET /api/flights/{id}/availability
```

**السبب:** يجب أن يقدر المستخدم يبحث عن رحلات قبل تسجيل الدخول.

---

## 7. Protected Endpoints (JWT مطلوب)

```
POST   /api/bookings/**
GET    /api/bookings/**
PUT    /api/bookings/**
DELETE /api/bookings/**

POST   /api/airports/**    (ADMIN)
PUT    /api/airports/**    (ADMIN)
DELETE /api/airports/**    (ADMIN)

POST   /api/aircraft/**    (ADMIN)
PUT    /api/aircraft/**    (ADMIN)

POST   /api/flights/**     (ADMIN)
PUT    /api/flights/**     (ADMIN)
DELETE /api/flights/**     (ADMIN)

GET    /api/users/**       (Authenticated)
```

---

## 8. توزيع مسؤولية JWT

**Auth Service يملك:**
- التحقق من بيانات الدخول (Password verification)
- توليد JWT (`JwtProvider`)
- Refresh Tokens

**API Gateway يملك:**
- التحقق من JWT (`JwtValidator` — قراءة فقط)
- فحص انتهاء الصلاحية
- قراءة الـ Claims
- Authentication Filter + Authorization

**الـ Gateway لا يُصدر توكنات ولا يدير كلمات المرور إطلاقاً.**

---

## 9. تدفق JWT

**تسجيل الدخول:**
```
Client → POST /api/auth/login → Gateway (public route) → Auth Service → JWT → Client
```

**طلب محمي:**
```
Client → Authorization: Bearer <JWT> → Gateway (validate) → Target Service
```

**مثال Claims:**
```json
{
  "sub": "user-uuid",
  "role": "CUSTOMER",
  "iat": 1234567890,
  "exp": 1234569999
}
```

---

## 10. القرار المعماري: Defense in Depth

*(موثّق بالتفصيل في `PROJECT_CONTEXT.md` القسم 11)*

```
Client → Gateway (JWT validation — خط الدفاع الأول)
             → Internal Service (Service-level security — خط الدفاع الثاني)
                  → Database
```

**السبب:** الـ Gateway يوفر أول حد أمني، لكن الخدمات الداخلية ما لازم تعتمد بشكل أعمى على افتراض وحيد (أن كل طلب مرّ عبر الـ Gateway فعلاً). عادةً الـ Gateway وحده هو المكشوف للعالم الخارجي، لكن أثناء التطوير المحلي كل خدمة مكشوفة على بورتها — لذلك كل خدمة تتحقق من الـ JWT بنفسها أيضاً.

---

## 11. User Identity Headers (مع حماية من الانتحال)

بعد التحقق من JWT، الـ Gateway يمرر هوية موثوقة للخدمات الداخلية:
```
X-User-Id:   <user UUID>
X-User-Role: CUSTOMER
```

**⚠️ قاعدة أمنية حرجة (تعديل مضاف على الخطة الأصلية):**
هذه الـ Headers **موثوقة داخلياً فقط**، والعميل (Client) يجب ألا يقدر يتجاوز الـ Gateway ويحقن هويات مزيفة. لذلك، **قبل** أي معالجة أخرى، الـ Gateway **يجب أن يمسح (Strip) أي `X-User-Id`/`X-User-Role` قادمة أصلاً من الطلب الوارد من الـ Client**، ثم يضيف نسخته الموثوقة الخاصة به بعد التحقق من JWT بنجاح. بدون هذه الخطوة، أي عميل يقدر يبعث `X-User-Role: ADMIN` مباشرة بطلبه وينتحل صلاحية — راجع القسم 13 لموقع هذه الخطوة بالتدفق الدقيق.

---

## 12. هيكل الـ Packages

```
api-gateway
└── src/main
    ├── java/com/project/apigateway
    │   ├── ApiGatewayApplication.java
    │   ├── config/       (CorsConfig — ننشئ الكلاسات فقط عند الحاجة)
    │   ├── security/      (JwtValidator — منطق خام، سيُنسخ لاحقاً لـ Flight/Booking)
    │   ├── filter/        (JwtAuthenticationFilter — GlobalFilter)
    │   └── exception/     (GlobalErrorHandler)
    │
    └── resources/application.properties
```

الأسماء الدقيقة قد تتغيّر أثناء التنفيذ.

---

## 13. Request Filter Flow (محدَّث)

```
Incoming Request
       |
       v
Strip incoming X-User-Id / X-User-Role headers  ← خطوة مضافة، إلزامية أولاً
       |
       v
Is endpoint public?
   |           |
  YES          NO
   |           |
   v           v
Forward     Read JWT
               |
               v
          Validate JWT
           |       |
         Invalid   Valid
           |       |
           v       v
         401    Read claims
                   |
                   v
             Check role
               |     |
             Deny   Allow
               |     |
               v     v
             403   Add trusted
                    X-User-Id / X-User-Role
                       |
                       v
                    Forward
```

---

## 14. Authorization

**CUSTOMER يقدر:**
- Search flights / View flights
- Create own booking / View own bookings

**ADMIN يقدر:**
- Manage airports / aircraft / flights
- Manage bookings حسب Business Rules

مصفوفة الصلاحيات الدقيقة ستُصقَل أكثر عند بناء Booking Service.

---

## 15. CORS

Frontend متوقع: React/Next.js على `:3000` (تطوير). CORS يُضبط مركزياً بالـ Gateway فقط — يُزال من `auth-service` (كان موجوداً هناك مؤقتاً، الآن ينتقل لمكانه الصحيح). Origins الإنتاج ستختلف عن التطوير.

---

## 16. Error Handling

أخطاء مستوى الـ Gateway المتوقعة: `401`, `403`, `404`, `502 Bad Gateway`, `503 Service Unavailable`. نُبقيها بسيطة بهذا الـ Sprint، بدون Over-engineering. أخطاء البزنس تبقى مسؤولية كل خدمة لحالها.

---

## 17. Database

**API Gateway: لا قاعدة بيانات.** Database-per-service مستمر: `auth_db`, `flight_db`, `booking_db` (لاحقاً). الـ Gateway يبقى Stateless بالكامل.

---

## 18. ما يُمنع وضعه في الـ Gateway

```
❌ JPA entities          ❌ Repositories
❌ Database access        ❌ User creation
❌ Flight creation         ❌ Flight price calculation
❌ Seat holding            ❌ Booking creation
❌ Payment processing      ❌ Business rules
```

الـ Gateway بنية تحتية (Infrastructure) فقط.

---

## 19. Technologies

**Sprint 3:** Java 21 · Spring Boot 4.1.0 · Spring Cloud Gateway (Reactive WebFlux) · JJWT 0.12.6 · Lombok · Maven · JUnit

**لاحقاً (لا نضيفها الآن):** Docker · Eureka/Service Discovery · Redis · Kafka · Observability

لا نُدخل تقنيات مستقبلية قبل أن تحل حاجة فعلية حقيقية.

---

## 20. خطة الاختبار

| # | الاختبار | النتيجة المتوقعة |
|---|----------|-------------------|
| 1 | Gateway startup | يبدأ على `:8080` |
| 2 | Auth routing (`POST /api/auth/login`) | يصل لـ Auth Service |
| 3 | Flight routing (`GET /api/flights/search`) | يصل لـ Flight Service |
| 4 | Protected endpoint بدون JWT | `401 Unauthorized` |
| 5 | JWT غير صالح | `401 Unauthorized` |
| 6 | CUSTOMER يحاول يوصل لـ ADMIN endpoint | `403 Forbidden` |
| 7 | ADMIN token صالح | الطلب يوصل للخدمة الهدف |
| 8 *(مضاف)* | Client يبعث `X-User-Role: ADMIN` يدوياً بدون JWT صالح | الهيدر يُمسح، الطلب يُرفض أو يُعامل كـ Public/Unauthenticated |

---

## 21. ترتيب التنفيذ

```
STEP 1  → application.properties
STEP 2  → Gateway routes
STEP 3  → Test Auth route
STEP 4  → Test Flight route
STEP 5  → Add Booking route (تحضيري)
STEP 6  → JwtValidator (security/)
STEP 7  → Strip incoming X-User-* headers  ← مضافة، قبل الـ Authentication filter
STEP 8  → JwtAuthenticationFilter (filter/)
STEP 9  → Public/private endpoint rules
STEP 10 → Role authorization
STEP 11 → Add trusted X-User-Id / X-User-Role headers (بعد التحقق فقط)
STEP 12 → Error handling
STEP 13 → CORS
STEP 14 → Integration tests
```

هذا الترتيب يسهّل تتبع الأخطاء لأن الـ Routing يُتحقق منه قبل إضافة الأمان.

---

## 22. Definition of Done

- [ ] Gateway يبدأ على `8080`
- [ ] Auth routing يعمل
- [ ] Flight routing يعمل
- [ ] Booking routing جاهز (تحضيري)
- [ ] Public endpoints تعمل بدون JWT
- [ ] Protected endpoints تتطلب JWT
- [ ] JWT مفقود/غير صالح → `401`
- [ ] دور غير مصرح → `403`
- [ ] هوية المستخدم تُمرَّر داخلياً (Trusted Headers) **بعد** مسح أي هيدر مزيّف من العميل
- [ ] CORS مضبوط مركزياً (وأُزيل من auth-service)
- [ ] أخطاء الـ Gateway تُعالَج بشكل متسق
- [ ] لا قاعدة بيانات للـ Gateway
- [ ] لا Business Logic بالـ Gateway
- [ ] اختبارات Integration للتدفقات الرئيسية (شاملة اختبار انتحال الهوية #8)
- [ ] `PROJECT_CONTEXT.md` محدَّث بأي قرار جديد ظهر أثناء التنفيذ

---

## 23. خارج نطاق Sprint 3

```
❌ Eureka                 ❌ Config Server
❌ Redis                  ❌ Kafka
❌ Rate Limiting          ❌ Distributed Tracing
❌ Circuit Breaker        ❌ Load Balancing
❌ OAuth2 / Keycloak      ❌ Service Mesh
```

تُضاف في Sprints مستقبلية عند ظهور حاجة فعلية حقيقية.

---

## 24. Roadmap

```
Sprint 1 → Auth Service ✅
Sprint 2 → Flight Service ✅
Sprint 3 → API Gateway ⬅️ نحن هنا
Sprint 4 → Booking Service
Sprint 5 → Payment
Sprint 6 → Redis + Seat Hold
Sprint 7 → Kafka + Events
لاحقاً  → Docker / Discovery / Observability
```

---

## 25. Decisions Log

| # | القرار | الحالة | ملاحظة |
|---|--------|--------|--------|
| 001 | Gateway كنقطة دخول خارجية وحيدة | Accepted | — |
| 002 | Gateway يتحقق من JWT للطلبات المحمية | Accepted | — |
| 003 | Defense in Depth (Gateway + كل خدمة تتحقق بنفسها) | Accepted | راجع `PROJECT_CONTEXT.md` §11 |
| 004 | لا قاعدة بيانات للـ Gateway | Accepted | Stateless بالكامل |
| 005 | استخدام URLs مباشرة قبل Service Discovery | Accepted initially | Eureka لاحقاً عند الحاجة |
| 006 | تمرير Trusted Headers (`X-User-Id`, `X-User-Role`) | Accepted | **بشرط** مسح أي هيدر مماثل من العميل أولاً (§11, §13) |
| 007 | Java 21 (تصحيح) | Accepted | كانت مكتوبة سهواً Java 17 بمسودة أولى، صُححت لتطابق باقي المشروع |

---

## 26. Living Document Rule

هذا الملف وثيقة معمارية حيّة. عند تغيير أي قرار: حدّث القسم المعني، أضف سطراً جديداً بـ Decisions Log، ووضّح سبب التغيير. حافظ على تزامن التنفيذ الفعلي مع هذا الملف. الملف مرجع لنا وللـ AI Agents المستقبلية اللي ح تشتغل على المشروع.
