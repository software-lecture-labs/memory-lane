package com.memorylane.core.ui;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;

/**
 * Shared entrance motion, so every screen moves with the same timing and easing.
 *
 * <p>Views always end fully visible at their layout position. When the system "Remove
 * animations" setting is on, nothing moves: the views are simply shown.
 */
public final class Motion {
    /** Fast start, soft landing: content arrives instead of sliding mechanically. */
    private static final Interpolator EASE_OUT = new DecelerateInterpolator(2.2f);
    private static final Interpolator SETTLE = new OvershootInterpolator(1.4f);
    private static final long RISE_MS = 420;
    private static final long DROP_MS = 640;

    private Motion() {
    }

    /** False when the user turned animations off in the system settings. */
    public static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /** Fades the view in while it rises a few dp into place. */
    public static void riseIn(@NonNull View view, long delayMs) {
        view.animate().cancel();
        if (!enabled()) {
            reset(view);
            return;
        }
        float distance = 16 * view.getResources().getDisplayMetrics().density;
        view.setAlpha(0f);
        view.setTranslationY(distance);
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(delayMs).setDuration(RISE_MS).setInterpolator(EASE_OUT).start();
    }

    /**
     * A polaroid dropped on the page: it arrives smaller and twisted, then settles flat with a
     * slight overshoot. Used once, for the logo on the login screen.
     */
    public static void dropIn(@NonNull View view, long delayMs) {
        view.animate().cancel();
        if (!enabled()) {
            reset(view);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.6f);
        view.setScaleY(0.6f);
        view.setRotation(-18f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setStartDelay(delayMs).setDuration(DROP_MS).setInterpolator(SETTLE).start();
    }

    private static void reset(View view) {
        view.setAlpha(1f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setRotation(0f);
    }
}
