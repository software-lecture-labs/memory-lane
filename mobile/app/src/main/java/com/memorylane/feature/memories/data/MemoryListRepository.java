package com.memorylane.feature.memories.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/**
 * Reads the signed-in user's memories page by page. The list screen depends on this interface
 * only (DIP); the remote implementation and test fakes are interchangeable behind it.
 *
 * <p>Contract: the callback runs exactly once with a success or an {@code ApiError}, unless the
 * returned {@link Cancellable} was cancelled first; then it never runs.
 */
public interface MemoryListRepository {
    /**
     * @param authorization full header value, e.g. {@code "Bearer <token>"}
     * @param page zero-based page number
     */
    Cancellable list(String authorization, int page, ResultCallback<MemoryApi.Page> callback);
}
