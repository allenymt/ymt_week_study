package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekDatesTest {

    @Test
    fun monday_is_its_own_week_start() {
        // 2026-09-21 是周一
        val monday = WeekDates.toEpochDay(2026, 9, 21)
        assertEquals(monday, WeekDates.weekStart(monday))
    }

    @Test
    fun sunday_belongs_to_the_preceding_monday() {
        // 2026-09-27 是周日，应归到 09-21 那一周
        val sunday = WeekDates.toEpochDay(2026, 9, 27)
        assertEquals(WeekDates.toEpochDay(2026, 9, 21), WeekDates.weekStart(sunday))
    }

    @Test
    fun week_crossing_year_boundary_stays_one_week() {
        // 2026-12-28 是周一，2027-01-03 是周日，必须同属一周
        val monday = WeekDates.toEpochDay(2026, 12, 28)
        val sunday = WeekDates.toEpochDay(2027, 1, 3)
        assertEquals(monday, WeekDates.weekStart(sunday))
        assertEquals(monday, WeekDates.weekStart(monday))
    }

    @Test
    fun dayOfWeek_is_zero_based_from_monday() {
        assertEquals(0, WeekDates.dayOfWeek(WeekDates.toEpochDay(2026, 9, 21)))
        assertEquals(6, WeekDates.dayOfWeek(WeekDates.toEpochDay(2026, 9, 27)))
    }

    @Test
    fun toEpochDay_roundtrips_known_date() {
        // 1970-01-01 是 epochDay 0
        assertEquals(0L, WeekDates.toEpochDay(1970, 1, 1))
        // 1970-01-02 是 epochDay 1
        assertEquals(1L, WeekDates.toEpochDay(1970, 1, 2))
    }
}
