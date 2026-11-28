package com.memorylane.feature.map.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/**
 * Reads the map points of the signed-in user. The map screen depends on this interface only, so
 * tests can pass a fake without a server.
 *
 * <p>Contract: the callback runs exactly once with the points or an {@code ApiError}, unless the
 * returned {@link Cancellable} was cancelled first; then it never runs.
 */
public interface MapRepository {
    /** @param authorization full header value, e.g. {@code "Bearer <token>"} */
    Cancellable points(String authorization, ResultCallback<MapApi.Points> callback);
}
