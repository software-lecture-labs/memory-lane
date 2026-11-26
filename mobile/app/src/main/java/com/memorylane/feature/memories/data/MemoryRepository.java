package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.core.network.Tagged;

/** Remote memories over HTTP; serves the list, create, detail and edit screens. */
public final class MemoryRepository
        implements MemoryListRepository, MemoryCreateRepository, MemoryDetailRepository, MemoryEditRepository {
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

    @Override
    public Cancellable getMemoryDetail(String authorization, String memoryId,
                                       ResultCallback<Tagged<MemoryApi.Detail>> callback) {
        return caller.enqueueTagged(api.detail(authorization, memoryId), callback);
    }

    @Override
    public Cancellable updateMemory(String authorization, String memoryId, String ifMatch, MemoryApi.Draft draft,
                                    ResultCallback<MemoryApi.Detail> callback) {
        return caller.enqueue(api.update(authorization, memoryId, ifMatch, draft), callback);
    }
}
