package com.memorylane.core.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * The signed-in session, kept only in process memory (docs/architecture.md "Mobil oturum saklama
 * kararı"): no disk, no refresh token. When the process dies the session is gone and the user
 * signs in again.
 *
 * <p>Thread-safe: the HTTP layer reads and expires the session on OkHttp threads while screens use
 * it on the main thread. Listeners run on the {@link Executor} given to the constructor (the main
 * thread in the app), outside the lock, and only when a session actually ends.
 */
public final class SessionStore {

    /** Why a session ended; screens may phrase the message differently. */
    public enum EndReason {
        /** The user signed out. */
        LOGGED_OUT,
        /** The server rejected the session token with 401. */
        EXPIRED
    }

    public interface Listener {
        void onSessionEnded(@NonNull EndReason reason);
    }

    private record Session(String token, String email) { }

    private final Executor notifier;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    @Nullable private Session session;

    public SessionStore(@NonNull Executor notifier) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /**
     * Starts a session after a successful login, replacing any previous one without an end event
     * (the new account's screens follow the new session).
     */
    public synchronized void start(@NonNull String token, @NonNull String email) {
        if (isBlank(token)) throw new IllegalArgumentException("token must not be blank");
        if (isBlank(email)) throw new IllegalArgumentException("email must not be blank");
        session = new Session(token, email);
    }

    public synchronized boolean isActive() {
        return session != null;
    }

    /** Raw access token of the current session; null when signed out. */
    @Nullable
    public synchronized String token() {
        return session == null ? null : session.token();
    }

    /** {@code Authorization} header value of the current session; null when signed out. */
    @Nullable
    public synchronized String authorization() {
        return session == null ? null : "Bearer " + session.token();
    }

    /** Account e-mail from the login response; null when signed out. */
    @Nullable
    public synchronized String email() {
        return session == null ? null : session.email();
    }

    /**
     * Ends the session locally right away, whatever the server later answers to the logout call.
     *
     * @return the ended session's {@code Authorization} value so the caller can revoke it on the
     *     server, or null when there was no session (no event is sent then)
     */
    @Nullable
    public String logout() {
        String authorization;
        synchronized (this) {
            if (session == null) return null;
            authorization = "Bearer " + session.token();
            session = null;
        }
        notifyEnded(EndReason.LOGGED_OUT);
        return authorization;
    }

    /**
     * Ends the session after a 401, but only if {@code rejectedToken} is still the current token.
     * A late 401 for an old account's request must not end the session of the account that
     * signed in since.
     *
     * @return true if the current session was ended
     */
    public boolean expire(@Nullable String rejectedToken) {
        synchronized (this) {
            if (session == null || !session.token().equals(rejectedToken)) return false;
            session = null;
        }
        notifyEnded(EndReason.EXPIRED);
        return true;
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    private void notifyEnded(EndReason reason) {
        notifier.execute(() -> {
            for (Listener listener : listeners) {
                listener.onSessionEnded(reason);
            }
        });
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
