package com.memorylane.feature.health.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/** The ViewModel depends on this interface only, so tests can pass a fake without a server. */
public interface HealthRepository {
    Cancellable checkHealth(ResultCallback<HealthResponse> callback);
}
