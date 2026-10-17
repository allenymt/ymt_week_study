package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.WeekMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class BankRollupTest {

    private val cfg = RuleConfig()

    /** 造一个视图，[usedMinutes] 扣减本周结余，[prevBankMinutes] 是银行的期初累计。 */
    private fun board(
        weekStart: Long,
        usedMinutes: Int = 0,
        redeemedMinutes: Int = 0,
        redeemedCents: Int = 0,
        prevBankMinutes: Int = 0,
        prevSavingCents: Int = 0
    ): WeekBoard = WeekBoard(
        weekStart = weekStart,
        days = (0L..6L).map { DayRecord(weekStart + it) },
        exams = emptyList(),
        meta = WeekMeta(
            usedMinutes = usedMinutes,
            redeemedMinutes = redeemedMinutes,
            redeemedCents = redeemedCents
        ),
        prevBankMinutes = prevBankMinutes,
        prevSavingCents = prevSavingCents,
        config = cfg
    )

    @Test
    fun empty_database_yields_all_zeros() {
        val r = BankRollupAggregator.build(latest = null, allWeeks = emptyList())
        assertEquals(0, r.bankBalanceMinutes)
        assertEquals(0, r.totalRedeemedCents)
        assertEquals(0, r.cashAvailableCents)
    }

    @Test
    fun total_redeemed_sums_every_week_while_balance_comes_from_latest_only() {
        // 历史两周各兑换过 2 元；最新周无消费，期初已带 120 分钟。
        val weeks = listOf(
            WeekEntity(weekStart = 0, redeemedMinutes = 30, redeemedCents = 200),
            WeekEntity(weekStart = 7, redeemedMinutes = 30, redeemedCents = 200)
        )
        // 无记录的周仍拿保底 60 + 无投诉奖 60 = 120 分钟，加上期初 120 = 240。
        val latest = board(weekStart = 14, prevBankMinutes = 120)
        val r = BankRollupAggregator.build(latest, weeks)

        assertEquals(400, r.totalRedeemedCents)     // 200 + 200，逐周相加
        assertEquals(240, r.bankBalanceMinutes)     // 只取最新周，不是三周求和
    }

    @Test
    fun cash_available_is_capped_while_total_redeemed_keeps_climbing() {
        // 逐周兑换累计 120 元 > 存钱上限 100 元：两者必须保持为不同量。
        val weeks = List(20) { i ->
            WeekEntity(weekStart = i * 7L, redeemedMinutes = 150, redeemedCents = 600)
        }
        val latest = board(
            weekStart = 200,
            prevSavingCents = 10000
        )
        val r = BankRollupAggregator.build(latest, weeks)

        assertEquals(12000, r.totalRedeemedCents)    // 历史事实，不封顶
        assertEquals(10000, r.cashAvailableCents)    // 夹在 savingCapCents
    }

    @Test
    fun balance_text_reads_as_hours_and_minutes() {
        assertEquals("0 分钟", BankRollup.minutesText(0))
        assertEquals("30 分钟", BankRollup.minutesText(30))
        assertEquals("1 小时", BankRollup.minutesText(60))
        assertEquals("8 小时", BankRollup.minutesText(480))
        assertEquals("1 小时 30 分钟", BankRollup.minutesText(90))
    }
}
