package com.project.authservice.config;

import com.project.authservice.entity.User;
import com.project.authservice.enums.UserRole;
import com.project.authservice.enums.UserStatus;
import com.project.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String adminEmail = "admin@example.com";
        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = User.builder()
                    .firstName("System")
                    .lastName("Admin")
                    .email(adminEmail)
                    .passwordHash(passwordEncoder.encode("admin123456"))
                    .role(UserRole.ADMIN)
                    .status(UserStatus.ACTIVE)
                    .build();

            userRepository.save(admin);
            log.info("=== DEFAULT ADMIN CREATED ===");
            log.info("Email: {}", adminEmail);
            log.info("Password: admin123456");
            log.info("=============================");
        }
    }
}
