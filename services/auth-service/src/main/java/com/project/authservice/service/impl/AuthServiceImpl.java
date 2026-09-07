package com.project.authservice.service.impl;

import com.project.authservice.config.JwtConstant;
import com.project.authservice.config.JwtProvider;
import com.project.authservice.dto.request.LoginRequest;
import com.project.authservice.dto.request.RefreshTokenRequest;
import com.project.authservice.dto.request.RegisterRequest;
import com.project.authservice.dto.response.AuthResponse;
import com.project.authservice.dto.response.RefreshTokenResponse;
import com.project.authservice.dto.response.UserResponse;
import com.project.authservice.entity.User;
import com.project.authservice.enums.UserStatus;
import com.project.authservice.mapper.UserMapper;
import com.project.authservice.repository.UserRepository;
import com.project.authservice.service.AuthService;
import com.project.common.exception.BadRequestException;
import com.project.common.exception.ConflictException;
import com.project.common.exception.RecordNotFoundException;
import com.project.common.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final JwtProvider jwtProvider;
    private final AuthenticationManager authenticationManager;
    private final JwtConstant jwtConstant;

    // ─────────────────────────────────────────────
    // 1. Register
    // ─────────────────────────────────────────────

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {

        // تحقق: هل الإيميل مستخدم مسبقاً؟
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email already registered: " + request.getEmail());
        }

        // Encode الباسورد
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // بنّي الـ User عبر الـ Mapper
        User user = userMapper.toEntity(request, encodedPassword);

        // ولّد رمز التحقق (one-time use UUID token)
        String verificationToken = UUID.randomUUID().toString();
        user.setVerificationToken(verificationToken);
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));

        // احفظ بقاعدة البيانات
        User savedUser = userRepository.saveAndFlush(user);
        // بديل الإيميل الحقيقي — طباعة الرابط باللوج (يُستبدل بـ SMTP عند Docker Sprint)
        log.info("=== EMAIL VERIFICATION (Dev Mode) ===");
        log.info("VERIFY EMAIL → http://localhost:7171/api/auth/verify-email?token={}", verificationToken);
        log.info("=====================================");

        return userMapper.toResponse(savedUser);
    }

    // ─────────────────────────────────────────────
    // 2. Verify Email
    // ─────────────────────────────────────────────

    @Override
    @Transactional
    public void verifyEmail(String token) {

        // دوّر المستخدم بالرمز
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new RecordNotFoundException("Invalid verification token"));

        // تحقق من صلاحية التوكن (لم تنته الـ 24 ساعة)
        if (user.getVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Verification token has expired. Please register again.");
        }

        // فعّل الحساب
        user.setStatus(UserStatus.ACTIVE);
        // امسح الرمز (one-time use — لا يُعاد استخدامه)
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);

        userRepository.save(user);

        log.info("Email verified successfully for: {}", user.getEmail());
    }

    // ─────────────────────────────────────────────
    // 3. Login
    // ─────────────────────────────────────────────

    @Override
    public AuthResponse login(LoginRequest request) {

        // Spring Security تتحقق من الباسورد + isEnabled() تلقائياً
        // → BadCredentialsException إذا الباسورد غلط
        // → DisabledException إذا الحساب PENDING_VERIFICATION
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String accessToken = jwtProvider.generateToken(authentication);
        String refreshToken = jwtProvider.generateRefreshToken(authentication);
        long expiresIn = jwtConstant.getAccessTokenExpiration() / 1000;

        // بدون query إضافي - الـ User أصلاً موجود بالـ principal
        User user = (User) authentication.getPrincipal();

        log.info("User logged in: {}", request.getEmail());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(expiresIn)
                .user(userMapper.toResponse(user))
                .build();
    }

    // ─────────────────────────────────────────────
    // 4. Refresh Token
    // ─────────────────────────────────────────────

    @Override
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {

        String refreshToken = request.getRefreshToken();

        try {
            if (jwtProvider.isTokenExpired(refreshToken)) {
                throw new UnauthorizedException("Refresh token has expired. Please login again.");
            }

            String email = jwtProvider.getEmailFromJwtToken(refreshToken);
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RecordNotFoundException("User not found: " + email));

            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    user, null, user.getAuthorities()
            );

            String newAccessToken = jwtProvider.generateToken(authentication);
            long expiresIn = jwtConstant.getAccessTokenExpiration() / 1000;

            log.debug("Access token refreshed for: {}", email);

            return RefreshTokenResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(refreshToken)
                    .expiresIn(expiresIn)
                    .build();

        } catch (io.jsonwebtoken.JwtException e) {
            throw new UnauthorizedException("Invalid refresh token");
        }
    }

    // ─────────────────────────────────────────────
    // 5. Get User By Email
    // ─────────────────────────────────────────────

    @Override
    public UserResponse getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RecordNotFoundException("User not found: " + email));
        return userMapper.toResponse(user);
    }
}
