package com.vdian.screentime.domain

import kotlin.math.min

/** 计分规则。全部为纯函数，不依赖 Android。单项分值、投诉判断、互斥组一律取自 [ItemCatalog]。 */
object ScoreRules {

    /** 单项分值，取自目录。扣分项与投诉返回负值，未知项返回 0。 */
    fun itemPoints(item: Item, catalog: ItemCatalog): Int = catalog.points(item)

    /** 这一天是否被投诉（任一投诉项已确认）。 */
    private fun hasComplaint(day: DayRecord, catalog: ItemCatalog): Boolean =
        day.items.any { catalog.isComplaint(it) }

    /**
     * 本周实际生效的投诉次数：按日期升序取前 [RuleConfig.complaintMaxPerWeek] 次。
     * 与输入顺序无关。
     */
    fun effectiveComplaintCount(days: List<DayRecord>, cfg: RuleConfig, catalog: ItemCatalog): Int =
        min(days.count { hasComplaint(it, catalog) }, cfg.complaintMaxPerWeek)

    /**
     * 某天的小计。可为负，不截断。
     * 普通项（含新扣分项）直接累加 points；投诉项按 [complaintCounts] 给出的生效次数扣分。
     * @param complaintCounts epochDay -> 该天生效的投诉次数
     */
    fun dailyScore(day: DayRecord, complaintCounts: Map<Long, Int>, catalog: ItemCatalog): Int {
        val base = day.items.filter { !catalog.isComplaint(it) }.sumOf { itemPoints(it, catalog) }
        val complaints = complaintCounts[day.epochDay] ?: 0
        return base + complaints * -catalog.complaintPenalty()
    }

    /**
     * 按日期升序计算每天的分数，并分配投诉额度。
     * 返回按日期升序排列的结果。
     */
    fun dailyScores(days: List<DayRecord>, cfg: RuleConfig, catalog: ItemCatalog): List<Int> {
        val ascending = days.sortedBy { it.epochDay }
        var remaining = cfg.complaintMaxPerWeek
        val counts = mutableMapOf<Long, Int>()
        for (d in ascending) {
            if (hasComplaint(d, catalog) && remaining > 0) {
                counts[d.epochDay] = 1
                remaining--
            } else {
                counts[d.epochDay] = 0
            }
        }
        return ascending.map { dailyScore(it, counts, catalog) }
    }

    /** 本周每日加分合计。 */
    fun dailyTotal(days: List<DayRecord>, cfg: RuleConfig, catalog: ItemCatalog): Int =
        dailyScores(days, cfg, catalog).sum()

    /** 单科考试奖励。 */
    fun examBonus(subject: Subject, score: Int, cfg: RuleConfig): Int =
        if (subject == Subject.ENGLISH) {
            when {
                score >= cfg.englishHighMin -> cfg.englishHighBonus
                score >= cfg.englishMidMin -> cfg.englishMidBonus
                else -> 0
            }
        } else {
            if (score >= cfg.otherSubjectMin) cfg.otherSubjectBonus else 0
        }

    /** 本周考试奖励合计。 */
    fun examTotal(exams: List<ExamRecord>, cfg: RuleConfig): Int =
        exams.sumOf { examBonus(it.subject, it.score, cfg) }

    /**
     * 孩子点击格子：把项加入待确认。
     * - 已确认的项不受影响（孩子不能再操作已确认的项）
     * - 同一互斥组已有已确认或待确认的其他项时，不允许申报（同组同一天最多一个处于这两种状态）
     * 是否允许孩子申报（[ItemDef.childClaimable]）由调用方判断，这里不管。
     */
    fun claim(item: Item, items: Set<Item>, pending: Set<Item>, catalog: ItemCatalog): Set<Item> {
        if (items.contains(item)) return pending
        val group = catalog.groupOf(item)
        if (group != null && (items + pending).any { it != item && catalog.groupOf(it) == group }) {
            return pending
        }
        return pending + item
    }

    /**
     * 家长点击格子：
     * - 该格是已确认 -> 取消确认（移出 items）
     * - 该格是待确认 -> 确认（从 pending 移入 items）
     * - 该格是空     -> 直接确认
     * 确认时同一互斥组的其他项（无论已确认还是待确认）一并移除。
     * 返回新的 (items, pending)。
     */
    fun advance(
        item: Item,
        items: Set<Item>,
        pending: Set<Item>,
        catalog: ItemCatalog
    ): Pair<Set<Item>, Set<Item>> {
        if (items.contains(item)) return (items - item) to pending
        val group = catalog.groupOf(item)
        val sameGroup: (Item) -> Boolean = { group != null && it != item && catalog.groupOf(it) == group }
        val newItems = items.filterNot(sameGroup).toSet() + item
        val newPending = pending.filterNot(sameGroup).toSet() - item
        return newItems to newPending
    }

    /** 家长长按：退回孩子的申报。 */
    fun retract(item: Item, pending: Set<Item>): Set<Item> = pending - item
}
