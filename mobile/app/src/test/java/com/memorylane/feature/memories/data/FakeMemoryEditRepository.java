package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link MemoryEditRepository} for unit tests. Every PUT is recorded with its If-Match
 * and body and stays pending until the test answers it; an answer to a cancelled request is
 * dropped and a request is answered at most once.
 */
public final class FakeMemoryEditRepository implements MemoryEditRepository {
    public final List<Request> requests = new ArrayList<>();

    @Override
    public Cancellable updateMemory(String authorization, String memoryId, String ifMatch, MemoryApi.Draft draft,
                                    ResultCallback<MemoryApi.Detail> callback) {
        Request request = new Request(authorization, memoryId, ifMatch, draft, callback);
        requests.add(request);
        return () -> request.cancelled = true;
    }

    public Request last() {
        if (requests.isEmpty()) throw new AssertionError("No update request was made");
        return requests.get(requests.size() - 1);
    }

    public static final class Request {
        public final String authorization;
        public final String memoryId;
        public final String ifMatch;
        public final MemoryApi.Draft draft;
        private final ResultCallback<MemoryApi.Detail> callback;
        private boolean cancelled;
        private boolean answered;

        private Request(String authorization, String memoryId, String ifMatch, MemoryApi.Draft draft,
                        ResultCallback<MemoryApi.Detail> callback) {
            this.authorization = authorization;
            this.memoryId = memoryId;
            this.ifMatch = ifMatch;
            this.draft = draft;
            this.callback = callback;
        }

        public boolean isCancelled() { return cancelled; }

        public void succeed(MemoryApi.Detail memory) {
            answer(ApiResult.success(memory));
        }

        public void fail(ApiError error) {
            answer(ApiResult.failure(error));
        }

        private void answer(ApiResult<MemoryApi.Detail> result) {
            if (answered) throw new AssertionError("Update request was already answered");
            answered = true;
            if (!cancelled) callback.onResult(result);
        }
    }
}
