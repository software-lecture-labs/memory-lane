package com.memorylane.backend.photo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * An uploaded photo that passed the ML-03 file rules. The type is decided from the file's own
 * first bytes; the declared Content-Type must agree with it. The file name and extension sent by
 * the client are never looked at, so a renamed text file cannot pass as a JPEG.
 */
record PhotoFile(byte[] bytes, String contentType, String sha256) {
    /** 5 MB as written in the contract: 5,000,000 bytes, not 5 MiB. */
    static final long MAX_BYTES = 5_000_000;

    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    static PhotoFile validate(byte[] bytes, String declaredType) {
        if (bytes == null || bytes.length == 0) {
            throw PhotoRejected.invalid("Fotoğraf dosyası gönderilmedi.");
        }
        if (bytes.length > MAX_BYTES) {
            throw PhotoRejected.tooLarge();
        }
        String detected = detect(bytes);
        if (detected == null || !detected.equals(normalize(declaredType))) {
            throw PhotoRejected.unsupportedType();
        }
        return new PhotoFile(bytes, detected, sha256(bytes));
    }

    private static String detect(byte[] bytes) {
        if (startsWith(bytes, JPEG_SIGNATURE)) return JPEG;
        if (startsWith(bytes, PNG_SIGNATURE)) return PNG;
        return null;
    }

    // "image/jpeg; charset=..." or different letter case still names the same type.
    private static String normalize(String declaredType) {
        if (declaredType == null) return "";
        int parameters = declaredType.indexOf(';');
        String type = parameters < 0 ? declaredType : declaredType.substring(0, parameters);
        return type.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        return bytes.length >= signature.length
                && Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length);
    }

    // Same digest the retry rule compares: identical key and bytes replay, different bytes conflict.
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
