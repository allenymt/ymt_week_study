package com.vdian.screentime.ui

import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.WeekDates
import org.junit.Assert.assertEquals
import org.junit.Test

class DayLabelsTest {

    @Test
    fun weekday_names_start_from_monday() {
        assertEquals("周一", DayLabels.weekdayName(0))
        assertEquals("周三", DayLabels.weekdayName(2))
        assertEquals("周日", DayLabels.weekdayName(6))
    }

    @Test
    fun item_names_match_the_paper_table() {
        assertEquals("举手回答", DayLabels.itemName(Item.RAISED))
        assertEquals("字认真", DayLabels.itemName(Item.NEAT_WRITING))
        assertEquals("主动英语", DayLabels.itemName(Item.ENGLISH))
        assertEquals("主动阅读", DayLabels.itemName(Item.READING))
        assertEquals("主动运动", DayLabels.itemName(Item.EXERCISE))
        assertEquals("投诉", DayLabels.itemName(Item.COMPLAINT))
    }

    @Test
    fun date_range_spans_seven_days() {
        val monday = WeekDates.toEpochDay(2026, 9, 21)
        assertEquals("9月21日 - 9月27日", DayLabels.formatDateRange(monday))
    }

    @Test
    fun weekday_name_handles_out_of_range_gracefully() {
        assertEquals("", DayLabels.weekdayName(7))
        assertEquals("", DayLabels.weekdayName(-1))
    }
}
