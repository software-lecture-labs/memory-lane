package com.memorylane.core.di;

import android.content.Context;

import com.google.gson.Gson;
import com.memorylane.MemoryLaneApp;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.feature.health.data.HealthApi;
import com.memorylane.feature.health.data.HealthRepository;
import com.memorylane.feature.health.data.RemoteHealthRepository;

import com.memorylane.feature.auth.data.AuthApi;
import com.memorylane.feature.auth.data.AuthRepository;
import com.memorylane.feature.auth.data.RemoteAuthRepository;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryRepository;

import retrofit2.Retrofit;

/**
 * Manual dependency injection: the only place that knows concrete classes. Screens ask for
 * interfaces (e.g. {@link HealthRepository}) and stay unaware of Retrofit. Moving to Hilt later
 * only replaces this class; see docs/architecture.md "Mobil mimari".
 */
public final class AppContainer {
    private final String baseUrl;
    private final ApiCaller caller;
    private final AuthApi authApi;
    private final AuthRepository authRepository;
    private final MemoryRepository memoryRepository;
    private String token;
    private final HealthRepository healthRepository;

    public AppContainer(String baseUrl) {
        Gson gson = new Gson();
        Retrofit retrofit = ApiClient.create(baseUrl, gson);
        caller = new ApiCaller(gson);
        authApi = retrofit.create(AuthApi.class);
        authRepository = new RemoteAuthRepository(authApi, caller);
        memoryRepository = new MemoryRepository(retrofit.create(MemoryApi.class), caller);
        this.baseUrl = retrofit.baseUrl().toString();
        this.healthRepository = new RemoteHealthRepository(retrofit.create(HealthApi.class), caller);
    }

    public static AppContainer from(Context context) {
        return ((MemoryLaneApp) context.getApplicationContext()).getContainer();
    }

    /** Normalized server address, shown on screen to help diagnose emulator/phone setup. */
    public String getBaseUrl() {
        return baseUrl;
    }

    public HealthRepository getHealthRepository() {
        return healthRepository;
    }
    public AuthRepository getAuthRepository() { return authRepository; }

    public MemoryRepository getMemoryRepository() { return memoryRepository; }

    public String authorization() { return token == null ? null : "Bearer " + token; }

    public void startSession(String accessToken) { token = accessToken; }

    public void endSession() {
        String authorization = authorization();
        token = null;
        if (authorization != null) caller.enqueue(authApi.logout(authorization), result -> { });
    }

    public void clearSession() { token = null; }
}
