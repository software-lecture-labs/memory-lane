package com.memorylane.feature.health.data;

import retrofit2.Call;
import retrofit2.http.GET;

/** Infrastructure endpoint from ML-04; deliberately outside the /api/v1 business API. */
public interface HealthApi {
    @GET("api/health")
    Call<HealthResponse> getHealth();
}
