package com.memorylane.core.di;

import android.content.Context;

import com.google.gson.Gson;
import com.memorylane.MemoryLaneApp;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.feature.health.data.HealthApi;
import com.memorylane.feature.health.data.HealthRepository;
import com.memorylane.feature.health.data.RemoteHealthRepository;

import retrofit2.Retrofit;

/**
 * Manual dependency injection: the only place that knows concrete classes. Screens ask for
 * interfaces (e.g. {@link HealthRepository}) and stay unaware of Retrofit. Moving to Hilt later
 * only replaces this class; see docs/architecture.md "Mobil mimari".
 */
public final class AppContainer {
    private final String baseUrl;
    private final HealthRepository healthRepository;

    public AppContainer(String baseUrl) {
        Gson gson = new Gson();
        Retrofit retrofit = ApiClient.create(baseUrl, gson);
        ApiCaller caller = new ApiCaller(gson);
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
}
