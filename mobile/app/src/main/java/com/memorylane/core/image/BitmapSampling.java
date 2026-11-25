package com.memorylane.core.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes downloaded photo bytes at the size a screen shows them, never at full resolution: the
 * bounds are read first, then the image is decoded with a power-of-two {@code inSampleSize}.
 * A 12 MP photo shown as a 360 px card thus costs about 1 MB instead of 48 MB.
 *
 * <p>JPEGs uploaded unchanged keep their EXIF orientation (docs/architecture.md, ML-10), and
 * {@link BitmapFactory} ignores it, so the rotation is applied here. No other EXIF data is read.
 */
public final class BitmapSampling {
    /**
     * Most pixels decoded at once, as for uploads (PhotoRules): a tiny file may claim huge
     * dimensions, so its reported size is never trusted on its own.
     */
    static final long MAX_DECODE_PIXELS = 16_000_000;

    private BitmapSampling() {
    }

    /**
     * Largest power of two that keeps the decoded image at least {@code reqWidth} x
     * {@code reqHeight} (so it stays sharp), raised further until it fits {@link #MAX_DECODE_PIXELS}.
     *
     * @throws IllegalArgumentException if any size is zero or negative
     */
    static int inSampleSize(int width, int height, int reqWidth, int reqHeight) {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) {
            throw new IllegalArgumentException("Image and target sizes must be positive");
        }
        int sample = 1;
        while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
            sample *= 2;
        }
        while ((long) (width / sample) * (height / sample) > MAX_DECODE_PIXELS) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Decodes {@code bytes} for a {@code reqWidth} x {@code reqHeight} view, upright.
     *
     * @return null for an empty, unreadable or too large image, or when memory runs out; the
     *     caller shows its placeholder instead of crashing
     */
    @WorkerThread
    @Nullable
    public static Bitmap decode(@Nullable byte[] bytes, int reqWidth, int reqHeight) {
        if (bytes == null || bytes.length == 0 || reqWidth <= 0 || reqHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;

        int degrees = rotationDegrees(bytes);
        // A quarter turn swaps the sides, so compare the stored image with the target turned too.
        boolean sideways = degrees == 90 || degrees == 270;
        options.inSampleSize = inSampleSize(options.outWidth, options.outHeight,
                sideways ? reqHeight : reqWidth, sideways ? reqWidth : reqHeight);
        options.inJustDecodeBounds = false;
        try {
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            return decoded == null || degrees == 0 ? decoded : rotate(decoded, degrees);
        } catch (OutOfMemoryError error) {
            return null;
        }
    }

    private static int rotationDegrees(byte[] bytes) {
        try {
            return new ExifInterface(new ByteArrayInputStream(bytes)).getRotationDegrees();
        } catch (IOException | RuntimeException unreadable) {
            return 0; // PNGs and files without EXIF are shown as stored
        }
    }

    private static Bitmap rotate(Bitmap bitmap, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }
}
