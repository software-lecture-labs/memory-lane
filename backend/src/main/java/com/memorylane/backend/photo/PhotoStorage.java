package com.memorylane.backend.photo;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where photo bytes live. The service only knows this interface, so moving photos to another
 * store (object storage, a database table) means adding an implementation, not changing the
 * upload rules. Keys are generated here; client file names and paths are never used.
 */
public interface PhotoStorage {
    /** Saves the bytes under a new key and returns that key; nothing is visible under it until complete. */
    String store(byte[] bytes) throws IOException;

    /** Opens a stored photo; throws {@link java.nio.file.NoSuchFileException} if it is gone. */
    InputStream open(String key) throws IOException;

    /** Removes a stored photo; a key that is already gone is not an error. */
    void delete(String key) throws IOException;

    /**
     * Keys of photos written before {@code cutoff}. Used to find files that no photo row refers
     * to (ML-14); recent files are left out because an upload writes its file just before its row.
     */
    List<String> keysStoredBefore(Instant cutoff) throws IOException;

    /**
     * Removes writes that never completed (e.g. the process died mid-upload) and started before
     * {@code cutoff}. A file that cannot be removed is skipped and tried again on the next call.
     */
    int removeIncompleteWrites(Instant cutoff) throws IOException;

    /**
     * Moves a photo to quarantine instead of deleting it. A photo no row refers to may only look
     * orphaned because the backend is connected to the wrong, an empty or a restored database;
     * in quarantine it is kept for a while and can still be moved back by hand.
     */
    void quarantine(String key) throws IOException;

    /** Quarantined photos and when each was moved there. */
    Map<String, Instant> quarantined() throws IOException;

    /** Removes a quarantined photo for good; a key that is already gone is not an error. */
    void deleteQuarantined(String key) throws IOException;
}
