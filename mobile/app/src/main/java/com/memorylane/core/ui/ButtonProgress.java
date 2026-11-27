package com.memorylane.core.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import com.memorylane.R;

/**
 * A small spinner inside a button while its action runs, e.g. "Giriş yapılıyor…" on the login
 * button: the wait shows where the user tapped, and the button's own text says what is
 * happening (TalkBack reads it). {@link #hide} puts the original icon and text back.
 *
 * <p>Only the look changes; enabling and disabling the button stays with the screen.
 */
public final class ButtonProgress {
    private ButtonProgress() {
    }

    private record Original(@Nullable Drawable icon, CharSequence text) {}

    public static void show(@NonNull MaterialButton button, @NonNull CharSequence busyText) {
        if (button.getTag(R.id.tag_button_progress) == null) {
            button.setTag(R.id.tag_button_progress, new Original(button.getIcon(), button.getText()));
        }
        button.setIcon(spinner(button.getContext()));
        button.setText(busyText);
    }

    public static void hide(@NonNull MaterialButton button) {
        Object tag = button.getTag(R.id.tag_button_progress);
        if (!(tag instanceof Original)) return;
        Original original = (Original) tag;
        button.setTag(R.id.tag_button_progress, null);
        button.setIcon(original.icon());
        button.setText(original.text());
    }

    private static Drawable spinner(Context context) {
        CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(context, null);
        spec.indicatorSize = context.getResources().getDimensionPixelSize(R.dimen.loading_button_spinner);
        spec.trackThickness = context.getResources().getDimensionPixelSize(R.dimen.loading_button_spinner_track);
        spec.indicatorInset = 0;
        spec.indicatorColors = new int[]{ContextCompat.getColor(context, R.color.ml_on_primary)};
        return IndeterminateDrawable.createCircularDrawable(context, spec);
    }
}
