package com.memorylane.feature.auth.data;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

/** Auth paths from docs/api-contract.md, relative to the shared server root. */
public interface AuthApi {
    @POST("api/v1/auth/register")
    Call<UserResponse> register(@Body AuthRequest request);

    @POST("api/v1/auth/login")
    Call<LoginResponse> login(@Body AuthRequest request);
}
