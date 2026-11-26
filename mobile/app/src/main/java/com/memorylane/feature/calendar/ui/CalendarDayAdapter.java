package com.memorylane.feature.calendar.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;
import com.memorylane.databinding.ItemCalendarDayBinding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** The 42 day cells of the month grid. */
final class CalendarDayAdapter extends ListAdapter<CalendarDay, CalendarDayAdapter.DayHolder> {
    private static final int[] NOTE_TINTS = {
            R.color.calendar_note_butter, R.color.calendar_note_peach,
            R.color.calendar_note_lavender, R.color.calendar_note_mint};

    private static final DiffUtil.ItemCallback<CalendarDay> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull CalendarDay oldItem, @NonNull CalendarDay newItem) {
            return oldItem.date().equals(newItem.date());
        }

        @Override
        public boolean areContentsTheSame(@NonNull CalendarDay oldItem, @NonNull CalendarDay newItem) {
            return oldItem.equals(newItem);
        }
    };

    private final Consumer<LocalDate> onPick;
    private final DateTimeFormatter spokenDate;
    @Nullable private LocalDate selected;
    @Nullable private LocalDate today;

    CalendarDayAdapter(@NonNull Consumer<LocalDate> onPick, @NonNull Locale locale) {
        super(DIFF);
        this.onPick = onPick;
        this.spokenDate = DateTimeFormatter.ofPattern("d MMMM EEEE", locale);
    }

    /** Selection and today are drawn on the cells; changing them redraws only what changed. */
    void setMarks(@Nullable LocalDate selected, @NonNull LocalDate today) {
        LocalDate previous = this.selected;
        boolean todayChanged = !today.equals(this.today);
        this.selected = selected;
        this.today = today;
        for (int i = 0; i < getItemCount(); i++) {
            LocalDate date = getItem(i).date();
            if (todayChanged || date.equals(previous) || date.equals(selected)) notifyItemChanged(i);
        }
    }

    /** Sticky-note color of a day; stable, so a day keeps its color across months and reloads. */
    @ColorRes
    static int noteTint(@NonNull LocalDate date) {
        return NOTE_TINTS[(int) Math.floorMod(date.toEpochDay(), (long) NOTE_TINTS.length)];
    }

    @NonNull
    @Override
    public DayHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DayHolder(ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull DayHolder holder, int position) {
        CalendarDay day = getItem(position);
        holder.bind(day, day.date().equals(selected), day.date().equals(today));
    }

    final class DayHolder extends RecyclerView.ViewHolder {
        private final ItemCalendarDayBinding binding;

        DayHolder(ItemCalendarDayBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CalendarDay day, boolean isSelected, boolean isToday) {
            Context context = binding.getRoot().getContext();
            binding.calendarDayNumber.setText(String.format(Locale.ROOT, "%d", day.date().getDayOfMonth()));
            binding.calendarDayNumber.setTextColor(ContextCompat.getColor(context,
                    !day.inMonth() ? R.color.calendar_outside_month
                            : isToday ? R.color.ml_primary : R.color.ml_plum));

            int count = day.notes().size();
            if (count > 0) {
                binding.calendarDayNote.setVisibility(View.VISIBLE);
                binding.calendarDayNote.setText(day.notes().get(0).title());
                binding.calendarDayNote.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(context, noteTint(day.date()))));
                // Each day's note sits a little askew, like notes stuck on by hand.
                binding.calendarDayNote.setRotation(day.date().getDayOfMonth() % 2 == 0 ? -3f : 2f);
            } else {
                binding.calendarDayNote.setVisibility(View.GONE);
            }
            binding.calendarDayMore.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            binding.calendarDayMore.setText(String.format(Locale.ROOT, "+%d", count - 1));

            View cell = binding.calendarCell;
            cell.setBackgroundResource(isSelected ? R.drawable.calendar_day_selected
                    : isToday && day.inMonth() ? R.drawable.calendar_day_today : 0);
            cell.setSelected(isSelected);
            cell.setContentDescription(describe(context, day));
            if (day.inMonth()) {
                cell.setOnClickListener(view -> onPick.accept(day.date()));
                cell.setClickable(true);
            } else {
                cell.setOnClickListener(null);
                cell.setClickable(false);
            }
            cell.setImportantForAccessibility(day.inMonth()
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private String describe(Context context, CalendarDay day) {
            String date = spokenDate.format(day.date());
            int count = day.notes().size();
            return count == 0 ? date
                    : context.getResources().getQuantityString(R.plurals.calendar_day_description, count,
                            date, count, Objects.requireNonNull(day.notes().get(0).title()));
        }
    }
}
