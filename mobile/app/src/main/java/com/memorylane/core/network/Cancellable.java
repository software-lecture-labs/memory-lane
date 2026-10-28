package com.memorylane.core.network;

/** Lets a ViewModel drop an in-flight request without knowing it is a Retrofit call. */
public interface Cancellable {
    void cancel();
}
