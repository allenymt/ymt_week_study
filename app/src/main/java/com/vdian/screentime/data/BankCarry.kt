package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.RuleConfig

/**
 * 银行结转。纯函数。
 *
 * 不变式：第 W 周的期初 = 上一条已存在周行的期末；周行上固化的
 * `prevBankMinutes / prevSavingCents` 就是该行自己的期初。
 */
object BankCarry {

    /** [row] 这一周的期末（用它自己固化的期初、日记录与考试记录重算）。 */
    fun closingOf(
        row: WeekEntity,
        days: List<DayRecord>,
        exams: List<ExamRecord>,
        cfg: RuleConfig,
        catalog: ItemCatalog
    ): BankOpening = WeekBoardAssembler.build(
        weekStart = row.weekStart,
        days = days,
        exams = exams,
        weekEntity = row,
        fallbackOpening = null,
        cfg = cfg,
        catalog = catalog
    ).closing()

    /**
     * 按 `weekStart` 升序重放全部周行，返回期初已更正的周行（顺序为升序，其余字段不变）。
     * 第一行期初为 0；之后每行期初 = 上一行（已更正）的期末。
     */
    fun replay(
        weeks: List<WeekEntity>,
        daysByWeek: Map<Long, List<DayRecord>>,
        examsByWeek: Map<Long, List<ExamRecord>>,
        cfg: RuleConfig,
        catalog: ItemCatalog
    ): List<WeekEntity> {
        var opening = BankOpening.ZERO
        return weeks.sortedBy { it.weekStart }.map { row ->
            val fixed = row.copy(
                prevBankMinutes = opening.bankMinutes,
                prevSavingCents = opening.savingCents
            )
            opening = closingOf(
                fixed,
                daysByWeek[row.weekStart].orEmpty(),
                examsByWeek[row.weekStart].orEmpty(),
                cfg,
                catalog
            )
            fixed
        }
    }
}
