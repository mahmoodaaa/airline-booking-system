# خطة تنفيذ: Auth Service — المرحلة الثانية

## الوضع الحالي (ما اتبنى فعلاً)

| الملف | الحالة |
|-------|--------|
| `User.java` (Entity) | ✅ جاهز — فيه `verificationToken` + `verificationTokenExpiry` |
| `UserRepository.java` | ✅ جاهز — فيه `findByVerificationToken()` |
| `UserMapper.java` | ✅ جاهز |
| `DTOs` (Register, Login, RefreshToken, UpdateUser + Responses) | ✅ جاهزة |
| `JwtProvider.java` | ✅ جاهز — `generateToken()` + `generateRefreshToken()` |
| `JwtTokenValidator.java` | ✅ جاهز — Filter |
| `SecurityConfig.java` | ✅ جاهز |
| `CustomUserDetailsService.java` | ✅ جاهز — **مشكلة موجودة** (شوف تحت) |
| `AuthService.java` (Interface) | ✅ جاهز |
| `AuthServiceImpl.java` | ⏳ فاضية (return null) |
| `AuthController.java` | ❌ ما اتبنى بعد |
| `common-lib` exceptions | ✅ جاهزة (`ConflictException`, `RecordNotFoundException`, إلخ) |

---

## مشكلة موجودة يجب إصلاحها أولاً

> [!WARNING]
> **`CustomUserDetailsService`** فيها مشكلة: يعمل Cast مباشر `(UserDetails)` على الـ `User` entity — وهذا خطأ لأن `User` ما بتنفّذ `UserDetails` interface. رح يرمي `ClassCastException` عند أول Login.

**الحل:** `User` لازم تنفّذ `UserDetails` — أو نبني `CustomUserDetails` wrapper.
**القرار المُختار:** نخلّي `User` تنفّذ `UserDetails` مباشرة (أبسط وأنظف لمشروعنا).

> [!CAUTION]
> **في `AuthServiceImpl`** — فيه inject لـ `AuthService authService` بنفس الكلاس! هذا سبّب circular dependency. يجب حذفه فوراً.

---

## الخطة — 4 مراحل مرتّبة بالتسلسل

---

### المرحلة 1 — إصلاح ما قبل البناء (Prerequisites)

#### [MODIFY] [User.java](file:///c:/Users/USER/IdeaProjects/airline-booking-system/services/auth-service/src/main/java/com/project/authservice/entity/User.java)

`User` تنفّذ `UserDetails` — لازم تضيف هالـ methods:

```
implements UserDetails
─────────────────────────────────────────────
getAuthorities()     → List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
getPassword()        → return passwordHash
getUsername()        → return email
isAccountNonExpired() → true
isAccountNonLocked()  → true
isCredentialsNonExpired() → true
isEnabled()          → status == UserStatus.ACTIVE
```

**ليش `isEnabled()` مهمة جداً؟**
لأن Spring Security بتتحقق منها تلقائياً عند Login — إذا كانت `false` (المستخدم `PENDING_VERIFICATION`) بيرمي `DisabledException` وما بيكمل المصادقة. هذا هو الـ Guard الأساسي اللي يمنع مستخدم غير محقّق من الدخول.

#### [MODIFY] [AuthServiceImpl.java](file:///c:/Users/USER/IdeaProjects/airline-booking-system/services/auth-service/src/main/java/com/project/authservice/service/impl/AuthServiceImpl.java)

- حذف `@Autowired private AuthService authService` (circular dependency)
- إضافة inject لـ `JwtProvider` و `AuthenticationManager` و `JwtConstant`
- تحويل `@Autowired` → `@RequiredArgsConstructor` + `final fields`

---

### المرحلة 2 — تنفيذ AuthServiceImpl (القلب)

#### Method 1: `register(RegisterRequest request)`

```
Logic:
1. تحقق: هل الإيميل موجود مسبقاً؟ → ConflictException("Email already registered")
2. Encode الباسورد: passwordEncoder.encode(request.getPassword())
3. ولّد verificationToken: UUID.randomUUID().toString()
4. احسب verificationTokenExpiry: LocalDateTime.now().plusHours(24)
5. بنّي الـ User عبر userMapper.toEntity() ثم set الـ token والـ expiry
6. احفظ بقاعدة البيانات: userRepository.save(user)
7. طباعة التوكن باللوج (بديل الإيميل بالوقت الحالي):
   log.info("VERIFY EMAIL → http://localhost:7171/api/auth/verify-email?token={}", token)
8. رجّع: userMapper.toResponse(savedUser)
```

#### Method 2: `verifyEmail(String token)`

```
Logic:
1. دوّر المستخدم: userRepository.findByVerificationToken(token)
   → إذا ما وُجد: RecordNotFoundException("Invalid verification token")
2. تحقق من الصلاحية: هل verificationTokenExpiry قبل LocalDateTime.now()؟
   → إذا انتهت: BadRequestException("Verification token has expired")
3. غيّر status → ACTIVE
4. امسح الـ token (one-time use): user.setVerificationToken(null) + setVerificationTokenExpiry(null)
5. احفظ: userRepository.save(user)
```

#### Method 3: `login(LoginRequest request)`

