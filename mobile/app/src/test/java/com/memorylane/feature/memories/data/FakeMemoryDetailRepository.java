package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.core.network.Tagged;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link MemoryDetailRepository} for unit tests. Every call is recorded and stays
 * pending until the test answers it; an answer to a cancelled request is dropped and a request is
 * answered at most once, as the interface requires.
 */
public final class FakeMemoryDetailRepository implements MemoryDetailRepository {
    public static final String ETAG = "\"memory-m1-r1\"";

    public final List<Request> requests = new ArrayList<>();

    @Override
    public Cancellable getMemoryDetail(String authorization, String memoryId,
                                       ResultCallback<Tagged<MemoryApi.Detail>> callback) {
        Request request = new Request(authorization, memoryId, callback);
        requests.add(request);
        return () -> request.cancelled = true;
    }

    public Request last() {
        if (requests.isEmpty()) throw new AssertionError("No detail request was made");
        return requests.get(requests.size() - 1);
    }

    /** A memory with two photos, a category, an emotion and a location. */
    public static MemoryApi.Detail fullMemory(String id) {
        return new MemoryApi.Detail(id, "Bursa'da bir sonbahar günü", "Kestane şekeri molası.", "2024-10-28",
                "TRAVEL", "PEACEFUL", new MemoryApi.Location(40.1885, 29.0610),
                "2026-01-01T09:00:00Z", "2026-01-02T09:00:00Z", 2, "p1",
                List.of(photo(id, "p1", 0), photo(id, "p2", 3)));
    }

    /** A memory with only the required fields: no photos, no emotion, no location. */
    public static MemoryApi.Detail plainMemory(String id) {
        return new MemoryApi.Detail(id, "Sade bir gün", "", "2025-01-01", "OTHER", null, null,
                "2026-01-01T09:00:00Z", "2026-01-01T09:00:00Z", 0, null, List.of());
    }

    public static MemoryApi.Photo photo(String memoryId, String id, int order) {
        return new MemoryApi.Photo(id, memoryId, order, "image/jpeg", 1000, "2026-01-01T09:00:00Z",
                "/api/v1/memories/" + memoryId + "/photos/" + id + "/content");
    }

    public static final class Request {
        public final String authorization;
        public final String memoryId;
        private final ResultCallback<Tagged<MemoryApi.Detail>> callback;
        private boolean cancelled;
        private boolean answered;

        private Request(String authorization, String memoryId, ResultCallback<Tagged<MemoryApi.Detail>> callback) {
            this.authorization = authorization;
            this.memoryId = memoryId;
            this.callback = callback;
        }

        public boolean isCancelled() { return cancelled; }

        public void succeed(MemoryApi.Detail memory) {
            succeed(memory, ETAG);
        }

        /** Answers with another version's tag, e.g. after the memory changed elsewhere. */
        public void succeed(MemoryApi.Detail memory, String etag) {
            answer(ApiResult.success(new Tagged<>(memory, etag)));
        }

        public void fail(ApiError error) {
            answer(ApiResult.failure(error));
        }

        private void answer(ApiResult<Tagged<MemoryApi.Detail>> result) {
            if (answered) throw new AssertionError("Detail request was already answered");
            answered = true;
            if (!cancelled) callback.onResult(result);
        }
    }
}
