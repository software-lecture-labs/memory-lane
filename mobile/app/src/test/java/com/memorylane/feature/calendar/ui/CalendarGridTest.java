package com.memorylane.feature.calendar.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public class CalendarGridTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);
    private static final CalendarNote NOTE = new CalendarNote("m1", "Kadıköy'de kahve molası", "", DAY);

    @Test
    public void monthStartsOnTheMondayOfItsFirstWeekAndAlwaysHasSixWeeks() {
        // 1 November 2026 is a Sunday, so the grid opens with Monday 26 October.
        List<CalendarDay> days = CalendarGrid.build(YearMonth.of(2026, 11), Map.of());

        assertEquals(42, days.size());
        assertEquals(LocalDate.of(2026, 10, 26), days.get(0).date());
        assertEquals(DayOfWeek.MONDAY, days.get(0).date().getDayOfWeek());
        assertEquals(LocalDate.of(2026, 12, 6), days.get(41).date());
    }

    @Test
    public void monthStartingOnMondayHasNoDaysOfThePreviousMonth() {
        // 1 June 2026 is a Monday.
        List<CalendarDay> days = CalendarGrid.build(YearMonth.of(2026, 6), Map.of());

        assertEquals(LocalDate.of(2026, 6, 1), days.get(0).date());
        assertTrue(days.get(0).inMonth());
        assertEquals(42, days.size());
    }

    @Test
    public void onlyDaysOfTheMonthCarryTheirNotes() {
        List<CalendarDay> days = CalendarGrid.build(YearMonth.of(2026, 11), Map.of(DAY, List.of(NOTE)));

        CalendarDay eighth = days.stream().filter(day -> day.date().equals(DAY)).findFirst().orElseThrow();
        assertEquals(List.of(NOTE), eighth.notes());
        assertFalse(days.get(0).inMonth());
        assertFalse(days.get(0).hasNotes());
    }

    @Test
    public void aNoteOfTheNeighbouringMonthIsNotShownOnItsFadedDay() {
        // 26 October is drawn faded in November's grid; its note belongs to October's page.
        LocalDate october26 = LocalDate.of(2026, 10, 26);
        CalendarNote octoberNote = new CalendarNote("m2", "Ekim anısı", "", october26);

        List<CalendarDay> days = CalendarGrid.build(YearMonth.of(2026, 11), Map.of(october26, List.of(octoberNote)));

        assertEquals(october26, days.get(0).date());
        assertFalse(days.get(0).hasNotes());
    }
}
