package com.memorylane.feature.memories.data;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface MemoryApi {
    @POST("api/v1/memories")
    Call<Memory> create(@Header("Authorization") String session,
                        @Header("Idempotency-Key") String key, @Body Draft draft);

    @GET("api/v1/memories")
    Call<Page> list(@Header("Authorization") String session, @Query("page") int page);

    /** Create body; Gson leaves out null emotion/location, which the server reads as "not given". */
    record Draft(String title, String description, String memoryDate, String category,
                 String emotion, Location location) {}

    record Location(double latitude, double longitude) {}

    record Memory(String id, String title, String description, String memoryDate) {}

    record Page(List<Memory> content, boolean hasNext) {}
}
