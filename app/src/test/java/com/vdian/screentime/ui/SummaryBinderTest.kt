package com.vdian.screentime.ui

import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekCalculator
import com.vdian.screentime.domain.WeekMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryBinderTest {

    private val cfg = RuleConfig()

    private fun lineMap(summary: com.vdian.screentime.domain.WeekSummary) =
        SummaryBinder.lines(summary).toMap()

    @Test
    fun lines_cover_every_field_on_the_paper_summary_table() {
        val s = WeekCalculator.summarize(
            (0L..6L).map { DayRecord(it, setOf(Item.RAISED)) },
            listOf(ExamRecord(Subject.ENGLISH, 95)),
            WeekMeta(usedMinutes = 30),
            cfg
        )
        val m = lineMap(s)
        assertEquals("60", m["保底"])
        assertEquals("60", m["一周无投诉"])
        assertEquals("70", m["每日加分合计"])
        assertEquals("30", m["考试奖励合计"])
        assertEquals("0", m["投诉扣分"])
        assertEquals("220", m["本周总赚取"])
        assertEquals("30", m["本周已使用"])
        assertEquals("190", m["本周结余"])
    }

    @Test
    fun complaint_penalty_line_is_negative() {
        val s = WeekCalculator.summarize(
            listOf(DayRecord(0, setOf(Item.COMPLAINT))),
            emptyList(), WeekMeta(), cfg
        )
        assertEquals("-30", lineMap(s)["投诉扣分"])
    }

    @Test
    fun lines_are_ordered_for_display() {
        val names = SummaryBinder.lines(
            WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        ).map { it.first }
        assertEquals(
            listOf(
                "保底", "一周无投诉", "每日加分合计", "考试奖励合计",
                "投诉扣分", "本周总赚取", "本周已使用", "本周结余"
            ),
            names
        )
    }

    @Test
    fun overflow_hint_is_shown_only_when_capped() {
        val under = WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        assertEquals("", SummaryBinder.overflowHint(under))

        val days = (0L..6L).map {
            DayRecord(it, setOf(Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH,
                Item.READING, Item.EXERCISE))
        }
        val over = WeekCalculator.summarize(days, emptyList(), WeekMeta(), cfg)
        assertEquals("已封顶 4 小时，超出 120 分钟可换小奖品", SummaryBinder.overflowHint(over))
    }
}
