package com.vdian.screentime.ui

import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
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

    @Test
    fun header_shows_item_name_and_points() {
        val cfg = RuleConfig()
        assertEquals("举手回答\n+10", DayLabels.itemHeader(Item.RAISED, cfg))
        assertEquals("主动阅读\n+15", DayLabels.itemHeader(Item.READING, cfg))
    }

    /** 投诉的分值本身就是负数，不能再补 "+"，否则显示成 "+-30"。 */
    @Test
    fun complaint_header_does_not_double_the_minus_sign() {
        val header = DayLabels.itemHeader(Item.COMPLAINT, RuleConfig())
        assertEquals("投诉\n-30", header)
    }

    @Test
    fun header_points_follow_the_config() {
        val cfg = RuleConfig(readingPoints = 20)
        assertEquals("主动阅读\n+20", DayLabels.itemHeader(Item.READING, cfg))
    }
}
