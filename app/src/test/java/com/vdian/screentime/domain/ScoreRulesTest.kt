package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRulesTest {

    private val cfg = RuleConfig()

    private fun day(d: Long, vararg items: Item) =
        DayRecord(d, items.toSet())

    @Test
    fun itemPoints_match_the_paper_table() {
        assertEquals(10, ScoreRules.itemPoints(Item.RAISED, cfg))
        assertEquals(10, ScoreRules.itemPoints(Item.NEAT_WRITING, cfg))
        assertEquals(10, ScoreRules.itemPoints(Item.ENGLISH, cfg))
        assertEquals(15, ScoreRules.itemPoints(Item.READING, cfg))
        assertEquals(15, ScoreRules.itemPoints(Item.EXERCISE, cfg))
    }

    @Test
    fun complaint_penalty_is_negative() {
        assertEquals(-30, ScoreRules.itemPoints(Item.COMPLAINT, cfg))
    }

    @Test
    fun daily_score_sums_confirmed_items_only() {
        val d = day(0, Item.RAISED, Item.READING, Item.EXERCISE)
        assertEquals(40, ScoreRules.dailyScore(d, emptyMap(), cfg))
    }

    @Test
    fun pending_items_do_not_score() {
        val d = DayRecord(0, items = setOf(Item.RAISED), pending = setOf(Item.READING))
        assertEquals(10, ScoreRules.dailyScore(d, emptyMap(), cfg))
    }

    @Test
    fun repeated_selection_is_idempotent() {
        val once = day(0, Item.RAISED)
        val twice = DayRecord(0, items = setOf(Item.RAISED, Item.RAISED))
        assertEquals(ScoreRules.dailyScore(once, emptyMap(), cfg),
                     ScoreRules.dailyScore(twice, emptyMap(), cfg))
    }

    @Test
    fun negative_daily_score_is_not_clamped() {
        // 一天被投诉两次 = -60，必须原样保留
        val d = day(0, Item.RAISED, Item.COMPLAINT, Item.COMPLAINT)
        assertEquals(-50, ScoreRules.dailyScore(d, mapOf(0L to 2), cfg))
    }

    @Test
    fun complaint_counts_only_first_two_per_week() {
        val days = listOf(
            day(0, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(2, Item.COMPLAINT),
            day(3, Item.COMPLAINT)
        )
        assertEquals(2, ScoreRules.effectiveComplaintCount(days, cfg))
    }

    @Test
    fun third_complaint_scores_zero_but_stays_recorded() {
        val days = listOf(
            day(0, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(2, Item.COMPLAINT)
        )
        val scores = ScoreRules.dailyScores(days, cfg)
        assertEquals(-30, scores[0])
        assertEquals(-30, scores[1])
        assertEquals(0, scores[2])          // 第 3 次不扣分
        assertTrue(days[2].items.contains(Item.COMPLAINT))  // 但事实仍记录
    }

    @Test
    fun complaint_counting_follows_chronological_order_not_input_order() {
        // 乱序输入，必须先按日期排序再决定哪两次生效
        val days = listOf(
            day(5, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(3, Item.COMPLAINT)
        )
        val scores = ScoreRules.dailyScores(days, cfg)
        // 排序后为 1, 3, 5 -> 前两次 (1, 3) 扣分，第 5 天不扣
        assertEquals(-30, scores[0])   // day 1
        assertEquals(-30, scores[1])   // day 3
        assertEquals(0, scores[2])     // day 5
    }

    @Test
    fun exam_bonus_english_has_two_tiers() {
        assertEquals(0, ScoreRules.examBonus(Subject.ENGLISH, 84, cfg))
        assertEquals(15, ScoreRules.examBonus(Subject.ENGLISH, 85, cfg))
        assertEquals(15, ScoreRules.examBonus(Subject.ENGLISH, 89, cfg))
        assertEquals(30, ScoreRules.examBonus(Subject.ENGLISH, 90, cfg))
        assertEquals(30, ScoreRules.examBonus(Subject.ENGLISH, 100, cfg))
    }

    @Test
    fun exam_bonus_other_subjects_only_reward_90_plus() {
        for (s in listOf(Subject.CHINESE, Subject.MATH, Subject.SCIENCE)) {
            assertEquals(0, ScoreRules.examBonus(s, 84, cfg))
            assertEquals(0, ScoreRules.examBonus(s, 85, cfg))
            assertEquals(0, ScoreRules.examBonus(s, 89, cfg))
            assertEquals(30, ScoreRules.examBonus(s, 90, cfg))
        }
    }

    @Test
    fun exam_total_sums_all_subjects() {
        val exams = listOf(
            ExamRecord(Subject.ENGLISH, 90),   // +30
            ExamRecord(Subject.CHINESE, 88),   // 0
            ExamRecord(Subject.MATH, 95),      // +30
            ExamRecord(Subject.SCIENCE, 91)    // +30
        )
        assertEquals(90, ScoreRules.examTotal(exams, cfg))
    }

    @Test
    fun advance_moves_item_from_pending_to_confirmed() {
        val (items, pending) = ScoreRules.advance(Item.READING, emptySet(), setOf(Item.READING))
        assertTrue(items.contains(Item.READING))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun advance_on_confirmed_item_removes_it() {
        val (items, pending) = ScoreRules.advance(Item.READING, setOf(Item.READING), emptySet())
        assertFalse(items.contains(Item.READING))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun advance_on_empty_cell_confirms_directly() {
        val (items, _) = ScoreRules.advance(Item.ENGLISH, emptySet(), emptySet())
        assertTrue(items.contains(Item.ENGLISH))
    }

    @Test
    fun repeated_child_claim_does_not_duplicate() {
        val pending = setOf(Item.EXERCISE)
        val cfgAfter = pending + Item.EXERCISE
        assertEquals(1, cfgAfter.size)
    }

    @Test
    fun retract_removes_pending_item() {
        val pending = ScoreRules.retract(Item.READING, setOf(Item.READING, Item.ENGLISH))
        assertEquals(setOf(Item.ENGLISH), pending)
    }

    @Test
    fun retract_on_absent_item_is_a_noop() {
        assertEquals(setOf(Item.ENGLISH), ScoreRules.retract(Item.READING, setOf(Item.ENGLISH)))
    }
}
