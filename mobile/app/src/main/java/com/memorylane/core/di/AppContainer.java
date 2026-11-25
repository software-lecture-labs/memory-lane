package com.memorylane.core.di;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.memorylane.MemoryLaneApp;
import com.memorylane.core.image.BitmapSampling;
import com.memorylane.core.image.PhotoImageLoader;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.core.network.AuthInterceptor;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.core.session.SessionStore;
import com.memorylane.feature.health.data.HealthApi;
import com.memorylane.feature.health.data.HealthRepository;
import com.memorylane.feature.health.data.RemoteHealthRepository;

import com.memorylane.feature.auth.data.AuthApi;
import com.memorylane.feature.auth.data.AuthRepository;
import com.memorylane.feature.auth.data.RemoteAuthRepository;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryRepository;
import com.memorylane.feature.photos.data.DevicePhotoPreparer;
import com.memorylane.feature.photos.data.PhotoApi;
import com.memorylane.feature.photos.data.PhotoPreparer;
import com.memorylane.feature.photos.data.PhotoUploads;
import com.memorylane.feature.photos.data.RemotePhotoUploads;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import retrofit2.Retrofit;

/**
 * Manual dependency injection: the only place that knows concrete classes. Screens ask for
 * interfaces (e.g. {@link HealthRepository}) and stay unaware of Retrofit. Moving to Hilt later
 * only replaces this class; see docs/architecture.md "Mobil mimari".
 *
 * <p>Owns the in-memory {@link SessionStore} for the whole process. {@link AuthInterceptor} puts
 * its token on every protected request and expires it on 401, so screens never handle the token.
 */
public final class AppContainer {
    /** An eighth of the app's heap, the usual budget for an in-memory image cache on Android. */
    private static final int PHOTO_CACHE_SHARE_OF_HEAP = 8;
    /** Two downloads at a time: enough for a screen of cards without crowding a slow connection. */
    private static final int PHOTO_DOWNLOAD_THREADS = 2;

    private final String baseUrl;
    private final ApiCaller caller;
    private final SessionStore sessionStore;
    private final AuthApi authApi;
    private final AuthRepository authRepository;
    private final MemoryRepository memoryRepository;
    private final HealthRepository healthRepository;
    private final PhotoUploads photoUploads;
    private final PhotoPreparer photoPreparer;
    private final PhotoImageLoader<Bitmap> photoImageLoader;

    /** {@code appContext} must be the application context; the photo preparer outlives any screen. */
    public AppContainer(String baseUrl, Context appContext) {
        this(baseUrl, mainThread(),
                new DevicePhotoPreparer(appContext.getContentResolver(), appContext.getCacheDir()));
    }

    /**
     * @param mainThread runs session-ended listeners and delivers loaded photos; the main thread
     *     in the app
     * @param photoPreparer passed in so JVM tests can build the container without an Android Context
     */
    AppContainer(String baseUrl, Executor mainThread, PhotoPreparer photoPreparer) {
        Gson gson = new Gson();
        sessionStore = new SessionStore(mainThread);
        Retrofit retrofit = ApiClient.create(baseUrl, gson, new AuthInterceptor(sessionStore, baseUrl));
        caller = new ApiCaller(gson);
        authApi = retrofit.create(AuthApi.class);
        authRepository = new RemoteAuthRepository(authApi, caller);
        memoryRepository = new MemoryRepository(retrofit.create(MemoryApi.class), caller);
        this.baseUrl = retrofit.baseUrl().toString();
        this.healthRepository = new RemoteHealthRepository(retrofit.create(HealthApi.class), caller);
        PhotoApi photoApi = retrofit.create(PhotoApi.class);
        this.photoUploads = new RemotePhotoUploads(photoApi, caller);
        this.photoPreparer = photoPreparer;
        // Decoded photos stay in memory only (the server says no-store) and are dropped with the session.
        photoImageLoader = new PhotoImageLoader<>(photoApi, BitmapSampling::decode, Bitmap::getAllocationByteCount,
                Runtime.getRuntime().maxMemory() / PHOTO_CACHE_SHARE_OF_HEAP,
                Executors.newFixedThreadPool(PHOTO_DOWNLOAD_THREADS, AppContainer::photoThread), mainThread);
        sessionStore.addListener(photoImageLoader);
    }

    // Daemon threads: idle photo workers never keep the process (or a JVM test run) alive.
    private static Thread photoThread(Runnable task) {
        Thread thread = new Thread(task, "photo-loader");
        thread.setDaemon(true);
        return thread;
    }

    private static Executor mainThread() {
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    public static AppContainer from(Context context) {
        return ((MemoryLaneApp) context.getApplicationContext()).getContainer();
    }

    /** Normalized server address, shown on screen to help diagnose emulator/phone setup. */
    public String getBaseUrl() {
        return baseUrl;
    }

    public HealthRepository getHealthRepository() {
        return healthRepository;
    }

    public AuthRepository getAuthRepository() { return authRepository; }

    public MemoryRepository getMemoryRepository() { return memoryRepository; }

    public PhotoUploads getPhotoUploads() { return photoUploads; }

    public PhotoPreparer getPhotoPreparer() { return photoPreparer; }

    /** Session-bound, memory-only loader for memory photos; cleared on sign-out and 401. */
    public PhotoImageLoader<Bitmap> getPhotoImageLoader() { return photoImageLoader; }

    public SessionStore getSessionStore() { return sessionStore; }

    /** {@code Authorization} value of the current session; null when signed out. */
    @Nullable
    public String authorization() { return sessionStore.authorization(); }

    /** Starts the session after a successful login; e-mail comes from the login response. */
    public void startSession(@NonNull String accessToken, @NonNull String email) {
        sessionStore.start(accessToken, email);
    }

    /**
     * Signs out: the session is gone locally at once, then the old token is revoked on the server.
     * The server's answer is not awaited and cannot undo the local sign-out (500, dropped
     * connection or stopped server included); the token then simply expires on the server.
     */
    public void endSession() {
        endSession(result -> { });
    }

    /** Same as {@link #endSession()}; {@code onServerAnswer} lets tests wait for the server. */
    void endSession(ResultCallback<Void> onServerAnswer) {
        String authorization = sessionStore.logout();
        if (authorization == null) return;
        // The explicit header is kept by AuthInterceptor; a 401 for it cannot end a newer session.
        caller.enqueue(authApi.logout(authorization), onServerAnswer);
    }

    /**
     * Ends the current session after the server rejected it with 401. Normally a no-op:
     * {@link AuthInterceptor} has already expired the session before the screen sees the 401.
     */
    public void clearSession() { sessionStore.expire(sessionStore.token()); }
}
