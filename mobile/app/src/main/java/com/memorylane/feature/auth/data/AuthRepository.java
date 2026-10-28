package com.memorylane.feature.auth.data;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

/** Auth data operations; session ownership and screen transitions belong to their callers. */
public interface AuthRepository {
    Cancellable register(AuthRequest request, ResultCallback<UserResponse> callback);

    Cancellable login(AuthRequest request, ResultCallback<LoginResponse> callback);
}
