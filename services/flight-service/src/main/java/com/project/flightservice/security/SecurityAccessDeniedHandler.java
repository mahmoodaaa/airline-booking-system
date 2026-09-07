package com.project.flightservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles 403 Forbidden responses when the authenticated user lacks the required role.
 * Returns a structured JSON response matching the ApiResponse format.
 */
@Component
public class SecurityAccessDeniedHandler implements AccessDeniedHandler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String json = "{" +
                "\"success\":false," +
                "\"message\":\"Forbidden: You do not have the required role to access this resource\"," +
                "\"data\":null," +
                "\"status\":\"FORBIDDEN\"," +
                "\"timestamp\":\"" + LocalDateTime.now().format(FORMATTER) + "\"" +
                "}";

        response.getWriter().write(json);
    }
}
