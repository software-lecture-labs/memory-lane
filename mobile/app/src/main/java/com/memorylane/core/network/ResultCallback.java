package com.memorylane.core.network;

import androidx.annotation.NonNull;

public interface ResultCallback<T> {
    void onResult(@NonNull ApiResult<T> result);
}
