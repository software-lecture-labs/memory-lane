package com.memorylane.core.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.memorylane.R;
import com.memorylane.databinding.ViewScreenHeaderBinding;

/** Title + subtitle block at the top of every screen, so headers cannot drift apart per screen. */
public class ScreenHeaderView extends LinearLayout {
    private final ViewScreenHeaderBinding binding;

    public ScreenHeaderView(Context context) {
        this(context, null);
    }

    public ScreenHeaderView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScreenHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        binding = ViewScreenHeaderBinding.inflate(LayoutInflater.from(context), this);
        // The XML attribute exists only from API 28; the compat call also covers API 26-27.
        ViewCompat.setAccessibilityHeading(binding.headerTitle, true);

        // TypedArray is AutoCloseable only from API 31; minSdk is 26, so recycle explicitly.
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ScreenHeaderView, defStyleAttr, 0);
        try {
            setTitle(a.getText(R.styleable.ScreenHeaderView_headerTitle));
            setSubtitle(a.getText(R.styleable.ScreenHeaderView_headerSubtitle));
        } finally {
            a.recycle();
        }
    }

    public void setTitle(@Nullable CharSequence title) {
        binding.headerTitle.setText(title);
    }

    public void setSubtitle(@Nullable CharSequence subtitle) {
        binding.headerSubtitle.setText(subtitle);
        binding.headerSubtitle.setVisibility(subtitle == null || subtitle.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
