package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.BankCalculator
import com.vdian.screentime.domain.BankResult
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.WeekCalculator
import com.vdian.screentime.domain.WeekMeta
import com.vdian.screentime.domain.WeekSummary

/**
 * 一周的完整视图数据，UI 的唯一数据来源。
 * 界面直接读 [summary] 与 [bank]，不做任何算术。
 */
data class WeekBoard(
    val weekStart: Long,
    val days: List<DayRecord>,          // 恒为 7 条，按日期升序
    val exams: List<ExamRecord>,
    val meta: WeekMeta,
    val prevBankMinutes: Int,
    val prevSavingCents: Int,
    val config: RuleConfig
) {
    val summary: WeekSummary by lazy {
        WeekCalculator.summarize(days, exams, meta, config)
    }

    val bank: BankResult by lazy {
        BankCalculator.compute(
            prevBankMinutes = prevBankMinutes,
            prevSavingCents = prevSavingCents,
            weekBalanceMinutes = summary.balance,
            redeemedMinutesThisWeek = meta.redeemedMinutes,
            redeemedCentsThisWeek = meta.redeemedCents,
            cfg = config
        )
    }
}

/** 把数据库原始行拼装成 [WeekBoard]。纯函数。 */
object WeekBoardAssembler {

    fun build(
        weekStart: Long,
        days: List<DayRecord>,
        exams: List<ExamRecord>,
        weekEntity: WeekEntity?,
        prevEntity: WeekEntity?,
        cfg: RuleConfig
    ): WeekBoard {
        val byDay = days.associateBy { it.epochDay }
        val filled = (0L..6L).map { offset ->
            byDay[weekStart + offset] ?: DayRecord(weekStart + offset)
        }
        return WeekBoard(
            weekStart = weekStart,
            days = filled,
            exams = exams,
            meta = weekEntity?.toDomain() ?: WeekMeta(),
            prevBankMinutes = prevEntity?.prevBankMinutes ?: 0,
            prevSavingCents = prevEntity?.prevSavingCents ?: 0,
            config = cfg
        )
    }

    private fun WeekEntity.toDomain() = Mappers.run { toDomain() }
}
