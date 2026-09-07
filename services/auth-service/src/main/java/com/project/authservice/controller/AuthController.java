package com.project.authservice.controller;

import com.project.authservice.dto.request.LoginRequest;
import com.project.authservice.dto.request.RefreshTokenRequest;
import com.project.authservice.dto.request.RegisterRequest;
import com.project.authservice.dto.response.AuthResponse;
import com.project.authservice.dto.response.RefreshTokenResponse;
import com.project.authservice.dto.response.UserResponse;
import com.project.authservice.service.AuthService;
import com.project.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Authentication and Authorization APIs (Public & Protected)")
public class AuthController {

    private final AuthService authService;

    // ─────────────────────────────────────────────
    // POST /api/auth/register
    // ─────────────────────────────────────────────
    @Operation(
            summary = "Register a new user",
            description = "🔑 **Access Level:** Public\n\nCreates a new user account. Generates a verification token which is printed to the logs (in Dev Mode). The account remains in `PENDING_VERIFICATION` status until verified."
    )
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        UserResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful. Please verify your email.", response));
    }

    // ─────────────────────────────────────────────
    // GET /api/auth/verify-email?token=xxx
    // ─────────────────────────────────────────────
    @Operation(
            summary = "Verify user email",
            description = "🔑 **Access Level:** Public\n\nActivates the account using the verification token. The token is expected as a query parameter (found in logs during registration)."
    )
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<String>> verifyEmail(
            @RequestParam("token") String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(
                ApiResponse.success("Email verified successfully. You can now login.", "OK"));
    }

    // ─────────────────────────────────────────────
    // POST /api/auth/login
    // ─────────────────────────────────────────────
    @Operation(
            summary = "Login user",
            description = "🔑 **Access Level:** Public\n\nAuthenticates a user and returns an Access Token and a Refresh Token. Returns `401 Unauthorized` for bad credentials, or `403 Forbidden` if the account is not verified."
    )
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    // ─────────────────────────────────────────────
    // POST /api/auth/refresh-token
    // ─────────────────────────────────────────────
    @Operation(
            summary = "Refresh Access Token",
            description = "🔑 **Access Level:** Public\n\nIssues a new Access Token using a valid Refresh Token. Does not require a JWT Authorization header."
    )
    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }

    // ─────────────────────────────────────────────
    // GET /api/auth/me   ← محمية بـ JWT
    // ─────────────────────────────────────────────
    @Operation(
            summary = "Get current user profile",
            description = "🔑 **Access Level:** Authenticated Users (Customer, Admin)\n\nFetches the profile details of the currently authenticated user. Requires `Authorization: Bearer <token>` header."
    )
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        UserResponse response = authService.getUserByEmail(email);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
