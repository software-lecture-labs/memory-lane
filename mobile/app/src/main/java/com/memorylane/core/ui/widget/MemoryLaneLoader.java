package com.memorylane.core.ui.widget;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.memorylane.R;
import com.memorylane.core.ui.Motion;

/**
 * The app's waiting sign: the Memory Lane polaroid gently swaying and breathing, like a photo
 * pinned to a page in a draft. It replaces the plain spinner wherever a screen waits for the
 * server, so waiting looks like part of the app rather than a stall.
 *
 * <p>The motion runs only while the view is attached and visible, and not at all when the
 * system "Remove animations" setting is on; the logo then simply stands still. It is decorative:
 * the text next to it tells TalkBack what is loading.
 */
public class MemoryLaneLoader extends AppCompatImageView {
    private static final long SWAY_MS = 1100;
    private static final float SWAY_DEGREES = 6f;
    private static final float BREATH_SCALE = 1.06f;

    @Nullable private ValueAnimator sway;

    public MemoryLaneLoader(@NonNull Context context) {
        this(context, null);
    }

    public MemoryLaneLoader(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setImageResource(R.drawable.ic_logo_mark);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateMotion();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateMotion();
    }

    private void updateMotion() {
        boolean shown = isAttachedToWindow() && isShown();
        if (shown && Motion.enabled()) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (sway != null) return;
        // Swings from one side to the other and back, a little larger at the ends of the swing.
        sway = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(View.ROTATION, -SWAY_DEGREES, SWAY_DEGREES),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, BREATH_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, BREATH_SCALE));
        sway.setDuration(SWAY_MS);
        sway.setRepeatCount(ValueAnimator.INFINITE);
        sway.setRepeatMode(ValueAnimator.REVERSE);
        sway.setInterpolator(new AccelerateDecelerateInterpolator());
        sway.start();
    }

    private void stop() {
        if (sway == null) return;
        sway.cancel();
        sway = null;
        setRotation(0f);
        setScaleX(1f);
        setScaleY(1f);
    }
}
