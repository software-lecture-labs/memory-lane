package com.memorylane.backend.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Disk storage behaviour; uses a temporary directory, no database or Docker needed. */
class LocalPhotoStorageTest {
    @TempDir Path directory;

    @Test
    void storedBytesCanBeReadBackUnderAGeneratedKey() throws Exception {
        var storage = new LocalPhotoStorage(directory);
        String key = storage.store(PhotoFileTest.JPEG);

        assertEquals(key, UUID.fromString(key).toString());
        try (InputStream in = storage.open(key)) {
            assertArrayEquals(PhotoFileTest.JPEG, in.readAllBytes());
        }
    }

    @Test
    void everyUploadGetsItsOwnFileAndNoTemporaryFileIsLeft() throws Exception {
        var storage = new LocalPhotoStorage(directory);
        String first = storage.store(PhotoFileTest.JPEG);
        String second = storage.store(PhotoFileTest.JPEG);

        assertNotEquals(first, second);
        try (var files = Files.list(directory)) {
            assertEquals(List.of(first, second).stream().sorted().toList(),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void deleteRemovesTheFileAndToleratesAMissingOne() throws Exception {
        var storage = new LocalPhotoStorage(directory);
        String key = storage.store(PhotoFileTest.PNG);

        storage.delete(key);
        storage.delete(key);
        assertThrows(NoSuchFileException.class, () -> storage.open(key));
    }

    @Test
    void keysThatAreNotUuidsNeverReachTheFileSystem() throws Exception {
        // Storage root one level down so the file "outside" it is still inside the test's TempDir.
        var storage = new LocalPhotoStorage(directory.resolve("photos"));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "secret");

        assertThrows(IllegalArgumentException.class, () -> storage.open("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> storage.delete("../outside.txt"));
        assertTrue(Files.exists(outside));
    }

    @Test
    void createsTheDirectoryWhenItDoesNotExistYet() throws Exception {
        Path nested = directory.resolve("a").resolve("photos");
        new LocalPhotoStorage(nested).store(PhotoFileTest.JPEG);
        assertTrue(Files.isDirectory(nested));
    }
}
