package com.project.authservice.enums;

public enum UserRole {
    CUSTOMER("Customer - Can search flights, book, and manage own reservations"),
    ADMIN("Admin - Full system access, manages flights and bookings");

    private final String description;

    UserRole(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}