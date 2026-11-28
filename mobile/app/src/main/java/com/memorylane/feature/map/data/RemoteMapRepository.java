package com.memorylane.feature.map.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

public final class RemoteMapRepository implements MapRepository {
    private final MapApi api;
    private final ApiCaller caller;

    public RemoteMapRepository(MapApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    @Override
    public Cancellable points(String authorization, ResultCallback<MapApi.Points> callback) {
        return caller.enqueue(api.points(authorization), callback);
    }
}
