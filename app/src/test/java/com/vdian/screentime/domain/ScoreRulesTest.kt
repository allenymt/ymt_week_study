package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRulesTest {

    private val cfg = RuleConfig()
    private val catalog = ItemCatalog.builtin()

    private fun day(d: Long, vararg items: Item) =
        DayRecord(d, items.toSet())

    @Test
    fun itemPoints_match_the_paper_table() {
        assertEquals(10, ScoreRules.itemPoints(Item.RAISED, catalog))
        assertEquals(10, ScoreRules.itemPoints(Item.NEAT_WRITING, catalog))
        assertEquals(10, ScoreRules.itemPoints(Item.ENGLISH, catalog))
        assertEquals(15, ScoreRules.itemPoints(Item.READING, catalog))
        assertEquals(15, ScoreRules.itemPoints(Item.EXERCISE, catalog))
    }

    @Test
    fun complaint_penalty_is_negative() {
        assertEquals(-30, ScoreRules.itemPoints(Item.COMPLAINT, catalog))
    }

    @Test
    fun daily_score_sums_confirmed_items_only() {
        val d = day(0, Item.RAISED, Item.READING, Item.EXERCISE)
        assertEquals(40, ScoreRules.dailyScore(d, emptyMap(), catalog))
    }

    @Test
    fun pending_items_do_not_score() {
        val d = DayRecord(0, items = setOf(Item.RAISED), pending = setOf(Item.READING))
        assertEquals(10, ScoreRules.dailyScore(d, emptyMap(), catalog))
    }

    @Test
    fun repeated_selection_is_idempotent() {
        val once = day(0, Item.RAISED)
        val twice = DayRecord(0, items = setOf(Item.RAISED, Item.RAISED))
        assertEquals(ScoreRules.dailyScore(once, emptyMap(), catalog),
                     ScoreRules.dailyScore(twice, emptyMap(), catalog))
    }

    @Test
    fun negative_daily_score_is_not_clamped() {
        // 一天被投诉两次 = -60，必须原样保留
        val d = day(0, Item.RAISED, Item.COMPLAINT, Item.COMPLAINT)
        assertEquals(-50, ScoreRules.dailyScore(d, mapOf(0L to 2), catalog))
    }

    @Test
    fun complaint_counts_only_first_two_per_week() {
        val days = listOf(
            day(0, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(2, Item.COMPLAINT),
            day(3, Item.COMPLAINT)
        )
        assertEquals(2, ScoreRules.effectiveComplaintCount(days, cfg, catalog))
    }

    @Test
    fun third_complaint_scores_zero_but_stays_recorded() {
        val days = listOf(
            day(0, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(2, Item.COMPLAINT)
        )
        val scores = ScoreRules.dailyScores(days, cfg, catalog)
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
        val scores = ScoreRules.dailyScores(days, cfg, catalog)
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
        val (items, pending) = ScoreRules.advance(Item.READING, emptySet(), setOf(Item.READING), catalog)
        assertTrue(items.contains(Item.READING))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun advance_on_confirmed_item_removes_it() {
        val (items, pending) = ScoreRules.advance(Item.READING, setOf(Item.READING), emptySet(), catalog)
        assertFalse(items.contains(Item.READING))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun advance_on_empty_cell_confirms_directly() {
        val (items, _) = ScoreRules.advance(Item.ENGLISH, emptySet(), emptySet(), catalog)
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

    // ---- 1.1：新扣分项与互斥 ----

    @Test
    fun deduction_item_counts_toward_daily_subtotal() {
        val d = day(0, Item.NEAT_WRITING_BAD, Item.RAISED)
        assertEquals(0, ScoreRules.dailyScores(listOf(d), cfg, catalog).single())
    }

    @Test
    fun new_deduction_item_is_not_limited_by_weekly_complaint_cap() {
        val days = (0L..3L).map { day(it, Item.HOMEWORK_PASSIVE) }
        assertEquals(-40, ScoreRules.dailyTotal(days, cfg, catalog))
        assertEquals(0, ScoreRules.effectiveComplaintCount(days, cfg, catalog))
    }

    @Test
    fun parent_confirm_replaces_confirmed_item_in_same_group() {
        val (items, _) = ScoreRules.advance(
            Item.NEAT_WRITING_BAD, setOf(Item.NEAT_WRITING), emptySet(), catalog
        )
        assertEquals(setOf(Item.NEAT_WRITING_BAD), items)
    }

    @Test
    fun parent_confirm_clears_pending_item_in_same_group() {
        val (items, pending) = ScoreRules.advance(
            Item.HOMEWORK_PASSIVE, emptySet(), setOf(Item.HOMEWORK_ACTIVE), catalog
        )
        assertEquals(setOf(Item.HOMEWORK_PASSIVE), items)
        assertFalse(pending.contains(Item.HOMEWORK_ACTIVE))
    }

    @Test
    fun parent_confirm_leaves_other_groups_untouched() {
        val (items, pending) = ScoreRules.advance(
            Item.NEAT_WRITING, setOf(Item.RAISED, Item.HOMEWORK_ACTIVE), setOf(Item.READING), catalog
        )
        assertEquals(setOf(Item.RAISED, Item.HOMEWORK_ACTIVE, Item.NEAT_WRITING), items)
        assertEquals(setOf(Item.READING), pending)
    }

    @Test
    fun child_claim_rejected_when_group_already_confirmed() {
        val pending = ScoreRules.claim(
            Item.NEAT_WRITING_BAD, setOf(Item.NEAT_WRITING), emptySet(), catalog
        )
        assertEquals(emptySet<Item>(), pending)
        // 用可申报的项再验一次，排除 childClaimable 的干扰
        val pending2 = ScoreRules.claim(
            Item.HOMEWORK_ACTIVE, setOf(Item.HOMEWORK_PASSIVE), setOf(Item.READING), catalog
        )
        assertEquals(setOf(Item.READING), pending2)
    }

    @Test
    fun child_claim_rejected_when_group_already_has_pending_item() {
        // 同组另一项已待确认（例如家长之前直接录入后又被改成待确认的情形），不能再报第二个
        val custom = ItemCatalog(catalog.defs.map {
            if (it.item == Item.NEAT_WRITING_BAD) it.copy(childClaimable = true) else it
        })
        val pending = ScoreRules.claim(
            Item.NEAT_WRITING_BAD, emptySet(), setOf(Item.NEAT_WRITING, Item.READING), custom
        )
        assertEquals(setOf(Item.NEAT_WRITING, Item.READING), pending)

        val pending2 = ScoreRules.claim(
            Item.HOMEWORK_ACTIVE, setOf(Item.RAISED), setOf(Item.HOMEWORK_PASSIVE), catalog
        )
        assertEquals(setOf(Item.HOMEWORK_PASSIVE), pending2)
    }

    @Test
    fun child_claim_on_already_pending_item_is_idempotent() {
        val pending = ScoreRules.claim(Item.NEAT_WRITING, emptySet(), setOf(Item.NEAT_WRITING), catalog)
        assertEquals(setOf(Item.NEAT_WRITING), pending)
    }

    @Test
    fun child_claim_adds_to_pending_when_no_conflict() {
        val pending = ScoreRules.claim(Item.NEAT_WRITING, setOf(Item.RAISED), emptySet(), catalog)
        assertEquals(setOf(Item.NEAT_WRITING), pending)
    }

    @Test
    fun child_claim_on_confirmed_item_is_a_noop() {
        val pending = ScoreRules.claim(Item.RAISED, setOf(Item.RAISED), emptySet(), catalog)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun disabled_item_still_scores() {
        val disabled = ItemCatalog(catalog.defs.map {
            if (it.item == Item.READING) it.copy(enabled = false) else it
        })
        val d = day(0, Item.READING, Item.RAISED)
        assertEquals(
            ScoreRules.dailyScores(listOf(d), cfg, catalog),
            ScoreRules.dailyScores(listOf(d), cfg, disabled)
        )
        assertEquals(25, ScoreRules.dailyScores(listOf(d), cfg, disabled).single())
    }

    @Test
    fun unknown_key_scores_zero_without_crashing() {
        val d = day(0, Item("NO_SUCH_ITEM"), Item.RAISED)
        assertEquals(0, ScoreRules.itemPoints(Item("NO_SUCH_ITEM"), catalog))
        assertEquals(10, ScoreRules.dailyScores(listOf(d), cfg, catalog).single())
        val (items, _) = ScoreRules.advance(Item("NO_SUCH_ITEM"), setOf(Item.RAISED), emptySet(), catalog)
        assertEquals(setOf(Item.RAISED, Item("NO_SUCH_ITEM")), items)
    }
}
