package com.memorylane.feature.memories.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.core.network.Tagged;

/**
 * Reads one memory of the signed-in user with its photos. The detail and edit screens depend on
 * this interface only (ISP/DIP).
 *
 * <p>Contract: the callback runs exactly once with the detail and its ETag or an {@code ApiError}
 * (404 MEMORY_NOT_FOUND for missing and foreign memories alike, 401 INVALID_SESSION), unless the
 * returned {@link Cancellable} was cancelled first; then it never runs. A 2xx answer without an
 * ETag is a PARSE error, so the edit form is never opened without a tag to send back.
 */
public interface MemoryDetailRepository {
    /** @param authorization full header value, e.g. {@code "Bearer <token>"} */
    Cancellable getMemoryDetail(String authorization, String memoryId,
                                ResultCallback<Tagged<MemoryApi.Detail>> callback);
}
