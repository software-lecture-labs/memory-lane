package com.memorylane.backend.memory;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.memorylane.backend.auth.CurrentUser;
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
    private final MemoryService memories;
    private final Clock clock;

    /** The owner comes only from the session (SessionAuthenticationFilter + @CurrentUser, ADR-0003). */
    public MemoryController(MemoryService memories, Clock clock) {
        this.memories = memories;
        this.clock = clock;
    }

    record Detail(@JsonUnwrapped MemoryService.Summary summary, List<MemoryService.Photo> photos) {}

    @PostMapping
    ResponseEntity<Detail> create(
            @CurrentUser UUID owner,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String key,
            @RequestBody Map<String, Object> body) {
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
            @CurrentUser UUID owner,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "category", defaultValue = "") String category) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Sayfa veya sayfa boyutu geçersiz.");
        }
        if (!category.isEmpty()) MemoryInput.category(category);
        String term = MemoryInput.text(query.strip(), "", 100, "Arama");
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(memories.list(owner, page, size, term, category));
    }

    /** Contract: 200 MemoryDetail with a strong ETag; missing and foreign memories are both 404. */
    @GetMapping("/{id}")
    ResponseEntity<Detail> detail(@CurrentUser UUID owner, @PathVariable UUID id) {
        var found = memories.detail(owner, id);
        return ResponseEntity.ok().eTag(MemoryPrecondition.tag(id, found.version()))
                .header("Cache-Control", "no-store")
                .body(new Detail(found.summary(), found.photos()));
    }

    /**
     * Contract: same body rules as POST; If-Match is required. 200 with the current MemoryDetail
     * and no ETag (the body is normalised, so the next edit takes its tag from a detail GET).
     */
    @PutMapping("/{id}")
    ResponseEntity<Detail> update(
            @CurrentUser UUID owner,
            @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> body) {
        var updated = memories.update(owner, id, ifMatch, MemoryInput.from(body, clock));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Detail(updated.summary(), updated.photos()));
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
