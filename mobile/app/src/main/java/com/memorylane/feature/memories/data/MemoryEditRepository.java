package com.memorylane.feature.memories.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/**
 * Saves the edit form of one memory (PUT with If-Match). The edit screen depends on this
 * interface only (ISP/DIP).
 *
 * <p>Contract: the callback runs exactly once with the updated detail or an {@code ApiError},
 * unless the returned {@link Cancellable} was cancelled first; then it never runs. The answer has
 * no ETag: the next edit takes its tag from {@link MemoryDetailRepository}. Errors the screen
 * decides on by status and code: 412 MEMORY_MODIFIED (keep the draft, never resend it
 * automatically), 428 PRECONDITION_REQUIRED, 400 INVALID_INPUT / INVALID_PRECONDITION,
 * 404 MEMORY_NOT_FOUND, 401 INVALID_SESSION.
 */
public interface MemoryEditRepository {
    /**
     * @param authorization full header value, e.g. {@code "Bearer <token>"}
     * @param ifMatch       the detail's ETag exactly as received, quotes included
     * @param draft         every writable field, including the ones the form does not change
     */
    Cancellable updateMemory(String authorization, String memoryId, String ifMatch, MemoryApi.Draft draft,
                             ResultCallback<MemoryApi.Detail> callback);
}
