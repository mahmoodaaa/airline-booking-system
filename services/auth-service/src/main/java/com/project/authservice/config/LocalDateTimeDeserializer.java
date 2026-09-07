package com.project.authservice.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class LocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMATTER_WITH_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String dateString = p.getText();
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }

        dateString = dateString.trim();

        try {
            // Try to parse as date-only (YYYY-MM-DD) - convert to LocalDateTime at midnight
            if (dateString.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate date = LocalDate.parse(dateString, DATE_FORMATTER);
                return date.atStartOfDay();
            }
            // Try to parse as ISO datetime (YYYY-MM-DDTHH:mm:ss)
            else if (dateString.contains("T")) {
                try {
                    return LocalDateTime.parse(dateString, DATETIME_FORMATTER_WITH_MILLIS);
                } catch (DateTimeParseException e) {
                    return LocalDateTime.parse(dateString, DATETIME_FORMATTER);
                }
            }
            // Try ISO format without T
            else if (dateString.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                return LocalDateTime.parse(dateString, formatter);
            }
            // Default: try ISO_LOCAL_DATE_TIME
            else {
                return LocalDateTime.parse(dateString);
            }
        } catch (DateTimeParseException e) {
            throw new IOException("Unable to parse date: " + dateString + ". Supported formats: yyyy-MM-dd, yyyy-MM-ddTHH:mm:ss", e);
        }
    }
}

