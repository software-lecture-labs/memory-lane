package com.memorylane.core.navigation;

import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;

import com.memorylane.R;
import com.memorylane.core.session.SessionStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps navigation in line with the in-memory session (ML-30):
 *
 * <ul>
 *   <li>When the session ends (sign-out or 401) the whole back stack is replaced by the login
 *       screen and the tab stacks the bottom bar saved are cleared. Every protected screen's
 *       ViewModel is cleared with it, so the next account sees nothing of the previous one, and
 *       back on the login screen leaves the app.</li>
 *   <li>A protected destination without a session (first launch, restore after process death,
 *       any later navigation) is replaced by the login screen the same way.</li>
 * </ul>
 *
 * <p>Fragment transactions are not allowed after the activity saved its state, so a pending
 * redirect waits for {@code onStart}. Destination changes are checked in a posted task, not inside
 * the NavController callback.
 */
public final class SessionNavigator implements DefaultLifecycleObserver, SessionStore.Listener,
        NavController.OnDestinationChangedListener {
    private final FragmentActivity activity;
    private final NavController navController;
    private final SessionStore sessionStore;
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private boolean resetPending;

    private SessionNavigator(FragmentActivity activity, NavController navController,
                             SessionStore sessionStore) {
        this.activity = activity;
        this.navController = navController;
        this.sessionStore = sessionStore;
    }

    /**
     * Binds to the activity's lifecycle; listeners are removed in {@code onDestroy}, so the
     * process-wide store never holds a destroyed activity. Call after the NavController exists:
     * a protected destination is replaced right away, before its view is created.
     */
    public static void bind(@NonNull FragmentActivity activity, @NonNull NavController navController,
                            @NonNull SessionStore sessionStore) {
        SessionNavigator navigator = new SessionNavigator(activity, navController, sessionStore);
        sessionStore.addListener(navigator);
        navController.addOnDestinationChangedListener(navigator);
        activity.getLifecycle().addObserver(navigator);
        navigator.apply();
    }

    @Override
    public void onSessionEnded(@NonNull SessionStore.EndReason reason) {
        // Even on a public screen: the protected stacks below it must not survive the session.
        resetPending = true;
        apply();
    }

    @Override
    public void onDestinationChanged(@NonNull NavController controller,
                                     @NonNull NavDestination destination, @Nullable Bundle arguments) {
        mainThread.post(this::apply);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        apply();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        sessionStore.removeListener(this);
        navController.removeOnDestinationChangedListener(this);
        mainThread.removeCallbacksAndMessages(null);
    }

    private void apply() {
        if (activity.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED
                || activity.getSupportFragmentManager().isStateSaved()) {
            return; // onStart runs apply() again
        }
        if (sessionStore.isActive()) {
            resetPending = false; // signed in again before the reset could run
            return;
        }
        NavDestination current = navController.getCurrentDestination();
        boolean onProtected = current != null && SessionRoutes.requiresSession(current.getId());
        if (!resetPending && !onProtected) return;
        resetPending = false;
        showLogin();
    }

    private void showLogin() {
        navController.navigate(R.id.loginFragment, null, new NavOptions.Builder()
                .setPopUpTo(R.id.nav_graph, true)
                .build());
        // popUpTo without saveState leaves the tab stacks NavigationUI saved; drop them too.
        List<Integer> destinations = new ArrayList<>();
        for (NavDestination destination : navController.getGraph()) {
            destinations.add(destination.getId());
        }
        for (int id : destinations) {
            if (SessionRoutes.requiresSession(id)) navController.clearBackStack(id);
        }
    }
}
