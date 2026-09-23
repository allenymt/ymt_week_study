package com.vdian.screentime.domain

import kotlin.math.min

/** 计分规则。全部为纯函数，不依赖 Android。 */
object ScoreRules {

    /** 单项分值。投诉返回负值。 */
    fun itemPoints(item: Item, cfg: RuleConfig): Int = when (item) {
        Item.RAISED -> cfg.raisePoints
        Item.NEAT_WRITING -> cfg.neatWritingPoints
        Item.ENGLISH -> cfg.englishPoints
        Item.READING -> cfg.readingPoints
        Item.EXERCISE -> cfg.exercisePoints
        Item.COMPLAINT -> -cfg.complaintPenalty
    }

    /**
     * 本周实际生效的投诉次数：按日期升序取前 [RuleConfig.complaintMaxPerWeek] 次。
     * 与输入顺序无关。
     */
    fun effectiveComplaintCount(days: List<DayRecord>, cfg: RuleConfig): Int =
        min(days.count { it.items.contains(Item.COMPLAINT) }, cfg.complaintMaxPerWeek)

    /**
     * 某天的小计。可为负，不截断。
     * @param complaintCounts epochDay -> 该天生效的投诉次数
     */
    fun dailyScore(day: DayRecord, complaintCounts: Map<Long, Int>, cfg: RuleConfig): Int {
        val base = day.items.filter { it != Item.COMPLAINT }.sumOf { itemPoints(it, cfg) }
        val complaints = complaintCounts[day.epochDay] ?: 0
        return base + complaints * -cfg.complaintPenalty
    }

    /**
     * 按日期升序计算每天的分数，并分配投诉额度。
     * 返回按日期升序排列的结果。
     */
    fun dailyScores(days: List<DayRecord>, cfg: RuleConfig): List<Int> {
        val ascending = days.sortedBy { it.epochDay }
        var remaining = cfg.complaintMaxPerWeek
        val counts = mutableMapOf<Long, Int>()
        for (d in ascending) {
            if (d.items.contains(Item.COMPLAINT) && remaining > 0) {
                counts[d.epochDay] = 1
                remaining--
            } else {
                counts[d.epochDay] = 0
            }
        }
        return ascending.map { dailyScore(it, counts, cfg) }
    }

    /** 本周每日加分合计。 */
    fun dailyTotal(days: List<DayRecord>, cfg: RuleConfig): Int =
        dailyScores(days, cfg).sum()

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
     * 孩子点击格子：把项加入待确认。已确认的项不受影响（孩子不能再操作已确认的项）。
     */
    fun claim(item: Item, items: Set<Item>, pending: Set<Item>): Set<Item> =
        if (items.contains(item)) pending else pending + item

    /**
     * 家长点击格子：
     * - 该格是已确认 -> 取消确认（移出 items）
     * - 该格是待确认 -> 确认（从 pending 移入 items）
     * - 该格是空     -> 直接确认
     * 返回新的 (items, pending)。
     */
    fun advance(item: Item, items: Set<Item>, pending: Set<Item>): Pair<Set<Item>, Set<Item>> =
        if (items.contains(item)) {
            (items - item) to pending
        } else {
            (items + item) to (pending - item)
        }

    /** 家长长按：退回孩子的申报。 */
    fun retract(item: Item, pending: Set<Item>): Set<Item> = pending - item
}
