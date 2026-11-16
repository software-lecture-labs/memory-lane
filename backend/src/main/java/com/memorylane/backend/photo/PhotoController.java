package com.memorylane.backend.photo;

import com.memorylane.backend.auth.CurrentUser;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Photo endpoints from the ML-03 contract; one file per upload request, in the "file" part. */
@RestController
@RequestMapping("/api/v1/memories/{memoryId}/photos")
public class PhotoController {
    private final PhotoService photos;

    public PhotoController(PhotoService photos) {
        this.photos = photos;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<PhotoService.Photo> upload(
            @CurrentUser UUID user,
            @PathVariable UUID memoryId,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String key,
            @RequestParam(name = "file", required = false) MultipartFile file) throws IOException {
        UUID requestKey = requestKey(key);
        if (file == null) {
            throw PhotoRejected.invalid("Fotoğraf dosyası gönderilmedi.");
        }
        // Only the bytes and the declared type are used; file.getOriginalFilename() is ignored on purpose.
        var uploaded = photos.upload(user, memoryId, requestKey, file.getBytes(), file.getContentType());
        var photo = uploaded.photo();
        var response = ResponseEntity.status(uploaded.replayed() ? 200 : 201)
                .location(URI.create(photo.contentUrl()))
                .header("Cache-Control", "no-store");
        if (uploaded.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(photo);
    }

    @GetMapping
    ResponseEntity<List<PhotoService.Photo>> list(@CurrentUser UUID user, @PathVariable UUID memoryId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(photos.list(user, memoryId));
    }

    @GetMapping("/{photoId}/content")
    ResponseEntity<InputStreamResource> content(
            @CurrentUser UUID user, @PathVariable UUID memoryId, @PathVariable UUID photoId) throws IOException {
        var content = photos.content(user, memoryId, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .header("Cache-Control", "no-store")
                // The stored type was checked against the file's bytes; stop browsers guessing another one.
                .header("X-Content-Type-Options", "nosniff")
                // User-uploaded bytes: even if a crafted file is opened in a browser, it may not run
                // scripts or load anything (OWASP file upload guidance). The mobile client ignores this.
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(new InputStreamResource(content.stream()));
    }

    // Same rule and message as memory creation: missing and malformed keys get one answer.
    private static UUID requestKey(String key) {
        try {
            UUID id = UUID.fromString(key);
            if (id.toString().equalsIgnoreCase(key)) return id;
        } catch (IllegalArgumentException ignored) {
            // Falls through to the shared error below.
        }
        throw PhotoRejected.invalid("Idempotency-Key geçerli bir UUID olmalıdır.");
    }
}
