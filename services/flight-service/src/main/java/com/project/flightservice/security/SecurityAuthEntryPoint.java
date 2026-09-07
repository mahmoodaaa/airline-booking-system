package com.project.flightservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles 401 Unauthorized responses when no valid JWT token is provided.
 * Returns a structured JSON response matching the ApiResponse format.
 */
@Component
public class SecurityAuthEntryPoint implements AuthenticationEntryPoint {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String json = "{" +
                "\"success\":false," +
                "\"message\":\"Unauthorized: Missing or invalid JWT token\"," +
                "\"data\":null," +
                "\"status\":\"UNAUTHORIZED\"," +
                "\"timestamp\":\"" + LocalDateTime.now().format(FORMATTER) + "\"" +
                "}";

        response.getWriter().write(json);
    }
}
