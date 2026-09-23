package com.vdian.screentime.domain

/**
 * 一周的完整汇总，全部为派生值。
 * [overflow] 与 [BankResult.bankOverflow] 是两回事：前者是单周赚超封顶的部分，
 * 后者是银行累计超出上限的部分。
 */
data class WeekSummary(
    val dailyScores: List<Int>,          // 与输入 days 同序
    val dailyTotal: Int,                 // 每日加分合计（可为负）
    val examTotal: Int,                  // 考试奖励合计
    val baseMinutes: Int,                // 保底（无条件）
    val noComplaintBonusMinutes: Int,    // 一周无投诉奖励
    val totalEarned: Int,                // 封顶后的本周总赚取
    val cappedEarned: Int,               // 封顶前的原始值（调试与展示用）
    val overflow: Int,                   // 单周超出封顶的部分，>= 0
    val usedMinutes: Int,
    val overtimeMinutes: Int,
    val redeemedMinutes: Int,
    val redeemedCents: Int,
    val balance: Int,                    // 本周结余，可为负
    val complaintCount: Int,             // 本周生效的投诉次数
    val complaintPenalty: Int            // 单次投诉扣分（来自 RuleConfig，供界面展示用）
)
