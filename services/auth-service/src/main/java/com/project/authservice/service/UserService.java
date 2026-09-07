package com.project.authservice.service;

import com.project.authservice.dto.request.ChangePasswordRequest;
import com.project.authservice.dto.request.UpdateUserRequest;
import com.project.authservice.dto.response.UserResponse;
import com.project.authservice.entity.User;
import com.project.authservice.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

public interface UserService {

    UserResponse getCurrentUserProfile(User currentUser);

    UserResponse updateProfile(User currentUser, UpdateUserRequest request);

    void changePassword(User currentUser, ChangePasswordRequest request);

    void deactivateOwnAccount(User currentUser);

    @PreAuthorize("hasAuthority('ADMIN')")
    Page<UserResponse> getAllUsers(Pageable pageable);

    @PreAuthorize("hasAuthority('ADMIN')")
    UserResponse getUserById(UUID userId);

    @PreAuthorize("hasAuthority('ADMIN')")
    void updateUserStatus(UUID userId, UserStatus newStatus);

}
