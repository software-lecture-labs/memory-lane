package com.memorylane.feature.health.data;

/**
 * Mirrors backend HealthResponse: {"status":"UP","database":"UP"}. Gson maps fields by name,
 * so these names must stay equal to the JSON keys (and need keep rules if R8 is enabled).
 */
public final class HealthResponse {
    private String status;
    private String database;

    public HealthResponse() {
    }

    public HealthResponse(String status, String database) {
        this.status = status;
        this.database = database;
    }

    public String getStatus() {
        return status;
    }

    public String getDatabase() {
        return database;
    }
}
