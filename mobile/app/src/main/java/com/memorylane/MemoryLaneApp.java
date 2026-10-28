package com.memorylane;

import android.app.Application;

import com.memorylane.core.di.AppContainer;

public class MemoryLaneApp extends Application {
    private AppContainer container;

    @Override
    public void onCreate() {
        super.onCreate();
        container = new AppContainer(BuildConfig.API_BASE_URL);
    }

    public AppContainer getContainer() {
        return container;
    }
}
