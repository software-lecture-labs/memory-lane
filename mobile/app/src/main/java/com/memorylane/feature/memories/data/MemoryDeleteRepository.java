package com.memorylane.feature.memories.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/**
 * Deletes one memory of the signed-in user (ML-13). The delete panel depends on this interface
 * only (ISP/DIP).
 *
 * <p>Contract: the callback runs exactly once with success (204, no body) or an {@code ApiError}
 * (404 MEMORY_NOT_FOUND for missing and foreign memories alike, 401 INVALID_SESSION), unless the
 * returned {@link Cancellable} was cancelled first; then it never runs.
 */
public interface MemoryDeleteRepository {
    /** @param authorization full header value, e.g. {@code "Bearer <token>"} */
    Cancellable deleteMemory(String authorization, String memoryId, ResultCallback<Void> callback);
}
