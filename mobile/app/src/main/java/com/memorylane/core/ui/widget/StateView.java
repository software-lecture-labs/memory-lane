package com.memorylane.core.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.memorylane.databinding.ViewStateBinding;

/**
 * Shared loading / error / empty block. Error always carries an icon and a retry action,
 * empty never does, so the two cannot be confused on any screen that uses this view.
 */
public class StateView extends LinearLayout {
    private final ViewStateBinding binding;

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
        hide();
    }

    public void showLoading(CharSequence message) {
        render(true, false, null, message, null);
    }

    public void showError(CharSequence title, CharSequence message, @Nullable Runnable onRetry) {
        render(false, true, title, message, onRetry);
    }

    public void showEmpty(CharSequence title, CharSequence message) {
        render(false, false, title, message, null);
    }

    public void hide() {
        setVisibility(GONE);
    }

    private void render(boolean loading, boolean error, @Nullable CharSequence title,
                        @Nullable CharSequence message, @Nullable Runnable onRetry) {
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
