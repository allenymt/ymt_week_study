package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity

/**
 * 银行页要展示的三个总量。UI 只读这里，不做任何算术。
 *
 * 三个字段刻意保持为不同量，不要互相化简：
 * - [bankBalanceMinutes] 是**当前**余额（已封顶），来自最新一周的 [WeekBoard.bank]。
 *   不能对历史各周求和 —— 每周的 `prevBankMinutes` 已经携带了上期累计，求和会重复计算。
 * - [totalRedeemedCents] 是**历史累计**已兑换金额，逐周 `redeemedCents` 直接相加，不封顶。
 * - [cashAvailableCents] 是**已封顶**的存钱累计（上限 `savingCapCents`）。
 *   累计兑换一旦超过存钱上限，[totalRedeemedCents] 会继续增长而 [cashAvailableCents] 停在封顶值，
 *   两者从此分道扬镳。这是有意为之：一个是历史事实，一个是可用金额。
 */
data class BankRollup(
    val bankBalanceMinutes: Int,
    val totalRedeemedCents: Int,
    val cashAvailableCents: Int
) {
    /** 余额的"X 小时 Y 分钟"展示串。纯计算，供 UI 直接取用。 */
    fun balanceText(): String = minutesText(bankBalanceMinutes)

    companion object {
        /** 每分钟换算，仅为排版服务，不参与业务规则。 */
        private const val MINUTES_PER_HOUR = 60

        fun minutesText(minutes: Int): String {
            if (minutes <= 0) return "0 分钟"
            val h = minutes / MINUTES_PER_HOUR
            val m = minutes % MINUTES_PER_HOUR
            return when {
                h == 0 -> "$m 分钟"
                m == 0 -> "$h 小时"
                else -> "$h 小时 $m 分钟"
            }
        }
    }
}

/** 把历史各周的原始行聚合成 [BankRollup]。纯函数，见 [BankRollup] 的字段说明。 */
object BankRollupAggregator {

    fun build(latest: WeekBoard?, allWeeks: List<WeekEntity>): BankRollup {
        val balance = latest?.bank?.bankBalanceMinutes ?: 0
        val saving = latest?.bank?.savingCents ?: 0
        val totalRedeemed = allWeeks.sumOf { it.redeemedCents }
        return BankRollup(
            bankBalanceMinutes = balance,
            totalRedeemedCents = totalRedeemed,
            cashAvailableCents = saving
        )
    }
}
