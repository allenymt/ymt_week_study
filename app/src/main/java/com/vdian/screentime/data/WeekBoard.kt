package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.BankCalculator
import com.vdian.screentime.domain.BankResult
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.ItemCatalog
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
    val config: RuleConfig,
    /** 考核项目录，来自 item_def 表。单项分值、投诉判断、互斥组一律以它为准。 */
    val catalog: ItemCatalog
) {
    val summary: WeekSummary by lazy {
        WeekCalculator.summarize(days, exams, meta, config, catalog)
    }

    val bank: BankResult by lazy {
        BankCalculator.compute(
            prevBankMinutes = prevBankMinutes,
            prevSavingCents = prevSavingCents,
            weekBalanceMinutes = summary.balance,
            redeemedMinutesThisWeek = meta.redeemedMinutes,
            redeemedCentsThisWeek = meta.redeemedCents,
            savingAdjustCents = meta.savingAdjustCents,
            cfg = config
        )
    }
}

/** 一周的银行期初（= 上一周期末）。 */
data class BankOpening(val bankMinutes: Int, val savingCents: Int) {
    companion object {
        val ZERO = BankOpening(0, 0)
    }
}

/** 本周期末，即下一周的期初。 */
fun WeekBoard.closing(): BankOpening = BankOpening(bank.bankBalanceMinutes, bank.savingCents)

/** 把数据库原始行拼装成 [WeekBoard]。纯函数。 */
object WeekBoardAssembler {

    /**
     * 期初的来源：
     * - 本周行存在时，取它自己固化的 `prevBankMinutes / prevSavingCents`（该字段即本周期初）；
     * - 本周行尚不存在时，取 [fallbackOpening]，调用方应传入上一周行的期末（见 [BankCarry.closingOf]）；
     *   为 null 表示没有上一周，期初为 0。
     */
    fun build(
        weekStart: Long,
        days: List<DayRecord>,
        exams: List<ExamRecord>,
        weekEntity: WeekEntity?,
        fallbackOpening: BankOpening?,
        cfg: RuleConfig,
        catalog: ItemCatalog
    ): WeekBoard {
        val opening = weekEntity?.let { BankOpening(it.prevBankMinutes, it.prevSavingCents) }
            ?: fallbackOpening
            ?: BankOpening.ZERO
        val byDay = days.associateBy { it.epochDay }
        val filled = (0L..6L).map { offset ->
            byDay[weekStart + offset] ?: DayRecord(weekStart + offset)
        }
        return WeekBoard(
            weekStart = weekStart,
            days = filled,
            exams = exams,
            meta = weekEntity?.toDomain() ?: WeekMeta(),
            prevBankMinutes = opening.bankMinutes,
            prevSavingCents = opening.savingCents,
            config = cfg,
            catalog = catalog
        )
    }

    private fun WeekEntity.toDomain() = Mappers.run { toDomain() }
}
