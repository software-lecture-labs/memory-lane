package com.memorylane.backend.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps photos as files in one directory on the backend's disk (ADR-0002 assumes files that are
 * cleaned up after commit). With the shared Supabase database each developer's backend has its
 * own directory, so a photo row created on one machine has no file on another; see README.
 */
@Component
class LocalPhotoStorage implements PhotoStorage {
    private static final String TEMPORARY_PREFIX = "upload-";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path root;

    LocalPhotoStorage(@Value("${memorylane.photos.directory:storage/photos}") Path root) throws IOException {
        this.root = Files.createDirectories(root.toAbsolutePath().normalize());
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
                // Only names this class creates are photos; temporary uploads and anything else a
                // person put in the directory are never reported as photo keys.
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
                if (writtenBefore(path, cutoff) && Files.deleteIfExists(path)) removed++;
            }
        }
        return removed;
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
        UUID id = UUID.fromString(key);
        if (!id.toString().equals(key)) {
            throw new IllegalArgumentException("Invalid photo storage key");
        }
        return root.resolve(key);
    }
}
