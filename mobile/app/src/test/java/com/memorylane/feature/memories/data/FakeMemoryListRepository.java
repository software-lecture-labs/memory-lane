package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link MemoryListRepository} for unit tests. Every call is recorded and stays
 * pending until the test answers it, so tests control ordering, paging and failures. It keeps
 * the interface contract: an answer to a cancelled request is dropped, and a request is
 * answered at most once.
 */
public final class FakeMemoryListRepository implements MemoryListRepository {
    public final List<Request> requests = new ArrayList<>();

    @Override
    public Cancellable list(String authorization, int page, ResultCallback<MemoryApi.Page> callback) {
        Request request = new Request(authorization, page, callback);
        requests.add(request);
        return () -> request.cancelled = true;
    }

    public Request last() {
        if (requests.isEmpty()) throw new AssertionError("No list request was made");
        return requests.get(requests.size() - 1);
    }

    /** Sample memories in the server's order (newest first) across two years. */
    public static List<MemoryApi.Memory> sampleMemories() {
        return List.of(
                MemoryFixtures.memory("m3", "Bursa'da bir sonbahar günü",
                        "Tophane yamaçlarında kestane şekeri molası.", "2024-10-28"),
                MemoryFixtures.memory("m2", "Annemle kahve molası", "", "2024-10-14"),
                MemoryFixtures.memory("m1", "Üniversitedeki ilk günüm",
                        "Amfinin kapısındaki o tatlı heyecan.", "2023-09-18"));
    }

    public static final class Request {
        public final String authorization;
        public final int page;
        private final ResultCallback<MemoryApi.Page> callback;
        private boolean cancelled;
        private boolean answered;

        private Request(String authorization, int page, ResultCallback<MemoryApi.Page> callback) {
            this.authorization = authorization;
            this.page = page;
            this.callback = callback;
        }

        public boolean isCancelled() { return cancelled; }

        public void succeed(List<MemoryApi.Memory> memories, boolean hasNext) {
            answer(ApiResult.success(new MemoryApi.Page(memories, hasNext)));
        }

        public void fail(ApiError error) {
            answer(ApiResult.failure(error));
        }

        private void answer(ApiResult<MemoryApi.Page> result) {
            if (answered) throw new AssertionError("Request for page " + page + " was already answered");
            answered = true;
            if (!cancelled) callback.onResult(result);
        }
    }
}
