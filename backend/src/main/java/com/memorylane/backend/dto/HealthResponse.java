package com.memorylane.backend.dto;

// Overall status mirrors the database until more components are checked.
public record HealthResponse(String status, String database) {
    public static HealthResponse of(boolean databaseUp) {
        String state = databaseUp ? "UP" : "DOWN";
        return new HealthResponse(state, state);
    }
}
