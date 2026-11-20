package com.memorylane.feature.photos.data;

import android.net.Uri;

/** Turns a photo picked from the gallery into an upload-ready private copy; blocking, call off the main thread. */
public interface PhotoPreparer {
    PreparedPhoto prepare(Uri uri) throws PhotoPreparationException;

    /** Deletes the private copy. Never touches the gallery original. */
    void discard(PreparedPhoto photo);
}
