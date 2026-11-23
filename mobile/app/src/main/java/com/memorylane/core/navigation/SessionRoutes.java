package com.memorylane.core.navigation;

import com.memorylane.R;

import java.util.Set;

/**
 * Which navigation destinations may be shown without a session. Everything else is protected,
 * including destinations added later, so a forgotten entry fails safe (back to login) rather than
 * showing a protected screen signed out. SessionRoutesTest checks this list against nav_graph.xml.
 */
public final class SessionRoutes {
    private static final Set<Integer> PUBLIC = Set.of(R.id.loginFragment, R.id.registerFragment);

    private SessionRoutes() {
    }

    public static boolean isPublic(int destinationId) {
        return PUBLIC.contains(destinationId);
    }

    public static boolean requiresSession(int destinationId) {
        return !isPublic(destinationId);
    }
}
