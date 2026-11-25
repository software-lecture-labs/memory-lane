package com.memorylane.feature.memories.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;

/**
 * The timeline's continuous pastel guide line (Stitch "Anılar": 2px at left-2, behind the year
 * nodes). It is drawn once for the whole list instead of per row, so the gaps between cards do
 * not break it. It starts at the first year's node and ends with the last memory row; the
 * "load more" footer below the timeline stays clear of it.
 */
final class MemoryTimelineDecoration extends RecyclerView.ItemDecoration {
    private final RecyclerView.Adapter<?> rows;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float lineX;

    /** @param rows the adapter of the year and memory rows (not the footer) */
    MemoryTimelineDecoration(@NonNull Context context, @NonNull RecyclerView.Adapter<?> rows) {
        this.rows = rows;
        paint.setColor(ContextCompat.getColor(context, R.color.memory_timeline_line));
        paint.setStrokeWidth(context.getResources().getDimension(R.dimen.memory_timeline_line));
        paint.setStrokeCap(Paint.Cap.ROUND);
        lineX = context.getResources().getDimension(R.dimen.memory_timeline_line_x);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        float top = Float.NaN;
        float bottom = Float.NaN;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            RecyclerView.ViewHolder holder = parent.getChildViewHolder(child);
            if (holder.getBindingAdapter() != rows) continue;
            if (Float.isNaN(top)) {
                // The very first row is the first year: start at its node, not above it.
                top = holder.getBindingAdapterPosition() == 0 && holder instanceof MemoryListAdapter.YearHolder
                        ? child.getTop() + ((MemoryListAdapter.YearHolder) holder).nodeCenterY()
                        : parent.getPaddingTop();
            }
            bottom = child.getBottom();
        }
        if (Float.isNaN(top) || bottom <= top) return;
        float x = parent.getPaddingLeft() + lineX;
        canvas.drawLine(x, Math.max(top, 0), x, bottom, paint);
    }
}
