package com.memorylane.feature.photos.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/** The upload queue depends on this interface only, so its retry rules are tested without a server. */
public interface PhotoUploads {
    Cancellable upload(String session, String memoryId, PreparedPhoto photo,
                       ResultCallback<PhotoApi.Photo> callback);
}
