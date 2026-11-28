package com.memorylane.feature.memories.data;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import java.util.ArrayList;
import java.util.List;

/** In-memory {@link MemoryDeleteRepository}: every call is recorded and waits for the test's answer. */
public final class FakeMemoryDeleteRepository implements MemoryDeleteRepository {
    public final List<Request> requests = new ArrayList<>();

    @Override
    public Cancellable deleteMemory(String authorization, String memoryId, ResultCallback<Void> callback) {
        Request request = new Request(authorization, memoryId, callback);
        requests.add(request);
        return () -> request.cancelled = true;
    }

    public Request last() {
        if (requests.isEmpty()) throw new AssertionError("No delete request was made");
        return requests.get(requests.size() - 1);
    }

    public static final class Request {
        public final String authorization;
        public final String memoryId;
        private final ResultCallback<Void> callback;
        private boolean cancelled;

        private Request(String authorization, String memoryId, ResultCallback<Void> callback) {
            this.authorization = authorization;
            this.memoryId = memoryId;
            this.callback = callback;
        }

        public boolean isCancelled() { return cancelled; }

        public void succeed() {
            if (!cancelled) callback.onResult(ApiResult.success(null));
        }

        public void fail(ApiError error) {
            if (!cancelled) callback.onResult(ApiResult.failure(error));
        }
    }
}
