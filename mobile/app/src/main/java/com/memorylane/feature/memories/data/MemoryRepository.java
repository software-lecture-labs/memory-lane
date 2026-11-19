package com.memorylane.feature.memories.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

public interface MemoryRepository {
    Cancellable save(String session, String key, MemoryApi.Draft draft,
                     ResultCallback<MemoryApi.Memory> callback);

    Cancellable list(String session, int page, ResultCallback<MemoryApi.Page> callback);
}
