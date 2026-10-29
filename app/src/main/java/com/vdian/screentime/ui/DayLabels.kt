package com.vdian.screentime.ui

import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.ScoreRules
import com.vdian.screentime.domain.WeekDates

/** 界面中文文案。集中在此，保证与纸质记录表用词一致。 */
object DayLabels {

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayName(index: Int): String = weekdays.getOrElse(index) { "" }

    /** 项名取自目录；目录里没有的 key 直接显示 key 本身。 */
    fun itemName(item: Item, catalog: ItemCatalog): String =
        catalog.def(item)?.name ?: item.key

    /**
     * 表头文案：项名换行接分值。投诉的分值本就是负数（[ScoreRules.itemPoints] 返回
     * 负的 points），所以不能写死 "+" 前缀，否则渲染成 "投诉\n+-30"。
     */
    fun itemHeader(item: Item, catalog: ItemCatalog): String {
        val points = ScoreRules.itemPoints(item, catalog)
        val signed = if (points >= 0) "+$points" else "$points"
        return "${itemName(item, catalog)}\n$signed"
    }

    fun formatDateRange(weekStart: Long): String =
        "${WeekDates.format(weekStart)} - ${WeekDates.format(weekStart + 6)}"
}
