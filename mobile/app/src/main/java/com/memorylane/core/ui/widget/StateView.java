package com.memorylane.core.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.memorylane.R;
import com.memorylane.databinding.ViewStateBinding;

/**
 * Shared loading / error / empty block. Error always carries an icon and a retry action,
 * empty never does, so the two cannot be confused on any screen that uses this view. Loading
 * shows the app's swaying logo ({@link MemoryLaneLoader}); every screen that waits gets it
 * through this view without changing its own code.
 */
public class StateView extends LinearLayout {
    /** After this long a loading message admits the connection is slow, so it never looks frozen. */
    static final long SLOW_AFTER_MS = 4000;

    private final ViewStateBinding binding;
    private final Runnable slowHint;
    @Nullable private CharSequence loadingMessage;

    public StateView(Context context) {
        this(context, null);
    }

    public StateView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StateView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        binding = ViewStateBinding.inflate(LayoutInflater.from(context), this);
        slowHint = () -> setText(binding.stateMessage,
                getContext().getString(R.string.state_loading_slow, loadingMessage));
        hide();
    }

    /**
     * Waiting for the server: the swaying logo and {@code message}. If the wait goes on for
     * {@link #SLOW_AFTER_MS}, a second line says the connection is slow.
     */
    public void showLoading(CharSequence message) {
        render(true, false, null, message, null);
        loadingMessage = message;
        postDelayed(slowHint, SLOW_AFTER_MS);
    }

    public void showError(CharSequence title, CharSequence message, @Nullable Runnable onRetry) {
        render(false, true, title, message, onRetry);
    }

    public void showEmpty(CharSequence title, CharSequence message) {
        render(false, false, title, message, null);
    }

    public void hide() {
        removeCallbacks(slowHint);
        setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(slowHint);
        super.onDetachedFromWindow();
    }

    private void render(boolean loading, boolean error, @Nullable CharSequence title,
                        @Nullable CharSequence message, @Nullable Runnable onRetry) {
        // Any new state replaces a pending "slow connection" line from an earlier loading.
        removeCallbacks(slowHint);
        setVisibility(VISIBLE);
        binding.stateProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.stateIcon.setVisibility(error ? View.VISIBLE : View.GONE);
        setText(binding.stateTitle, title);
        setText(binding.stateMessage, message);
        binding.stateRetry.setVisibility(onRetry != null ? View.VISIBLE : View.GONE);
        binding.stateRetry.setOnClickListener(onRetry == null ? null : v -> onRetry.run());
    }

    private static void setText(TextView view, @Nullable CharSequence text) {
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
