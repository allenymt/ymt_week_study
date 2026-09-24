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
        Item.COMPLAINT -> "投诉"
    }

    fun itemHeader(item: Item, cfg: RuleConfig): String =
        "${itemName(item)}\n+${ScoreRules.itemPoints(item, cfg)}"

    fun formatDateRange(weekStart: Long): String =
        "${WeekDates.format(weekStart)} - ${WeekDates.format(weekStart + 6)}"
}
