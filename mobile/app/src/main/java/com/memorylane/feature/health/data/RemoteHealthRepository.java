package com.memorylane.feature.health.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

public final class RemoteHealthRepository implements HealthRepository {
    private final HealthApi api;
    private final ApiCaller caller;

    public RemoteHealthRepository(HealthApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    @Override
    public Cancellable checkHealth(ResultCallback<HealthResponse> callback) {
        return caller.enqueue(api.getHealth(), callback);
    }
}
