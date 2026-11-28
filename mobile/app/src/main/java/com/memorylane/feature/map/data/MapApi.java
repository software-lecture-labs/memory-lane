package com.memorylane.feature.map.data;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;

/** The map endpoint of docs/api-contract.md ("Harita"). */
public interface MapApi {
    /**
     * Located memories of the session user, newest first. Not paged: at most {@code limit}
     * points, and {@code truncated} says whether more matched. 401 INVALID_SESSION.
     */
    @GET("api/v1/memories/map")
    Call<Points> points(@Header("Authorization") String session);

    /** One pin. Coordinates are boxed so a point the server sent without them can be rejected. */
    record Point(String id, String title, String memoryDate, Double latitude, Double longitude,
                 String coverPhotoId) {}

    record Points(List<Point> points, int limit, int returned, boolean truncated) {}
}
