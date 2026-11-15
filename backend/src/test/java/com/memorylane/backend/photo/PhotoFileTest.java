package com.memorylane.backend.photo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** ML-09 file rules; no database or Docker needed. */
class PhotoFileTest {
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D};

    @Test
    void acceptsJpegAndPngByTheirOwnBytes() {
        assertEquals("image/jpeg", PhotoFile.validate(JPEG, "image/jpeg").contentType());
        assertEquals("image/png", PhotoFile.validate(PNG, "image/png").contentType());
    }

    @Test
    void declaredTypeIsComparedWithoutCaseOrParameters() {
        assertEquals("image/jpeg", PhotoFile.validate(JPEG, " Image/JPEG; charset=binary").contentType());
    }

    @Test
    void declaredTypeMustMatchTheContent() {
        assertRejected(415, "UNSUPPORTED_PHOTO_TYPE", () -> PhotoFile.validate(PNG, "image/jpeg"));
        assertRejected(415, "UNSUPPORTED_PHOTO_TYPE", () -> PhotoFile.validate(JPEG, null));
    }

    @Test
    void renamedTextFileIsNotAPhoto() {
        byte[] text = "not really a photo".getBytes(StandardCharsets.UTF_8);
        assertRejected(415, "UNSUPPORTED_PHOTO_TYPE", () -> PhotoFile.validate(text, "image/jpeg"));
    }

    @Test
    void otherImageTypesAreRejected() {
        byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
        assertRejected(415, "UNSUPPORTED_PHOTO_TYPE", () -> PhotoFile.validate(gif, "image/gif"));
    }

    @Test
    void emptyFileIsInvalidInput() {
        assertRejected(400, "INVALID_INPUT", () -> PhotoFile.validate(new byte[0], "image/jpeg"));
        assertRejected(400, "INVALID_INPUT", () -> PhotoFile.validate(null, "image/jpeg"));
    }

    @Test
    void limitIsFiveMillionBytesInclusive() {
        assertEquals(PhotoFile.MAX_BYTES, PhotoFile.validate(jpegOfSize(5_000_000), "image/jpeg").bytes().length);
        assertRejected(413, "FILE_TOO_LARGE", () -> PhotoFile.validate(jpegOfSize(5_000_001), "image/jpeg"));
    }

    @Test
    void digestDependsOnlyOnTheBytes() {
        assertEquals(PhotoFile.validate(JPEG, "image/jpeg").sha256(), PhotoFile.validate(JPEG.clone(), "image/JPEG").sha256());
        assertNotEquals(PhotoFile.validate(JPEG, "image/jpeg").sha256(), PhotoFile.validate(jpegOfSize(20), "image/jpeg").sha256());
        assertTrue(PhotoFile.validate(JPEG, "image/jpeg").sha256().matches("[0-9a-f]{64}"));
    }

    static byte[] jpegOfSize(int size) {
        byte[] bytes = Arrays.copyOf(JPEG, size);
        Arrays.fill(bytes, JPEG.length, size, (byte) 7);
        return bytes;
    }

    private static void assertRejected(int status, String code, Runnable action) {
        PhotoRejected error = assertThrows(PhotoRejected.class, action::run);
        assertEquals(status, error.status());
        assertEquals(code, error.code());
    }
}
