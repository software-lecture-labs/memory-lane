package com.memorylane.feature.photos.data;

import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;

/** Photo upload from the ML-03 contract: one file per request, in the multipart part "file". */
public interface PhotoApi {
    @Multipart
    @POST("api/v1/memories/{memoryId}/photos")
    Call<Photo> upload(@Header("Authorization") String session,
                       @Header("Idempotency-Key") String key,
                       @Path("memoryId") String memoryId,
                       @Part MultipartBody.Part file);

    /** PhotoResponse; only the fields the upload screen needs are read. */
    record Photo(String id, String memoryId, int order, String contentType, long sizeBytes, String contentUrl) {}
}
