package com.memorylane.feature.photos.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public final class RemotePhotoUploads implements PhotoUploads {
    private final PhotoApi api;
    private final ApiCaller caller;

    public RemotePhotoUploads(PhotoApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    @Override
    public Cancellable upload(String session, String memoryId, PreparedPhoto photo,
                              ResultCallback<PhotoApi.Photo> callback) {
        // The body streams from the prepared copy, so a 5 MB photo is never held in memory twice.
        // The file name is fixed: the server ignores it and the user's own file name stays private.
        RequestBody bytes = RequestBody.create(photo.file(), MediaType.get(photo.contentType()));
        MultipartBody.Part part = MultipartBody.Part.createFormData("file", "photo", bytes);
        return caller.enqueue(api.upload(session, photo.key(), memoryId, part), callback);
    }
}
