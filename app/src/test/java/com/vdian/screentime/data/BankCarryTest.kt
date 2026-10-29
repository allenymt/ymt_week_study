package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 默认配置下一个没有任何日记录的周：保底 60 + 无投诉奖 60 = 120 分钟。
 * 用 usedMinutes 调出想要的周结余：used=20 → +100，used=70 → +50。
 */
class BankCarryTest {

    private val cfg = RuleConfig()
    private val catalog = ItemCatalog.builtin()

    private val w1 = 0L
    private val w2 = 7L
    private val w3 = 14L

    private fun board(
        weekStart: Long,
        row: WeekEntity?,
        fallback: BankOpening?,
        exams: List<ExamRecord> = emptyList()
    ) = WeekBoardAssembler.build(weekStart, emptyList(), exams, row, fallback, cfg, catalog)

    /** 正确的三周链：第 1 周 +100（并人工存入 200 分），第 2 周 +50。 */
    private fun correctChain() = listOf(
        WeekEntity(weekStart = w1, usedMinutes = 20, savingAdjustCents = 200),
        WeekEntity(weekStart = w2, usedMinutes = 70, prevBankMinutes = 100, prevSavingCents = 200),
        WeekEntity(weekStart = w3, prevBankMinutes = 150, prevSavingCents = 200)
    )

    @Test
    fun three_week_chain_opening_of_week3_is_150() {
        val replayed = BankCarry.replay(correctChain().take(2), emptyMap(), emptyMap(), cfg, catalog)
        val row1 = replayed[0]
        val row2 = replayed[1]
        assertEquals(100, board(w1, row1, null).bank.bankBalanceMinutes)
        assertEquals(100, row2.prevBankMinutes)
        // 第 3 周行尚不存在：期初 = 第 2 周期末
        val opening3 = BankCarry.closingOf(row2, emptyList(), emptyList(), cfg, catalog)
        val week3 = board(w3, null, opening3)
        assertEquals(150, week3.prevBankMinutes)
        assertEquals(200, week3.prevSavingCents)
        // 旧算法取"上一周行的期初"（row2.prevBankMinutes = 100）作为本周期初，会得到 100
        assertNotEquals(row2.prevBankMinutes, week3.prevBankMinutes)
    }

    @Test
    fun week3_with_own_row_reads_its_own_opening() {
        val rows = correctChain()
        assertEquals(150, board(w3, rows[2], BankOpening(0, 0)).prevBankMinutes)
    }

    @Test
    fun week2_row_exists_week3_missing_opening_is_week2_closing() {
        val row2 = WeekEntity(weekStart = w2, usedMinutes = 70, prevBankMinutes = 100, prevSavingCents = 200)
        val closing2 = board(w2, row2, null).closing()
        val opening3 = BankCarry.closingOf(row2, emptyList(), emptyList(), cfg, catalog)
        assertEquals(closing2, opening3)
        assertEquals(BankOpening(150, 200), opening3)
        assertEquals(150, board(w3, null, opening3).prevBankMinutes)
    }

    @Test
    fun exam_reward_of_previous_week_is_carried() {
        val row1 = WeekEntity(weekStart = w1, usedMinutes = 20)
        val exams1 = listOf(ExamRecord(Subject.ENGLISH, 95))
        val opening2 = BankCarry.closingOf(row1, emptyList(), exams1, cfg, catalog)
        assertEquals(130, opening2.bankMinutes)          // 100 + 英语 95 分 30
        // 不带考试记录时是 100——这正是旧 ensureWeekRow 的错误结果
        assertEquals(100, BankCarry.closingOf(row1, emptyList(), emptyList(), cfg, catalog).bankMinutes)

        val replayed = BankCarry.replay(
            listOf(row1, WeekEntity(weekStart = w2)),
            emptyMap(), mapOf(w1 to exams1), cfg, catalog
        )
        assertEquals(130, replayed[1].prevBankMinutes)
    }

    @Test
    fun replay_fixes_values_written_by_old_algorithm() {
        // 旧算法：第 2 行期初 = 第 1 周期末（用第 1 周的"上一行期初"=0 算），恰好是 100；
        // 第 3 行期初 = 第 2 周期末，但用的是第 1 行的期初 0 而非 100，于是得 50。
        val old = listOf(
            WeekEntity(weekStart = w1, usedMinutes = 20, savingAdjustCents = 200),
            WeekEntity(weekStart = w2, usedMinutes = 70, prevBankMinutes = 100, prevSavingCents = 200),
            WeekEntity(weekStart = w3, prevBankMinutes = 50, prevSavingCents = 0)
        )
        val replayed = BankCarry.replay(old, emptyMap(), emptyMap(), cfg, catalog)
        assertEquals(correctChain(), replayed)
    }

    @Test
    fun replay_leaves_correct_data_unchanged() {
        val rows = correctChain()
        assertEquals(rows, BankCarry.replay(rows, emptyMap(), emptyMap(), cfg, catalog))
    }

    @Test
    fun replay_sorts_and_starts_first_row_at_zero() {
        val rows = correctChain().map { it.copy(prevBankMinutes = 7, prevSavingCents = 7) }.reversed()
        val replayed = BankCarry.replay(rows, emptyMap(), emptyMap(), cfg, catalog)
        assertEquals(listOf(w1, w2, w3), replayed.map { it.weekStart })
        assertEquals(correctChain(), replayed)
    }

