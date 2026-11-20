package com.memorylane.feature.photos.data;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

/**
 * Copies a picked photo into the app's cache and, when it is not a JPEG/PNG within 5 MB (HEIC,
 * WebP, a large camera JPEG), re-encodes it once as JPEG. The copy is what every upload attempt
 * sends, so a retry repeats the exact bytes the server already hashed (ML-03, K2 review).
 */
public final class DevicePhotoPreparer implements PhotoPreparer {
    /** Longest side after conversion; sharper than any screen the app shows it on. */
    private static final int MAX_SIDE = 2560;
    /** A gallery item larger than this is not a photo the app should try to read into memory. */
    private static final long MAX_SOURCE_BYTES = 60_000_000;
    private static final int[] JPEG_QUALITIES = {90, 80, 70, 60};

    private final ContentResolver resolver;
    private final File directory;

    public DevicePhotoPreparer(ContentResolver resolver, File cacheDir) {
        this.resolver = resolver;
        // Cache, not files/: the system may clear it and it is never part of a backup.
        this.directory = new File(cacheDir, "photo-uploads");
    }

    @Override
    public PreparedPhoto prepare(Uri uri) throws PhotoPreparationException {
        File source = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create " + directory);
            }
            String name = UUID.randomUUID().toString();
            source = new File(directory, name + ".source");
            long size = copy(uri, source);
            byte[] head = head(source);
            String type = PhotoRules.detect(head, head.length);
            if (!PhotoRules.needsConversion(type, size)) {
                File ready = new File(directory, name + (PhotoRules.PNG.equals(type) ? ".png" : ".jpg"));
                Files.move(source.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                if (removeLocation(ready)) {
                    return new PreparedPhoto(UUID.randomUUID().toString(), ready, type);
                }
                // The metadata could not be rewritten safely; re-encoding drops it instead, so a
                // photo is never sent with its location. The copy is deleted in finally.
                source = ready;
            }
            // Its own name: the source may be the ".jpg" copy above, which finally deletes.
            File converted = new File(directory, name + "-converted.jpg");
            try {
                convertToJpeg(source, converted);
            } catch (IOException | PhotoPreparationException | OutOfMemoryError failed) {
                // A half-written copy of the user's photo must not stay behind in the cache.
                if (converted.exists() && !converted.delete()) converted.deleteOnExit();
                if (failed instanceof OutOfMemoryError) {
                    // Decoding is bounded (PhotoRules.sampleSize), but a phone low on memory can
                    // still refuse; that one photo is reported instead of the app crashing.
                    throw new PhotoPreparationException(PhotoPreparationException.Reason.UNSUPPORTED, failed);
                }
                throw failed;
            }
            return new PreparedPhoto(UUID.randomUUID().toString(), converted, PhotoRules.JPEG);
        } catch (IOException | SecurityException error) {
            throw new PhotoPreparationException(PhotoPreparationException.Reason.UNREADABLE, error);
        } finally {
            if (source != null && source.exists() && !source.delete()) {
                source.deleteOnExit();
            }
        }
    }

    @Override
    public void discard(PreparedPhoto photo) {
        // Only files this class created in its own cache directory are ever deleted.
        File file = photo.file();
        if (directory.equals(file.getParentFile()) && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private long copy(Uri uri, File target) throws IOException {
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("No stream for " + uri);
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = in.read(buffer)) != -1; ) {
                total += read;
                if (total > MAX_SOURCE_BYTES) throw new IOException("Source too large");
                out.write(buffer, 0, read);
            }
            return total;
        }
    }

    /**
     * Removes the GPS tags a camera writes into the photo. A memory has its own location field
     * (ML-03), chosen by the user; the photo's EXIF location is not used by any feature (EXIF is
     * out of scope in the README) and would only send where the user lives or was to the server.
     * Only GPS is removed; orientation and the other tags stay, so the photo still shows upright.
     * Done once before the first attempt, so every retry sends the same bytes.
     *
     * @return false when the file's metadata cannot be rewritten; the caller then re-encodes it.
     */
    private static boolean removeLocation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getPath());
            boolean found = false;
            for (String tag : GPS_TAGS) {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null);
                    found = true;
                }
            }
            if (found) exif.saveAttributes();
            return file.length() <= PhotoRules.MAX_BYTES;
        } catch (IOException | RuntimeException unreadableMetadata) {
            return false;
        }
    }

    private static final String[] GPS_TAGS = {
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    };

    // InputStream.readNBytes only exists from API 33; minSdk is 26.
    private static byte[] head(File file) throws IOException {
        byte[] head = new byte[8];
        int filled = 0;
        try (InputStream in = new FileInputStream(file)) {
            for (int read; filled < head.length && (read = in.read(head, filled, head.length - filled)) != -1; ) {
                filled += read;
            }
        }
        return Arrays.copyOf(head, filled);
    }

    private static void convertToJpeg(File source, File target) throws IOException, PhotoPreparationException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new PhotoPreparationException(PhotoPreparationException.Reason.UNSUPPORTED, null);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = PhotoRules.sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE);
        Bitmap decoded = BitmapFactory.decodeFile(source.getPath(), options);
        if (decoded == null) {
            throw new PhotoPreparationException(PhotoPreparationException.Reason.UNSUPPORTED, null);
        }
        // Re-encoding drops EXIF, including the orientation tag, so apply the rotation to the pixels;
        // otherwise a portrait photo would upload lying on its side. Location data is dropped too.
        Bitmap upright = scaleAndRotate(decoded, new ExifInterface(source.getPath()).getRotationDegrees());
        try {
            for (int quality : JPEG_QUALITIES) {
                try (OutputStream out = new FileOutputStream(target)) {
                    upright.compress(Bitmap.CompressFormat.JPEG, quality, out);
                }
                if (target.length() <= PhotoRules.MAX_BYTES) return;
            }
            throw new PhotoPreparationException(PhotoPreparationException.Reason.UNSUPPORTED, null);
        } finally {
            upright.recycle();
        }
    }

    // inSampleSize only halves, so the decoded image can still be up to twice MAX_SIDE; scale the
    // rest of the way here, in the same step as the rotation, to bound the output size.
    private static Bitmap scaleAndRotate(Bitmap bitmap, int degrees) {
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (degrees == 0 && scale == 1f) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }
}
