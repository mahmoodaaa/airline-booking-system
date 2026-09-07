package com.project.authservice.controller;

import com.project.authservice.dto.request.ChangePasswordRequest;
import com.project.authservice.dto.request.UpdateUserRequest;
import com.project.authservice.dto.response.UserResponse;
import com.project.authservice.entity.User;
import com.project.authservice.enums.UserStatus;
import com.project.authservice.service.UserService;
import com.project.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "User profile and Admin management APIs")
public class UserController {

    private final UserService userService;

    // ─────────────────────────────────────────────
    // Self-service (المستخدم يقيس حسابه)
    // ─────────────────────────────────────────────

    @Operation(
            summary = "Get current user profile",
            description = "🔑 **Access Level:** Authenticated Users (Customer, Admin)\n\nFetches the profile information of the currently authenticated user."
    )
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(@AuthenticationPrincipal User currentUser) {
        UserResponse response = userService.getCurrentUserProfile(currentUser);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Update current user profile",
            description = "🔑 **Access Level:** Authenticated Users (Customer, Admin)\n\nUpdates personal information (first name, last name) of the currently authenticated user."
    )
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody UpdateUserRequest request) {
        UserResponse response = userService.updateProfile(currentUser, request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", response));
    }

    @Operation(
            summary = "Change password",
            description = "🔑 **Access Level:** Authenticated Users (Customer, Admin)\n\nChanges the password of the currently authenticated user."
    )
    @PatchMapping("/change-password")
    public ResponseEntity<ApiResponse<String>> changePassword(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(currentUser, request);
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully", "OK"));
    }

    @Operation(
            summary = "Deactivate own account",
            description = "🔑 **Access Level:** Authenticated Users (Customer, Admin)\n\nDeactivates the account of the currently authenticated user."
    )
    @DeleteMapping("/deactivate")
    public ResponseEntity<ApiResponse<String>> deactivateAccount(@AuthenticationPrincipal User currentUser) {
        userService.deactivateOwnAccount(currentUser);
        return ResponseEntity.ok(ApiResponse.success("Account deactivated successfully", "OK"));
    }

    // ─────────────────────────────────────────────
    // Admin Management (خاص بالمسؤولين - Pagination)
    // ─────────────────────────────────────────────

    @Operation(
            summary = "Get all users (Paginated)",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves a paginated and sorted list of all users."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getAllUsers(
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<UserResponse> users = userService.getAllUsers(pageable);
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @Operation(
            summary = "Get user by ID",
            description = "🔑 **Access Level:** ADMIN only\n\nRetrieves details of a specific user by their UUID."
    )
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID userId) {
        UserResponse response = userService.getUserById(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Update user status",
            description = "🔑 **Access Level:** ADMIN only\n\nUpdates the account status (e.g., ACTIVE, SUSPENDED) of a specific user by UUID."
    )
    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<String>> updateUserStatus(
            @PathVariable UUID userId,
            @RequestParam UserStatus status) {
        userService.updateUserStatus(userId, status);
        return ResponseEntity.ok(ApiResponse.success("User status updated to " + status, "OK"));
    }
}