```
Logic:
1. بنّي UsernamePasswordAuthenticationToken(email, password)
2. authenticationManager.authenticate(token)
   → Spring Security هي اللي تتحقق من الباسورد + تستدعي isEnabled() تلقائياً
   → BadCredentialsException إذا باسورد غلط
   → DisabledException إذا الحساب PENDING_VERIFICATION
3. ولّد accessToken: jwtProvider.generateToken(authentication)
4. ولّد refreshToken: jwtProvider.generateRefreshToken(authentication)
5. جلب User من DB: userRepository.findByEmail(email) لتعبئة UserResponse
6. رجّع AuthResponse{accessToken, refreshToken, expiresIn, user}
```

> [!NOTE]
> `expiresIn` = `jwtConstant.getAccessTokenExpiration() / 1000` (تحويل milliseconds → seconds للـ client)

#### Method 4: `refreshToken(RefreshTokenRequest request)`

```
Logic:
1. تحقق من انتهاء التوكن: jwtProvider.isTokenExpired(refreshToken)
   → إذا منتهي: UnauthorizedException("Refresh token has expired")
2. استخرج الإيميل من التوكن: jwtProvider.getEmailFromJwtToken(refreshToken)
3. جلب المستخدم من DB للتأكد من وجوده: userRepository.findByEmail(email)
   → RecordNotFoundException إذا ما وُجد
4. بنّي Authentication object جديد من بيانات المستخدم
5. ولّد accessToken جديد فقط
6. رجّع RefreshTokenResponse{accessToken, refreshToken(نفسه), expiresIn}
```

#### Method 5: `getUserByEmail(String email)`

```
Logic:
1. userRepository.findByEmail(email)
   → RecordNotFoundException("User not found")
2. رجّع userMapper.toResponse(user)
```

---

### المرحلة 3 — بناء AuthController

#### [NEW] [AuthController.java](file:///c:/Users/USER/IdeaProjects/airline-booking-system/services/auth-service/src/main/java/com/project/authservice/controller/AuthController.java)

```
@RestController
@RequestMapping("/api/auth")
─────────────────────────────────────────────────────────────
POST   /register          → register()       → 201 CREATED
POST   /login             → login()          → 200 OK
GET    /verify-email      → verifyEmail(?token=xxx) → 200 OK
POST   /refresh-token     → refreshToken()   → 200 OK
GET    /me                → getUserByEmail() → 200 OK   ← محمية بـ JWT
```

**ملاحظة مهمة على `/me`:**
تستخرج الإيميل من `SecurityContextHolder.getContext().getAuthentication().getName()` — المستخدم ما بيرسل الإيميل بنفسه، الـ JWT هو اللي يحمله.

---

### المرحلة 4 — معالجة Exceptions المفقودة

#### [MODIFY] [AuthExceptionHandler.java](file:///c:/Users/USER/IdeaProjects/airline-booking-system/services/auth-service/src/main/java/com/project/authservice/exception/AuthExceptionHandler.java)

إضافة handler لـ `DisabledException` (اللي بترميه Spring عندما `isEnabled() = false`):

```java
@ExceptionHandler(DisabledException.class)
→ 403 FORBIDDEN + "Account not verified. Please check your email."
```

---

## ترتيب التنفيذ الفعلي (الترتيب مهم جداً)

```
① User.java         implements UserDetails  (شرط لكل شي تاني)
② AuthServiceImpl   حذف circular + إضافة dependencies
③ AuthServiceImpl   تنفيذ register() + verifyEmail()
④ AuthServiceImpl   تنفيذ login() + refreshToken() + getUserByEmail()
⑤ AuthController    بناء الـ endpoints
⑥ AuthExceptionHandler   إضافة DisabledException handler
⑦ تشغيل + اختبار يدوي بـ Postman
```

---

## خارطة التبعيات (من يعتمد على من)

```
User (entity)
  └── implements UserDetails  ← CustomUserDetailsService يعتمد عليها
        └── SecurityConfig (DaoAuthenticationProvider)
              └── AuthServiceImpl.login() (AuthenticationManager.authenticate)

UserRepository ← AuthServiceImpl (جميع الـ methods)
JwtProvider    ← AuthServiceImpl (login + refreshToken)
JwtConstant    ← AuthServiceImpl (expiresIn calculation)
UserMapper     ← AuthServiceImpl (register + getUserByEmail)

AuthServiceImpl ← AuthController
```

---

## ما هو خارج النطاق الآن (مؤجل بوعي)

| الموضوع | السبب |
|---------|-------|
| SMTP / إرسال إيميل حقيقي | Docker Sprint — كلاس واحد بديل |
| `updateUser()` | ما في endpoint يستدعيها حالياً |
| Admin endpoints | Sprint منفصل |
| Rate Limiting على Login | Sprint Security المتقدم |
| Logout / Token Blacklisting | يحتاج Redis — Sprint لاحق |

---

## نقاط للمراجعة قبل التنفيذ

> [!IMPORTANT]
> هل قرار استخدام `ROLE_` prefix صحيح؟ في `SecurityConfig` الـ Authority بُنيت بـ `ADMIN` بدون prefix، لكن Spring Security convention يستخدم `ROLE_` مع `hasRole()`. نحن نستخدم `hasAuthority("ADMIN")` — يجب التوحيد: إما `ROLE_ADMIN` في كل مكان أو `ADMIN` في كل مكان.
> **القرار المقترح:** نبقى على `ADMIN` بدون prefix (لأن `SecurityConfig` والـ JWT مكتوبين هيك مسبقاً).

