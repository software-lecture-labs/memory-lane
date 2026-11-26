package com.memorylane.feature.memories.data;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Memory endpoints and bodies of docs/api-contract.md ("Anı endpointleri"). */
public interface MemoryApi {
    @POST("api/v1/memories")
    Call<Memory> create(@Header("Authorization") String session,
                        @Header("Idempotency-Key") String key, @Body Draft draft);

    @GET("api/v1/memories")
    Call<Page> list(@Header("Authorization") String session, @Query("page") int page);

    /** 200 MemoryDetail with a strong {@code ETag}; missing and foreign memories are both 404. */
    @GET("api/v1/memories/{id}")
    Call<Detail> detail(@Header("Authorization") String session, @Path("id") String id);

    /**
     * Replaces the writable fields. {@code ifMatch} is the detail's ETag exactly as received.
     * 200 current MemoryDetail (without ETag); 412 MEMORY_MODIFIED, 428 PRECONDITION_REQUIRED,
     * 400 INVALID_INPUT / INVALID_PRECONDITION, 404 MEMORY_NOT_FOUND.
     */
    @PUT("api/v1/memories/{id}")
    Call<Detail> update(@Header("Authorization") String session, @Path("id") String id,
                        @Header("If-Match") String ifMatch, @Body Draft draft);

    /**
     * Write body shared by POST and PUT. Null fields are left out of the JSON (Gson default), and
     * the server then applies its default: category OTHER, no emotion, no location. A PUT must
     * therefore send back the values it does not change, or they are cleared.
     *
     * @param category API code such as {@code TRAVEL}, never the Turkish label
     * @param emotion  API code such as {@code HAPPY}, or null
     */
    record Draft(String title, String description, String memoryDate, String category,
                 String emotion, Location location) {}

    record Location(double latitude, double longitude) {}

    /** MemorySummary: a list row; the create answer is read the same way. */
    record Memory(String id, String title, String description, String memoryDate, String category,
                  String emotion, Location location, String createdAt, String updatedAt,
                  int photoCount, String coverPhotoId) {}

    /** MemoryDetail: the summary fields plus the photos in {@code order ASC}. */
    record Detail(String id, String title, String description, String memoryDate, String category,
                  String emotion, Location location, String createdAt, String updatedAt,
                  int photoCount, String coverPhotoId, List<Photo> photos) {}

    /** PhotoResponse inside MemoryDetail; {@code contentUrl} is relative and needs the session. */
    record Photo(String id, String memoryId, int order, String contentType, long sizeBytes,
                 String createdAt, String contentUrl) {}

    record Page(List<Memory> content, boolean hasNext) {}
}
