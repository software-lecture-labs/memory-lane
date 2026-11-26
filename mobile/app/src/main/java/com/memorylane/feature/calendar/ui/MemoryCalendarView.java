package com.memorylane.feature.calendar.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;

import com.memorylane.R;
import com.memorylane.core.ui.Motion;
import com.memorylane.databinding.ItemCalendarNoteBinding;
import com.memorylane.databinding.ItemCalendarReminderBinding;
import com.memorylane.databinding.ViewMemoryCalendarBinding;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Month calendar with a tiny note on every day that has a memory, and the picked day's
 * memories below the grid. Draws {@link MemoryCalendarUiState}; taps go to {@link Listener}.
 */
public class MemoryCalendarView extends LinearLayout {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    public interface Listener {
        void onPreviousMonth();

        void onNextMonth();

        void onDayPicked(@NonNull LocalDate day);
    }

    private final ViewMemoryCalendarBinding binding;
    private final CalendarDayAdapter adapter;
    private final DateTimeFormatter monthFormat;
    private final DateTimeFormatter dayFormat;
    @Nullable private Listener listener;
    @Nullable private YearMonth shownMonth;

    public MemoryCalendarView(Context context) {
        this(context, null);
    }

    public MemoryCalendarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        binding = ViewMemoryCalendarBinding.inflate(LayoutInflater.from(context), this);
        monthFormat = DateTimeFormatter.ofPattern(context.getString(R.string.calendar_month_pattern), TURKISH);
        dayFormat = DateTimeFormatter.ofPattern(context.getString(R.string.calendar_day_pattern), TURKISH);

        adapter = new CalendarDayAdapter(day -> {
            if (listener != null) listener.onDayPicked(day);
        }, TURKISH);
        binding.calendarDays.setLayoutManager(new GridLayoutManager(context, CalendarGrid.DAYS_PER_WEEK));
        binding.calendarDays.setAdapter(adapter);
        // Cells change in place; the default change fade would blink the whole grid.
        binding.calendarDays.setItemAnimator(null);

        binding.calendarPrevious.setOnClickListener(button -> {
            if (listener != null) listener.onPreviousMonth();
        });
        binding.calendarNext.setOnClickListener(button -> {
            if (listener != null) listener.onNextMonth();
        });
        addWeekdays(context);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void render(@NonNull MemoryCalendarUiState state) {
        LocalDate today = state.getToday();
        renderOnThisDay(state.getOnThisDay(), today);
        YearMonth month = state.getMonth();
        binding.calendarMonth.setText(monthFormat.format(month));
        int monthCount = state.getMonthCount();
        binding.calendarMonthCount.setText(monthCount == 0
                ? getContext().getString(R.string.calendar_month_empty)
                : getResources().getQuantityString(R.plurals.calendar_month_count, monthCount, monthCount));

        if (shownMonth != null && !shownMonth.equals(month)) {
            // Shared-axis feel: the new month slides in from the side it was asked from.
            Motion.slideIn(binding.calendarDays, month.isAfter(shownMonth));
        }
        shownMonth = month;
        adapter.submitList(state.getDays());
        adapter.setMarks(state.getSelected(), today);
        renderSelected(state);
    }

    /** "1 yıl önce bugün: …" rows; tapping one opens that day, in its own month. */
    private void renderOnThisDay(List<CalendarNote> notes, LocalDate today) {
        binding.calendarOnThisDayList.removeAllViews();
        binding.calendarOnThisDay.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (CalendarNote note : notes) {
            ItemCalendarReminderBinding row = ItemCalendarReminderBinding.inflate(
                    inflater, binding.calendarOnThisDayList, true);
            int years = today.getYear() - note.date().getYear();
            String ago = getResources().getQuantityString(R.plurals.calendar_years_ago, years, years);
            row.reminderYears.setText(ago);
            row.reminderTitle.setText(note.title());
            row.getRoot().setContentDescription(
                    getContext().getString(R.string.calendar_on_this_day_description, ago, note.title()));
            row.getRoot().setOnClickListener(view -> {
                if (listener != null) listener.onDayPicked(note.date());
            });
        }
    }

    private void renderSelected(MemoryCalendarUiState state) {
        LocalDate selected = state.getSelected();
        binding.calendarSelectedNotes.removeAllViews();
        if (selected == null) {
            binding.calendarSelectedDate.setText(R.string.calendar_pick_day);
            binding.calendarSelectedEmpty.setVisibility(View.GONE);
            return;
        }
        String day = dayFormat.format(selected);
        binding.calendarSelectedDate.setText(day);
        List<CalendarNote> notes = state.getSelectedNotes();
        binding.calendarSelectedEmpty.setVisibility(notes.isEmpty() ? View.VISIBLE : View.GONE);
        binding.calendarSelectedEmpty.setText(R.string.calendar_day_empty);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        int tint = ContextCompat.getColor(getContext(), CalendarDayAdapter.noteTint(selected));
        for (int i = 0; i < notes.size(); i++) {
            CalendarNote note = notes.get(i);
            ItemCalendarNoteBinding row = ItemCalendarNoteBinding.inflate(inflater, binding.calendarSelectedNotes, true);
            row.calendarNoteSwatch.setBackgroundTintList(ColorStateList.valueOf(tint));
            row.calendarNoteTitle.setText(note.title());
            boolean hasBody = !note.description().trim().isEmpty();
            row.calendarNoteBody.setText(hasBody ? note.description() : null);
            row.calendarNoteBody.setVisibility(hasBody ? View.VISIBLE : View.GONE);
            Motion.riseIn(row.getRoot(), 40L * i);
        }
    }

    /** Weekday initials from Monday, in Turkish ("Pzt", "Sal", …). */
    private void addWeekdays(Context context) {
        for (int i = 0; i < CalendarGrid.DAYS_PER_WEEK; i++) {
            TextView label = new TextView(context);
            TextViewCompat.setTextAppearance(label, R.style.TextAppearance_MemoryLane_Caption);
            label.setGravity(Gravity.CENTER);
            label.setText(DayOfWeek.MONDAY.plus(i).getDisplayName(TextStyle.SHORT, TURKISH));
            binding.calendarWeekdays.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
    }
}
