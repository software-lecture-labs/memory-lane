package com.memorylane.feature.memories.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/**
 * Creates a memory for the signed-in user. The create screen depends on this interface only
 * (ISP/DIP); listing lives in {@link MemoryListRepository}.
 *
 * <p>Contract: the callback runs exactly once with the created memory or an {@code ApiError},
 * unless the returned {@link Cancellable} was cancelled first; then it never runs. Sending the
 * same idempotency key again replays the first result instead of creating a second memory.
 */
public interface MemoryCreateRepository {
    /**
     * @param authorization full header value, e.g. {@code "Bearer <token>"}
     * @param idempotencyKey lowercase UUID, kept while the same draft is retried
     */
    Cancellable create(String authorization, String idempotencyKey, MemoryApi.Draft draft,
                       ResultCallback<MemoryApi.Memory> callback);
}
