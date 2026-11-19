package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

public final class RemoteMemoryRepository implements MemoryRepository {
    private final MemoryApi api;
    private final ApiCaller caller;

    public RemoteMemoryRepository(MemoryApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    @Override
    public Cancellable save(String session, String key, MemoryApi.Draft draft,
                            ResultCallback<MemoryApi.Memory> callback) {
        return caller.enqueue(api.create(session, key, draft), callback);
    }

    @Override
    public Cancellable list(String session, int page, ResultCallback<MemoryApi.Page> callback) {
        return caller.enqueue(api.list(session, page), callback);
    }
}
