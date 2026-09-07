package com.project.authservice.dto.response;

import com.project.authservice.enums.UserRole;
import com.project.authservice.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private UserRole role;      // بدل String
    private UserStatus status;  // بدل String
    private LocalDateTime createdAt;
}