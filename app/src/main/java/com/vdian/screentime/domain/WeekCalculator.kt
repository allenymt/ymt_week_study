package com.vdian.screentime.domain

import kotlin.math.max
import kotlin.math.min

/** 周汇总计算。纯函数。 */
object WeekCalculator {

    fun summarize(
        days: List<DayRecord>,
        exams: List<ExamRecord>,
        meta: WeekMeta,
        cfg: RuleConfig
    ): WeekSummary {
        val scores = ScoreRules.dailyScores(days, cfg)
        val dailyTotal = scores.sum()
        val examTotal = ScoreRules.examTotal(exams, cfg)
        val base = cfg.baseMinutes

        // 只有全部有记录的天都无投诉才给，且至少要有记录
        val noComplaint = days.isNotEmpty() && days.none { it.items.contains(Item.COMPLAINT) }
        val bonus = if (noComplaint) cfg.noComplaintBonusMinutes else 0

        // 计算每日与考试的合计（可能为负）
        val dailyAndExamTotal = dailyTotal + examTotal

        // 对每日与考试合计进行封顶
        val cappedDaily = min(dailyAndExamTotal, cfg.weekCapMinutes)

        // 总赚取 = 保底 + 无投诉奖 + 封顶后的每日与考试合计，再次封顶到周上限
        val raw = base + bonus + dailyAndExamTotal
        val cappedEarned = min(raw, cfg.weekCapMinutes)

        // 超出部分：从每日考试部分计算超出量，但不超过周上限的一半
        val dailyOverflow = max(0, dailyAndExamTotal - cappedDaily)
        val overflow = min(dailyOverflow, cfg.weekCapMinutes / 2)

        val balance = cappedEarned - meta.usedMinutes - meta.overtimeMinutes - meta.redeemedMinutes

        return WeekSummary(
            dailyScores = scores,
            dailyTotal = dailyTotal,
            examTotal = examTotal,
            baseMinutes = base,
            noComplaintBonusMinutes = bonus,
            totalEarned = cappedEarned,
            cappedEarned = cappedEarned,
            overflow = overflow,
            usedMinutes = meta.usedMinutes,
            overtimeMinutes = meta.overtimeMinutes,
            redeemedMinutes = meta.redeemedMinutes,
            redeemedCents = meta.redeemedCents,
            balance = balance,
            complaintCount = ScoreRules.effectiveComplaintCount(days, cfg),
            complaintPenalty = cfg.complaintPenalty
        )
    }
}
