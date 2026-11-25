package com.memorylane.feature.photos.data;

import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Streaming;

/** Photo upload from the ML-03 contract: one file per request, in the multipart part "file". */
public interface PhotoApi {
    @Multipart
    @POST("api/v1/memories/{memoryId}/photos")
    Call<Photo> upload(@Header("Authorization") String session,
                       @Header("Idempotency-Key") String key,
                       @Path("memoryId") String memoryId,
                       @Part MultipartBody.Part file);

    /**
     * The photo's bytes (JPEG or PNG, at most 5,000,000). A fixed path instead of {@code @Url}
     * means the request always goes to the configured server, so {@code AuthInterceptor} adds the
     * Bearer token and a 401 ends the session; the token never ends up in a URL. Missing and
     * foreign photos are 404. The answer is {@code no-store}: callers keep it in memory only.
     */
    @Streaming
    @GET("api/v1/memories/{memoryId}/photos/{photoId}/content")
    Call<ResponseBody> downloadPhotoContent(@Path("memoryId") String memoryId, @Path("photoId") String photoId);

    /** PhotoResponse; only the fields the upload screen needs are read. */
    record Photo(String id, String memoryId, int order, String contentType, long sizeBytes, String contentUrl) {}
}
