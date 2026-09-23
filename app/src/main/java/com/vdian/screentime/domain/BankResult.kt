package com.vdian.screentime.domain

/**
 * 屏幕银行状态。
 * [bankOverflow] 是银行累计超出上限的部分（与 [WeekSummary.overflow] 不同，后者是单周封顶溢出）。
 */
data class BankResult(
    val bankBalanceMinutes: Int,          // 银行累计，上限 bankCapMinutes
    val bankOverflow: Int,                // 超出上限的部分，>= 0
    val savingCents: Int,                 // 存钱累计，上限 savingCapCents
    val redeemableMinutesThisWeek: Int,   // 本周还能兑换的分钟数（已按整数单位取整）
    val redeemableCentsThisWeek: Int      // 上述分钟数对应的金额
)
