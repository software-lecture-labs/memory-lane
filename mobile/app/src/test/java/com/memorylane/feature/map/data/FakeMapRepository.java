package com.memorylane.feature.map.data;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link MapRepository} for unit tests. Every call is recorded and stays pending until
 * the test answers it; an answer to a cancelled request is dropped, as the interface requires.
 */
public final class FakeMapRepository implements MapRepository {
    public final List<Request> requests = new ArrayList<>();

    @Override
    public Cancellable points(String authorization, ResultCallback<MapApi.Points> callback) {
        Request request = new Request(authorization, callback);
        requests.add(request);
        return () -> request.cancelled = true;
    }

    public Request last() {
        if (requests.isEmpty()) throw new AssertionError("No map request was made");
        return requests.get(requests.size() - 1);
    }

    public static MapApi.Point point(String id, double latitude, double longitude) {
        return new MapApi.Point(id, "Anı " + id, "2026-05-01", latitude, longitude, null);
    }

    public static MapApi.Points points(MapApi.Point... points) {
        return new MapApi.Points(List.of(points), 1000, points.length, false);
    }

    public static final class Request {
        public final String authorization;
        private final ResultCallback<MapApi.Points> callback;
        private boolean cancelled;

        private Request(String authorization, ResultCallback<MapApi.Points> callback) {
            this.authorization = authorization;
            this.callback = callback;
        }

        public boolean isCancelled() { return cancelled; }

        public void succeed(MapApi.Points points) {
            if (!cancelled) callback.onResult(ApiResult.success(points));
        }

        public void fail(ApiError error) {
            if (!cancelled) callback.onResult(ApiResult.failure(error));
        }
    }
}
