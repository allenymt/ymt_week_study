package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekCalculatorTest {

    private val cfg = RuleConfig()

    private fun day(d: Long, vararg items: Item) = DayRecord(d, items.toSet())

    /** 造一周 7 天。 */
    private fun week(vararg days: DayRecord): List<DayRecord> = days.toList()

    @Test
    fun base_minutes_are_granted_unconditionally() {
        // 一条记录都没有，保底仍给 60
        val s = WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        assertEquals(60, s.baseMinutes)
    }

    @Test
    fun no_complaint_bonus_requires_all_seven_days_clean() {
        val clean = (0L..6L).map { day(it, Item.RAISED) }
        val s1 = WeekCalculator.summarize(clean, emptyList(), WeekMeta(), cfg)
        assertEquals(60, s1.noComplaintBonusMinutes)

        val withComplaint = (0L..6L).map {
            if (it == 3L) day(it, Item.COMPLAINT) else day(it, Item.RAISED)
        }
        val s2 = WeekCalculator.summarize(withComplaint, emptyList(), WeekMeta(), cfg)
        assertEquals(0, s2.noComplaintBonusMinutes)
    }

    @Test
    fun total_earned_sums_all_sources() {
        val days = (0L..6L).map { day(it, Item.RAISED) }        // 7 * 10 = 70
        val exams = listOf(ExamRecord(Subject.ENGLISH, 95))     // +30
        val s = WeekCalculator.summarize(days, exams, WeekMeta(), cfg)
        assertEquals(70, s.dailyTotal)
        assertEquals(30, s.examTotal)
        assertEquals(60 + 60 + 70 + 30, s.totalEarned)          // 220
    }

    @Test
    fun week_cap_clamps_total_and_reports_overflow() {
        // 造出超过 240 的一周：7 天全满分 = 7*60 = 420 加分
        val days = (0L..6L).map {
            day(it, Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH,
                Item.READING, Item.EXERCISE)
        }
        val s = WeekCalculator.summarize(days, emptyList(), WeekMeta(), cfg)
        assertEquals(420, s.dailyTotal)
        assertEquals(240, s.cappedEarned)
        assertEquals(240, s.totalEarned)          // 给上限 240 设为上限
        assertEquals(120, s.overflow)             // 超出部分
    }

    @Test
    fun negative_daily_total_reduces_earned() {
        val days = listOf(day(0, Item.COMPLAINT), day(1, Item.COMPLAINT))
        val s = WeekCalculator.summarize(days, emptyList(), WeekMeta(), cfg)
        assertEquals(-60, s.dailyTotal)
        // 60 保底 + 0 无投诉奖 - 60 = 0
        assertEquals(0, s.totalEarned)
    }

    @Test
    fun balance_subtracts_used_overtime_and_redeemed() {
        val days = (0L..6L).map { day(it, Item.RAISED) }
        val meta = WeekMeta(usedMinutes = 30, overtimeMinutes = 10, redeemedMinutes = 60)
        val s = WeekCalculator.summarize(days, emptyList(), meta, cfg)
        // 60 + 60 + 70 - 30 - 10 - 60 = 90
        assertEquals(90, s.balance)
    }

    @Test
    fun balance_can_go_negative_when_overused() {
        val meta = WeekMeta(usedMinutes = 500)
        val s = WeekCalculator.summarize(emptyList(), emptyList(), meta, cfg)
        // 60 - 500 = -440
        assertEquals(-440, s.balance)
        assertTrue(s.balance < 0)
    }

    @Test
    fun overflow_is_zero_when_under_cap() {
        val s = WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        assertEquals(0, s.overflow)
    }
}
