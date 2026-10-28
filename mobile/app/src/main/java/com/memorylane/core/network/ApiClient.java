package com.memorylane.core.network;

import com.google.gson.Gson;
import com.memorylane.core.config.ApiConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class ApiClient {
    private ApiClient() {
    }

    /**
     * One Retrofit instance for the whole app, rooted at the server address. Endpoint interfaces
     * carry the full path ({@code api/health}, later {@code api/v1/...}), matching the contract.
     */
    public static Retrofit create(String baseUrl, Gson gson) {
        // Short connect timeout so a stopped server or wrong address is reported quickly;
        // photo uploads (ML-03, 5 MB) may need their own longer write timeout later.
        // No HTTP logging interceptor on purpose: once login exists, Authorization headers and
        // password bodies must never reach logcat, not even in debug builds.
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        return new Retrofit.Builder()
                .baseUrl(ApiConfig.normalizeBaseUrl(baseUrl))
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
    }
}
