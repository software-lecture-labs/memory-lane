package com.memorylane.backend.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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
                    files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted().toList());
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
    void onlyOldPhotoFilesAreReportedAsKeys() throws Exception {
        var storage = new LocalPhotoStorage(directory);
        String old = storage.store(PhotoFileTest.JPEG);
        String recent = storage.store(PhotoFileTest.PNG);
        Path temporary = Files.write(directory.resolve("upload-123.tmp"), PhotoFileTest.JPEG);
        Path unrelated = Files.writeString(directory.resolve("notes.txt"), "not a photo");
        for (Path path : List.of(directory.resolve(old), temporary, unrelated)) age(path, Duration.ofHours(2));

        assertEquals(List.of(old), storage.keysStoredBefore(Instant.now().minus(Duration.ofHours(1))));
        assertTrue(Files.exists(directory.resolve(recent)));
    }

    @Test
    void onlyOldUnfinishedUploadsAreRemoved() throws Exception {
        var storage = new LocalPhotoStorage(directory);
        String photo = storage.store(PhotoFileTest.JPEG);
        Path abandoned = Files.write(directory.resolve("upload-1.tmp"), PhotoFileTest.JPEG);
        Path inProgress = Files.write(directory.resolve("upload-2.tmp"), PhotoFileTest.JPEG);
        age(abandoned, Duration.ofHours(2));
        age(directory.resolve(photo), Duration.ofHours(2));

        assertEquals(1, storage.removeIncompleteWrites(Instant.now().minus(Duration.ofHours(1))));
        assertFalse(Files.exists(abandoned));
        assertTrue(Files.exists(inProgress), "an upload still being written is left alone");
        assertTrue(Files.exists(directory.resolve(photo)), "completed photos are never touched");
    }

    private static void age(Path path, Duration by) throws Exception {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(by)));
    }

    @Test
    void createsTheDirectoryWhenItDoesNotExistYet() throws Exception {
        Path nested = directory.resolve("a").resolve("photos");
        new LocalPhotoStorage(nested).store(PhotoFileTest.JPEG);
        assertTrue(Files.isDirectory(nested));
    }
}
