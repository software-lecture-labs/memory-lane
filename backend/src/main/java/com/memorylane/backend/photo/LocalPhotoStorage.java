package com.memorylane.backend.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Keeps photos as files in one directory on the backend's disk (ADR-0002 assumes files that are
 * cleaned up after commit). With the shared Supabase database each developer's backend has its
 * own directory, so a photo row created on one machine has no file on another; see README.
 */
@Component
class LocalPhotoStorage implements PhotoStorage {
    private final Path root;

    LocalPhotoStorage(@Value("${memorylane.photos.directory:storage/photos}") Path root) throws IOException {
        this.root = Files.createDirectories(root.toAbsolutePath().normalize());
    }

    @Override
    public String store(byte[] bytes) throws IOException {
        String key = UUID.randomUUID().toString();
        // Write under a temporary name first, then rename in one step: a crash or full disk
        // never leaves a half-written file under a real key.
        Path temporary = Files.createTempFile(root, "upload-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file(key), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return key;
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(file(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(file(key));
    }

    // Keys come from our own database, but only accepting a UUID keeps any value like
    // "../.env" from ever reaching the file system.
    private Path file(String key) {
        UUID id = UUID.fromString(key);
        if (!id.toString().equals(key)) {
            throw new IllegalArgumentException("Invalid photo storage key");
        }
        return root.resolve(key);
    }
}
