package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

public final class MemoryRepository {
    private final MemoryApi api;
    private final ApiCaller caller;

    public MemoryRepository(MemoryApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    public Cancellable save(String session, String key, MemoryApi.Draft draft,
                            ResultCallback<MemoryApi.Memory> callback) {
        return caller.enqueue(api.create(session, key, draft), callback);
    }

    public Cancellable list(String session, int page, ResultCallback<MemoryApi.Page> callback) {
        return caller.enqueue(api.list(session, page), callback);
    }
}
