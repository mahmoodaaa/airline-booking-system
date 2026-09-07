package com.project.authservice.exception;


import com.project.common.exception.ErrorDetails;
import com.project.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

@ControllerAdvice
@Slf4j
public class AuthExceptionHandler {

    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    public ResponseEntity<ApiResponse<ErrorDetails>> handleAuthenticationException(
            Exception ex, WebRequest request) {

        ErrorDetails error = ErrorDetails.builder()
                .message("Invalid email or password")
                .path(request.getDescription(false).replace("uri=", ""))
                .exceptionType(ex.getClass().getSimpleName())
                .status(HttpStatus.UNAUTHORIZED)
                .build();

        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure(HttpStatus.UNAUTHORIZED, "Authentication failed", error));
    }

    /**
     * يُرمى عندما يحاول مستخدم PENDING_VERIFICATION الدخول.
     * Spring Security تستدعي isEnabled() تلقائياً — إذا false ترمي هاد الـ Exception.
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleDisabledException(
            DisabledException ex, WebRequest request) {

        ErrorDetails error = ErrorDetails.builder()
                .message("Account not verified. Please check your email and verify your account.")
                .path(request.getDescription(false).replace("uri=", ""))
                .exceptionType(ex.getClass().getSimpleName())
                .status(HttpStatus.FORBIDDEN)
                .build();

        log.warn("Login attempt on unverified account: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure(HttpStatus.FORBIDDEN, "Account not verified", error));
    }
}