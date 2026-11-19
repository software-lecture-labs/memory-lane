package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/** Remote memories over HTTP; serves both the list and the create screen. */
public final class MemoryRepository implements MemoryListRepository, MemoryCreateRepository {
    private final MemoryApi api;
    private final ApiCaller caller;

    public MemoryRepository(MemoryApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    @Override
    public Cancellable create(String authorization, String idempotencyKey, MemoryApi.Draft draft,
                              ResultCallback<MemoryApi.Memory> callback) {
        return caller.enqueue(api.create(authorization, idempotencyKey, draft), callback);
    }

    @Override
    public Cancellable list(String authorization, int page, ResultCallback<MemoryApi.Page> callback) {
        return caller.enqueue(api.list(authorization, page), callback);
    }
}
