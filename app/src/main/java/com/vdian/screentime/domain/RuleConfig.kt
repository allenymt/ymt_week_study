package com.vdian.screentime.domain

/** 全部可配置规则参数。默认值取自纸质记录表。 */
data class RuleConfig(
    val raisePoints: Int = 10,
    val neatWritingPoints: Int = 10,
    val englishPoints: Int = 10,
    val readingPoints: Int = 15,
    val exercisePoints: Int = 15,
    val complaintPenalty: Int = 30,
    val complaintMaxPerWeek: Int = 2,
    val baseMinutes: Int = 60,
    val noComplaintBonusMinutes: Int = 60,
    val weekCapMinutes: Int = 240,
    val bankCapMinutes: Int = 480,
    val redeemUnitMinutes: Int = 30,
    val redeemUnitCents: Int = 200,
    val weeklyRedeemCapCents: Int = 1000,
    val savingCapCents: Int = 5000,
    val englishMidMin: Int = 85,
    val englishHighMin: Int = 90,
    val englishMidBonus: Int = 15,
    val englishHighBonus: Int = 30,
    val otherSubjectMin: Int = 90,
    val otherSubjectBonus: Int = 30
)
