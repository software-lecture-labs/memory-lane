package com.memorylane.core.image;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.session.SessionStore;
import com.memorylane.feature.photos.data.PhotoApi;
import com.memorylane.feature.photos.data.PhotoRules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Loads protected memory photos for screens. The app uses it with {@code V = Bitmap}; the image
 * type is a parameter only so these rules can be tested on the JVM, where Android's Bitmap is
 * unavailable.
 *
 * <ul>
 *   <li><b>Session:</b> downloads go through {@link PhotoApi#downloadPhotoContent}, so
 *       {@code AuthInterceptor} adds the Bearer token and a 401 ends the session.</li>
 *   <li><b>Memory only:</b> the server answers {@code Cache-Control: no-store}; decoded images live
 *       in a size-bounded LRU in memory and nothing is ever written to disk.</li>
 *   <li><b>One request per owner:</b> an owner (in the app, the {@code ImageView}) gets only the
 *       answer to its latest {@link #load}. A recycled list cell that asks for another photo
 *       cancels the earlier request, so an old answer can never show the wrong photo.</li>
 *   <li><b>Session end:</b> {@link #clear()} (also run by {@link #onSessionEnded}) empties the
 *       cache, cancels running downloads and drops their answers, so the next account never sees
 *       the previous account's photos.</li>
 * </ul>
 *
 * <p>{@link #load}, {@link #cancel} and {@link #clear} are called on the main thread; downloads and
 * decoding run on the background executor, and results are delivered on the main executor.
 */
public final class PhotoImageLoader<V> implements SessionStore.Listener {
    /** Turns downloaded bytes into an image for the target size; null if they are not one. */
    public interface Decoder<V> {
        @Nullable
        V decode(@NonNull byte[] bytes, int width, int height);
    }

    /** Memory an image takes in the cache, in bytes. */
    public interface Sizer<V> {
        int sizeOf(@NonNull V image);
    }

    /** Receives the image, or null when it could not be loaded (the caller shows a placeholder). */
    public interface Target<V> {
        void onResult(@Nullable V image);
    }

    private final PhotoApi api;
    private final Decoder<V> decoder;
    private final Sizer<V> sizer;
    private final Executor background;
    private final Executor main;
    private final MemoryCache<V> cache;
    /** Latest request of each owner; weak keys so a destroyed view is not kept alive. */
    private final Map<Object, Request<V>> current = new WeakHashMap<>();
    /** Raised by {@link #clear()}; answers of an older generation are dropped. */
    private int generation;

    /**
     * @param maxCacheBytes memory budget of the cache; an image larger than it is shown but not kept
     * @param main          delivers results; the main thread in the app
     */
    public PhotoImageLoader(@NonNull PhotoApi api, @NonNull Decoder<V> decoder, @NonNull Sizer<V> sizer,
                            long maxCacheBytes, @NonNull Executor background, @NonNull Executor main) {
        this.api = Objects.requireNonNull(api, "api");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.sizer = Objects.requireNonNull(sizer, "sizer");
        this.background = Objects.requireNonNull(background, "background");
        this.main = Objects.requireNonNull(main, "main");
        this.cache = new MemoryCache<>(maxCacheBytes, sizer);
    }

    /** Cache key: the same photo at another size is a separate image. */
    static String key(String memoryId, String photoId, int width, int height) {
        return memoryId + '/' + photoId + '@' + width + 'x' + height;
    }

    /**
     * Shows photo {@code photoId} of memory {@code memoryId} at {@code width} x {@code height} on
     * {@code owner}. Replaces the owner's earlier request. A cached image is delivered at once;
     * otherwise {@code target} is called once with the image or null, unless the request is
     * replaced, cancelled or the cache is cleared first.
     */
    public void load(@NonNull Object owner, @NonNull String memoryId, @NonNull String photoId,
                     int width, int height, @NonNull Target<V> target) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Target size must be positive");
        String key = key(memoryId, photoId, width, height);
        Request<V> request = null;
        V cached;
        synchronized (this) {
            cancelLocked(owner);
            cached = cache.get(key);
            if (cached == null) {
                request = new Request<>(owner, key, generation, target, api.downloadPhotoContent(memoryId, photoId));
                current.put(owner, request);
            }
        }
        if (cached != null) {
            target.onResult(cached);
            return;
        }
        Request<V> started = request;
        background.execute(() -> {
            V image = download(started.call, width, height);
            main.execute(() -> deliver(started, image));
        });
    }

    /** Stops the owner's request, e.g. when its list cell is recycled; nothing is delivered. */
    public synchronized void cancel(@NonNull Object owner) {
        cancelLocked(owner);
    }

    /** Empties the cache and drops every running request. */
    public void clear() {
        List<Call<ResponseBody>> running = new ArrayList<>();
        synchronized (this) {
            generation++;
            for (Iterator<Request<V>> it = current.values().iterator(); it.hasNext(); ) {
                running.add(it.next().call);
                it.remove();
            }
            cache.clear();
        }
        for (Call<ResponseBody> call : running) call.cancel();
    }

    /** Sign-out and 401: the next account must not see this account's photos. */
    @Override
    public void onSessionEnded(@NonNull SessionStore.EndReason reason) {
        clear();
    }

    private void cancelLocked(Object owner) {
        Request<V> previous = current.remove(owner);
        if (previous != null) previous.call.cancel();
    }

    @Nullable
    private V download(Call<ResponseBody> call, int width, int height) {
        Response<ResponseBody> response;
        try {
            response = call.execute();
        } catch (IOException | RuntimeException failed) {
            return null; // no connection, or cancelled
        }
        try (ResponseBody body = response.isSuccessful() ? response.body() : response.errorBody()) {
            if (!response.isSuccessful() || body == null || body.contentLength() > PhotoRules.MAX_BYTES) {
                return null; // 404 and other errors; 401 already ended the session
            }
            byte[] bytes = body.bytes();
            return bytes.length == 0 || bytes.length > PhotoRules.MAX_BYTES ? null : decoder.decode(bytes, width, height);
        } catch (IOException | RuntimeException failed) {
            return null; // connection dropped mid-body, or bytes that are not an image
        }
    }

    private void deliver(Request<V> request, @Nullable V image) {
        synchronized (this) {
            if (current.get(request.owner) != request) return; // replaced, cancelled or cleared
            current.remove(request.owner);
            if (request.generation != generation) return;
            if (image != null) cache.put(request.key, image);
        }
        request.target.onResult(image);
    }

    private static final class Request<V> {
        final Object owner;
        final String key;
        final int generation;
        final Target<V> target;
        final Call<ResponseBody> call;

        Request(Object owner, String key, int generation, Target<V> target, Call<ResponseBody> call) {
            this.owner = owner;
            this.key = key;
            this.generation = generation;
            this.target = target;
            this.call = call;
        }
    }

    /** Least recently used images go first once the byte budget is exceeded. Not thread-safe. */
    static final class MemoryCache<V> {
        private final long maxBytes;
        private final Sizer<V> sizer;
        private final LinkedHashMap<String, V> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        MemoryCache(long maxBytes, Sizer<V> sizer) {
            this.maxBytes = maxBytes;
            this.sizer = sizer;
        }

        @Nullable
        V get(String key) {
            return entries.get(key);
        }

        void put(String key, V image) {
            long size = sizer.sizeOf(image);
            V replaced = entries.remove(key);
            if (replaced != null) bytes -= sizer.sizeOf(replaced);
            if (size > maxBytes) return;
            entries.put(key, image);
            bytes += size;
            Iterator<V> oldestFirst = entries.values().iterator();
            while (bytes > maxBytes && oldestFirst.hasNext()) {
                bytes -= sizer.sizeOf(oldestFirst.next());
                oldestFirst.remove();
            }
        }

        void clear() {
            entries.clear();
            bytes = 0;
        }
    }
}
