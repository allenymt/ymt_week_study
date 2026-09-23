package com.vdian.screentime.domain

import kotlin.math.max
import kotlin.math.min

/** 屏幕银行计算。纯函数。兑换扣的是分钟额度，不是钱。 */
object BankCalculator {

    /** 兑换 [minutes] 分钟可得金额（分）。不足一个单位不给钱。 */
    fun centsForMinutes(minutes: Int, cfg: RuleConfig): Int =
        if (minutes < cfg.redeemUnitMinutes) 0
        else (minutes / cfg.redeemUnitMinutes) * cfg.redeemUnitCents

    /**
     * 本周还能兑换的分钟数，取三者最小值并向下取整到整数单位：
     * 1) 本周结余
     * 2) 剩余金额额度换算出的分钟数
     */
    fun maxRedeemableMinutes(
        weekBalanceMinutes: Int,
        redeemedMinutesThisWeek: Int,
        redeemedCentsThisWeek: Int,
        cfg: RuleConfig
    ): Int {
        val remainingCents = max(0, cfg.weeklyRedeemCapCents - redeemedCentsThisWeek)
        val minutesByMoney =
            (remainingCents / cfg.redeemUnitCents) * cfg.redeemUnitMinutes
        val availableByBalance = max(0, weekBalanceMinutes - redeemedMinutesThisWeek)
        val raw = min(availableByBalance, minutesByMoney)
        return (raw / cfg.redeemUnitMinutes) * cfg.redeemUnitMinutes
    }

    fun compute(
        prevBankMinutes: Int,
        prevSavingCents: Int,
        weekBalanceMinutes: Int,
        redeemedMinutesThisWeek: Int,
        redeemedCentsThisWeek: Int,
        cfg: RuleConfig
    ): BankResult {
        val accumulated = prevBankMinutes + weekBalanceMinutes
        val bank = min(max(0, accumulated), cfg.bankCapMinutes)
        val overflow = max(0, accumulated - cfg.bankCapMinutes)
        val saving = min(prevSavingCents + redeemedCentsThisWeek, cfg.savingCapCents)
        val redeemableMinutes = maxRedeemableMinutes(
            weekBalanceMinutes, redeemedMinutesThisWeek, redeemedCentsThisWeek, cfg
        )

        return BankResult(
            bankBalanceMinutes = bank,
            bankOverflow = overflow,
            savingCents = saving,
            redeemableMinutesThisWeek = redeemableMinutes,
            redeemableCentsThisWeek = centsForMinutes(redeemableMinutes, cfg)
        )
    }
}
