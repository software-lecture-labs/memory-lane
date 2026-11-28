package com.memorylane.core.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.window.SplashScreenView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * Hand-off from the Android 12+ launch screen to the app: the logo grows a little and fades
 * while the launch screen dissolves into the first screen, instead of a hard cut. Nothing is
 * delayed for it; it starts as soon as the app is ready to draw. On older versions, or with
 * system animations off, the system's own transition is kept.
 */
public final class SplashExit {
    private static final long EXIT_MS = 320;
    private static final float LOGO_GROWTH = 1.25f;

    private SplashExit() {
    }

    public static void install(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSplashScreen().setOnExitAnimationListener(SplashExit::play);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void play(@NonNull SplashScreenView splash) {
        View logo = splash.getIconView();
        if (!Motion.enabled() || logo == null) {
            splash.remove();
            return;
        }
        AnimatorSet exit = new AnimatorSet();
        exit.playTogether(
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 1f, LOGO_GROWTH),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1f, LOGO_GROWTH),
                ObjectAnimator.ofFloat(splash, View.ALPHA, 1f, 0f));
        exit.setDuration(EXIT_MS);
        exit.setInterpolator(new AccelerateInterpolator(1.5f));
        exit.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                splash.remove();
            }
        });
        exit.start();
    }
}
