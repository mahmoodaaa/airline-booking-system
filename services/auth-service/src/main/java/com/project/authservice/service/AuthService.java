package com.project.authservice.service;

import com.project.authservice.dto.request.LoginRequest;
import com.project.authservice.dto.request.RefreshTokenRequest;
import com.project.authservice.dto.request.RegisterRequest;
import com.project.authservice.dto.response.AuthResponse;
import com.project.authservice.dto.response.RefreshTokenResponse;
import com.project.authservice.dto.response.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    void verifyEmail(String token);

    AuthResponse login(LoginRequest request);

    RefreshTokenResponse refreshToken(RefreshTokenRequest request);

    UserResponse getUserByEmail(String email);
}
