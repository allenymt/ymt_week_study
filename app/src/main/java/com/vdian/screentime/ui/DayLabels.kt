package com.vdian.screentime.ui

import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.ScoreRules
import com.vdian.screentime.domain.WeekDates

/** 界面中文文案。集中在此，保证与纸质记录表用词一致。 */
object DayLabels {

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayName(index: Int): String = weekdays.getOrElse(index) { "" }

    fun itemName(item: Item): String = when (item) {
        Item.RAISED -> "举手回答"
        Item.NEAT_WRITING -> "字认真"
        Item.ENGLISH -> "主动英语"
        Item.READING -> "主动阅读"
        Item.EXERCISE -> "主动运动"
        Item.PRAISE -> "老师表扬"
        Item.COMPLAINT -> "投诉"
    }

    /**
     * 表头文案：项名换行接分值。投诉的分值本就是负数（[ScoreRules.itemPoints] 返回
     * `-complaintPenalty`），所以不能写死 "+" 前缀，否则渲染成 "投诉\n+-30"。
     */
    fun itemHeader(item: Item, cfg: RuleConfig): String {
        val points = ScoreRules.itemPoints(item, cfg)
        val signed = if (points >= 0) "+$points" else "$points"
        return "${itemName(item)}\n$signed"
    }

    fun formatDateRange(weekStart: Long): String =
        "${WeekDates.format(weekStart)} - ${WeekDates.format(weekStart + 6)}"
}
