package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.RuleConfig

/**
 * 一份已通过校验、可直接落库的导入内容（方案第 5 节）。
 *
 * - [weeks] 按 `weekStart` 升序，`prevBankMinutes / prevSavingCents` 已用 [BankCarry.replay]
 *   按 [config] 与 [itemDefs] 重放过，本身就是自洽的；落库后仓库仍会再重放一次。
 * - [exams] 的 `id` 一律为 0，落库时由 Room 重新生成。
 * - [itemDefs] 恒非空。
 */
data class ImportPlan(
    val schemaVersion: Int,
    val childName: String,
    val config: RuleConfig,
    val itemDefs: List<ItemDef>,
    val days: List<DayEntity>,
    val weeks: List<WeekEntity>,
    val exams: List<ExamEntity>,
    val warnings: List<String>
)

sealed class ImportResult {
    data class Ok(val plan: ImportPlan) : ImportResult()
    data class Error(val reason: String) : ImportResult()
}