    @Test
    fun replay_of_empty_list_is_empty() {
        assertEquals(emptyList<WeekEntity>(), BankCarry.replay(emptyList(), emptyMap(), emptyMap(), cfg, catalog))
    }

    // ---- 2c：银行余额完全派生 ------------------------------------------------

    private fun openings(rows: List<WeekEntity>) = rows.map { it.prevBankMinutes }

    private fun replay(
        rows: List<WeekEntity>,
        days: Map<Long, List<DayRecord>> = emptyMap(),
        exams: Map<Long, List<ExamRecord>> = emptyMap(),
        config: RuleConfig = cfg,
        items: ItemCatalog = catalog
    ) = BankCarry.replay(rows, days, exams, config, items)

    @Test
    fun backfill_week1_plus_30_shifts_later_openings_by_30() {
        val rows = correctChain()
        val before = replay(rows)
        assertEquals(listOf(0, 100, 150), openings(before))
        // 补记第 1 周：英语 95 分，考试奖励 +30
        val after = replay(before, exams = mapOf(w1 to listOf(ExamRecord(Subject.ENGLISH, 95))))
        assertEquals(listOf(0, 130, 180), openings(after))
        // 只动期初，其余字段不变
        assertEquals(before.map { it.copy(prevBankMinutes = 0) }, after.map { it.copy(prevBankMinutes = 0) })
    }

    @Test
    fun backfill_plus_30_is_absorbed_by_bank_cap_480() {
        // 每周 +120：期初 0,120,240,360,480(封顶)
        val rows = (0 until 5).map { WeekEntity(weekStart = it * 7L) }
        assertEquals(listOf(0, 120, 240, 360, 480), openings(replay(rows)))
        val after = replay(rows, exams = mapOf(w1 to listOf(ExamRecord(Subject.ENGLISH, 95))))
        // 第 2~4 周期初 +30；第 5 周已封顶，仍为 480
        assertEquals(listOf(0, 150, 270, 390, 480), openings(after))
    }

    @Test
    fun item_points_change_shifts_whole_chain() {
        // 第 1 周有 3 天"举手回答"
        val days = mapOf(w1 to (0L..2L).map { DayRecord(it, items = setOf(Item.RAISED)) })
        val rows = correctChain()
        assertEquals(listOf(0, 130, 180), openings(replay(rows, days)))
        // 分值 10 → 20：第 1 周多 30，之后各周期初随之 +30
        val doubled = ItemCatalog.builtin(mapOf("raisePoints" to 20))
        assertEquals(listOf(0, 160, 210), openings(replay(rows, days, items = doubled)))
    }

    @Test
    fun rule_config_change_shifts_whole_chain() {
        val rows = correctChain()
        // 保底 60 → 90：每周多 30，累积到后面各周
        val after = replay(rows, config = cfg.copy(baseMinutes = 90))
        assertEquals(listOf(0, 130, 210), openings(after))
    }

    @Test
    fun single_row_chain_opening_is_zero() {
        val row = WeekEntity(weekStart = w2, usedMinutes = 70, prevBankMinutes = 123, prevSavingCents = 456)
        assertEquals(listOf(row.copy(prevBankMinutes = 0, prevSavingCents = 0)), replay(listOf(row)))
    }

    @Test
    fun negative_week_balance_clamps_bank_to_zero() {
        val rows = listOf(
            WeekEntity(weekStart = w1, usedMinutes = 20),     // +100
            WeekEntity(weekStart = w2, usedMinutes = 300),    // -180：100-180 → 夹 0
            WeekEntity(weekStart = w3)
        )
        assertEquals(listOf(0, 100, 0), openings(replay(rows)))
        // 第一周就为负也夹 0
        val first = listOf(WeekEntity(weekStart = w1, usedMinutes = 200), WeekEntity(weekStart = w2))
        assertEquals(listOf(0, 0), openings(replay(first)))
    }

    @Test
    fun accumulation_beyond_480_stays_capped() {
        val rows = (0 until 7).map { WeekEntity(weekStart = it * 7L) }
        assertEquals(listOf(0, 120, 240, 360, 480, 480, 480), openings(replay(rows)))
        // 封顶之后有一周花掉 100，下一周从 380 起而不是从"超额累计"起
        val spent = rows.toMutableList().also { it[4] = it[4].copy(usedMinutes = 220) }
        assertEquals(listOf(0, 120, 240, 360, 480, 380, 480), openings(replay(spent)))
    }

    @Test
    fun saving_adjust_and_redeemed_cents_carry_forward() {
        val rows = listOf(
            WeekEntity(weekStart = w1, savingAdjustCents = 200),
            WeekEntity(weekStart = w2, savingAdjustCents = -50, redeemedMinutes = 30, redeemedCents = 200),
            WeekEntity(weekStart = w3, savingAdjustCents = -1000),
            WeekEntity(weekStart = 21L)
        )
        val replayed = replay(rows)
        assertEquals(listOf(0, 200, 350, 0), replayed.map { it.prevSavingCents })
        // 兑换的 30 分钟从第 2 周结余里扣：120+90
        assertEquals(listOf(0, 120, 210, 330), openings(replayed))
    }
}
