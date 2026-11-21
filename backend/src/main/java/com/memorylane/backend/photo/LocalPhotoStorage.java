package com.memorylane.backend.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps photos as files in one directory on the backend's disk (ADR-0002 assumes files that are
 * cleaned up after commit). With the shared Supabase database each developer's backend has its
 * own directory, so a photo row created on one machine has no file on another; see README.
 * Photos the orphan sweep moves to quarantine live in its {@code quarantine} subdirectory.
 */
@Component
class LocalPhotoStorage implements PhotoStorage {
    private static final String TEMPORARY_PREFIX = "upload-";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private static final Logger log = LoggerFactory.getLogger(LocalPhotoStorage.class);

    private final Path root;
    private final Path quarantine;

    LocalPhotoStorage(@Value("${memorylane.photos.directory:storage/photos}") Path root) throws IOException {
        this.root = Files.createDirectories(root.toAbsolutePath().normalize());
        this.quarantine = Files.createDirectories(this.root.resolve("quarantine"));
    }

    @Override
    public String store(byte[] bytes) throws IOException {
        String key = UUID.randomUUID().toString();
        // Write under a temporary name first, then rename in one step: a crash or full disk
        // never leaves a half-written file under a real key.
        Path temporary = Files.createTempFile(root, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
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

    @Override
    public List<String> keysStoredBefore(Instant cutoff) throws IOException {
        List<String> keys = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path path : files) {
                // Only names this class creates are photos; temporary uploads, the quarantine
                // directory and anything else a person put here are never reported as photo keys.
                String name = path.getFileName().toString();
                if (isKey(name) && writtenBefore(path, cutoff)) keys.add(name);
            }
        }
        return keys;
    }

    @Override
    public int removeIncompleteWrites(Instant cutoff) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> temporary = Files.newDirectoryStream(root, TEMPORARY_PREFIX + "*" + TEMPORARY_SUFFIX)) {
            for (Path path : temporary) {
                try {
                    if (writtenBefore(path, cutoff) && Files.deleteIfExists(path)) removed++;
                } catch (IOException error) {
                    // One locked or unreadable file does not stop the others; it is tried again
                    // on the next sweep.
                    log.warn("Could not remove unfinished upload {}", path.getFileName(), error);
                }
            }
        }
        return removed;
    }

    @Override
    public void quarantine(String key) throws IOException {
        Path source = file(key);
        Path target = quarantined(key);
        // The quarantine lifetime counts from now, and a move keeps the modification time, so it
        // is set before moving. If it were set after, a failure there would leave the file in
        // quarantine with its old time and the next sweep could delete it at once. This way a
        // failed move only leaves the file looking new where it was; a later sweep tries again.
        Files.setLastModifiedTime(source, FileTime.from(Instant.now()));
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public Map<String, Instant> quarantined() throws IOException {
        Map<String, Instant> aside = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(quarantine)) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                if (!isKey(name)) continue;
                try {
                    aside.put(name, Files.getLastModifiedTime(path).toInstant());
                } catch (NoSuchFileException removedMeanwhile) {
                    // Restored or removed while listing.
                }
            }
        }
        return aside;
    }

    @Override
    public void deleteQuarantined(String key) throws IOException {
        Files.deleteIfExists(quarantined(key));
    }

    private static boolean writtenBefore(Path path, Instant cutoff) throws IOException {
        try {
            return Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (NoSuchFileException removedMeanwhile) {
            return false;
        }
    }

    private static boolean isKey(String name) {
        try {
            return UUID.fromString(name).toString().equals(name);
        } catch (IllegalArgumentException notAKey) {
            return false;
        }
    }

    // Keys come from our own database, but only accepting a UUID keeps any value like
    // "../.env" from ever reaching the file system.
    private Path file(String key) {
        return root.resolve(checked(key));
    }

    private Path quarantined(String key) {
        return quarantine.resolve(checked(key));
    }

    private static String checked(String key) {
        UUID id = UUID.fromString(key);
        if (!id.toString().equals(key)) {
            throw new IllegalArgumentException("Invalid photo storage key");
        }
        return key;
    }
}
