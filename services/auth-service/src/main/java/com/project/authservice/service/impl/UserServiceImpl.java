package com.project.authservice.service.impl;

import com.project.authservice.dto.request.ChangePasswordRequest;
import com.project.authservice.dto.request.UpdateUserRequest;
import com.project.authservice.dto.response.UserResponse;
import com.project.authservice.entity.User;
import com.project.authservice.enums.UserStatus;
import com.project.authservice.mapper.UserMapper;
import com.project.authservice.repository.UserRepository;
import com.project.authservice.service.UserService;
import com.project.common.exception.BadRequestException;
import com.project.common.exception.RecordNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    // ─────────────────────────────────────────────
    // Self-service
    // ─────────────────────────────────────────────

    @Override
    public UserResponse getCurrentUserProfile(User currentUser) {
        return userMapper.toResponse(currentUser);
    }

    @Override
    @Transactional
    public UserResponse updateProfile(User currentUser, UpdateUserRequest request) {
        userMapper.updateEntity(currentUser, request);
        User saved = userRepository.save(currentUser);

        log.info("Profile updated for user: {}", currentUser.getEmail());
        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void changePassword(User currentUser, ChangePasswordRequest request) {

        // تحقق: هل كلمة المرور الحالية صحيحة فعلاً؟
        if (!passwordEncoder.matches(request.getCurrentPassword(), currentUser.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        // تحقق: كلمة المرور الجديدة لازم تختلف عن القديمة
        if (passwordEncoder.matches(request.getNewPassword(), currentUser.getPasswordHash())) {
            throw new BadRequestException("New password must be different from the current password");
        }

        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(currentUser);

        log.info("Password changed for user: {}", currentUser.getEmail());

        // ملاحظة مستقبلية: هون بالضبط المكان اللي رح نضيف فيه لاحقاً
        // إبطال كل الـ Refresh Tokens القديمة (يحتاج Redis - مؤجل حسب Roadmap)
    }

    @Override
    @Transactional
    public void deactivateOwnAccount(User currentUser) {
        currentUser.setStatus(UserStatus.SUSPENDED);
        userRepository.save(currentUser);

        log.info("Account deactivated by user: {}", currentUser.getEmail());
    }

    // ─────────────────────────────────────────────
    // Admin only
    // ─────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> getAllUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return userPage.map(userMapper::toResponse);
    }

    @Override
    public UserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RecordNotFoundException("User not found with id: " + userId));
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void updateUserStatus(UUID userId, UserStatus newStatus) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RecordNotFoundException("User not found with id: " + userId));

        user.setStatus(newStatus);
        userRepository.save(user);

        log.info("Admin updated status of user {} to {}", user.getEmail(), newStatus);
    }
}