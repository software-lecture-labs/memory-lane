package com.memorylane.backend.controller;

import com.memorylane.backend.dto.HealthResponse;
import com.memorylane.backend.service.DatabaseHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final DatabaseHealthService databaseHealthService;

    public HealthController(DatabaseHealthService databaseHealthService) {
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        boolean databaseUp = databaseHealthService.isDatabaseUp();
        HttpStatus status = databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(HealthResponse.of(databaseUp));
    }
}
