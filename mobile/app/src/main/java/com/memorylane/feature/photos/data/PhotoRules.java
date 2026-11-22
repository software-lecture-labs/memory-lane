package com.memorylane.feature.photos.data;

import androidx.annotation.Nullable;

/**
 * The ML-03 file rules as the phone applies them before uploading. The server checks the same
 * rules again; checking here first means a photo that would be rejected is fixed or refused on
 * the device instead of after a slow upload.
 */
public final class PhotoRules {
    /** 5 MB as written in the contract: 5,000,000 bytes, not 5 MiB. */
    public static final long MAX_BYTES = 5_000_000;
    public static final int MAX_PHOTOS_PER_MEMORY = 5;

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    private PhotoRules() {
    }

    /** Type from the file's first bytes, like the server does; {@code null} for anything else (HEIC, WebP, GIF…). */
    @Nullable
    public static String detect(byte[] head, int length) {
        if (length >= 3 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return JPEG;
        }
        if (length >= 8 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == '\r' && head[5] == '\n' && head[6] == 0x1A && head[7] == '\n') {
            return PNG;
        }
        return null;
    }

    /** JPEG and PNG within the limit are sent as they are; everything else is converted to JPEG once. */
    public static boolean needsConversion(@Nullable String detectedType, long sizeBytes) {
        return detectedType == null || sizeBytes > MAX_BYTES;
    }

    /**
     * Most pixels decoded at once: 16 MP is 64 MB as ARGB, which fits a phone's app memory next to
     * the scaled copy. A tiny file can claim huge dimensions (a "decompression bomb"), so the size
     * a file reports is never trusted on its own.
     */
    static final long MAX_DECODE_PIXELS = 16_000_000;

    /**
     * Power-of-two decode step that brings the longer side down to at most {@code maxSide}. Decoding
     * a 50-megapixel photo at full size would need hundreds of MB; this keeps conversion within a
     * phone's memory while staying sharper than what the app displays. The step is also raised
     * until the decoded image stays within {@link #MAX_DECODE_PIXELS}.
     */
    public static int sampleSize(int width, int height, int maxSide) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= maxSide
                || (long) (width / sample) * (height / sample) > MAX_DECODE_PIXELS) {
            sample *= 2;
        }
        return sample;
    }
}
