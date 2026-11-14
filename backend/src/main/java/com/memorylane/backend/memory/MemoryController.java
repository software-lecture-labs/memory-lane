package com.memorylane.backend.memory;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.memorylane.backend.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {
    private final AuthService auth;
    private final MemoryService memories;
    private final Clock clock;

    public MemoryController(AuthService auth, MemoryService memories, Clock clock) {
        this.auth = auth;
        this.memories = memories;
        this.clock = clock;
    }

    record Detail(@JsonUnwrapped MemoryService.Summary summary, List<MemoryService.Photo> photos) {}

    @PostMapping
    ResponseEntity<Detail> create(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String key,
            @RequestBody Map<String, Object> body) {
        UUID owner = auth.me(authorization).id();
        UUID requestId = requestId(key);
        var created = memories.create(owner, requestId, MemoryInput.from(body, clock));
        URI location = URI.create("/api/v1/memories/" + created.summary().id());
        var response = ResponseEntity.status(created.replayed() ? 200 : 201)
                .location(location).header("Cache-Control", "no-store");
        if (created.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(new Detail(created.summary(), created.photos()));
    }

    @GetMapping
    ResponseEntity<MemoryService.Page> list(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "category", defaultValue = "") String category) {
        UUID owner = auth.me(authorization).id();
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Sayfa veya sayfa boyutu geçersiz.");
        }
        if (!category.isEmpty()) MemoryInput.category(category);
        String term = MemoryInput.text(query.strip(), "", 100, "Arama");
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(memories.list(owner, page, size, term, category));
    }

    private static UUID requestId(String key) {
        try {
            UUID id = UUID.fromString(key);
            if (id.toString().equalsIgnoreCase(key)) return id;
        } catch (IllegalArgumentException ignored) {
            // The same response covers missing and malformed keys.
        }
        throw new IllegalArgumentException("Idempotency-Key geçerli bir UUID olmalıdır.");
    }
}
