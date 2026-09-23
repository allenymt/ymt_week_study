package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BankCalculatorTest {

    private val cfg = RuleConfig()

    @Test
    fun bank_accumulates_prev_and_current() {
        val r = BankCalculator.compute(
            prevBankMinutes = 100, prevSavingCents = 0,
            weekBalanceMinutes = 50, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(150, r.bankBalanceMinutes)
        assertEquals(0, r.bankOverflow)
    }

    @Test
    fun bank_caps_at_480_and_reports_overflow() {
        val r = BankCalculator.compute(
            prevBankMinutes = 400, prevSavingCents = 0,
            weekBalanceMinutes = 200, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(480, r.bankBalanceMinutes)
        assertEquals(120, r.bankOverflow)     // 600 - 480
    }

    @Test
    fun bank_overflow_never_negative() {
        val r = BankCalculator.compute(
            prevBankMinutes = 100, prevSavingCents = 0,
            weekBalanceMinutes = 50, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(0, r.bankOverflow)
    }

    @Test
    fun negative_week_balance_reduces_bank_but_not_below_zero() {
        val r = BankCalculator.compute(
            prevBankMinutes = 100, prevSavingCents = 0,
            weekBalanceMinutes = -300, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(0, r.bankBalanceMinutes)   // 100 - 300 -> 夹到 0
        assertEquals(0, r.bankOverflow)
    }

    @Test
    fun already_full_bank_absorbs_new_earnings_into_overflow() {
        val r = BankCalculator.compute(
            prevBankMinutes = 480, prevSavingCents = 0,
            weekBalanceMinutes = 60, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(480, r.bankBalanceMinutes)
        assertEquals(60, r.bankOverflow)
    }

    @Test
    fun cents_for_minutes_rounds_down_to_whole_units() {
        assertEquals(200, BankCalculator.centsForMinutes(30, cfg))
        assertEquals(200, BankCalculator.centsForMinutes(45, cfg))   // 不足 60 分钟按 1 单位
        assertEquals(400, BankCalculator.centsForMinutes(60, cfg))
        assertEquals(0, BankCalculator.centsForMinutes(29, cfg))     // 不足 1 单位不给钱
    }

    @Test
    fun max_redeemable_is_limited_by_weekly_money_cap() {
        // 周上限 10 元 = 1000 分 = 5 个单位 = 150 分钟
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 400, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(150, m)
    }

    @Test
    fun max_redeemable_accounts_for_already_redeemed_money() {
        // 已兑换 8 元，只剩 2 元 = 1 单位 = 30 分钟
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 400, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 800, cfg = cfg
        )
        assertEquals(30, m)
    }

    @Test
    fun max_redeemable_is_limited_by_balance() {
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 45, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(30, m)   // 只能取整单位
    }

    @Test
    fun max_redeemable_is_zero_when_money_cap_reached() {
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 400, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 1000, cfg = cfg
        )
        assertEquals(0, m)
    }

    @Test
    fun saving_caps_at_50_yuan() {
        val r = BankCalculator.compute(
            prevBankMinutes = 0, prevSavingCents = 4800,
            weekBalanceMinutes = 0, redeemedMinutesThisWeek = 30,
            redeemedCentsThisWeek = 200, cfg = cfg
        )
        assertEquals(5000, r.savingCents)     // 4800 + 200 -> 夹到 5000
    }

    @Test
    fun saving_is_never_decreased_by_penalties() {
        // 本周结余为负，存钱不受影响（钱不抵惩罚）
        val r = BankCalculator.compute(
            prevBankMinutes = 0, prevSavingCents = 1000,
            weekBalanceMinutes = -200, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(1000, r.savingCents)
    }
}
