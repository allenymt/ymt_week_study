package com.vdian.screentime.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.vdian.screentime.domain.Item

/**
 * 表格列定义的唯一来源：列顺序、每列宽度权重、格子 TextView 的构造。
 *
 * 表头（[WeekFragment]）与数据行（[WeekRowAdapter]）都从这里取列顺序，
 * 避免两处各写一份而顺序漂移。
 */
object ItemColumns {

    /** 列顺序，与纸质记录表一致。 */
    val ROW_ITEMS: List<Item> = listOf(
        Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH,
        Item.READING, Item.EXERCISE, Item.COMPLAINT
    )

    /** 行首星期标签 / 行尾当日小计的宽度，表头用同宽占位才能对齐。 */
    const val LABEL_WIDTH_DP = 56
    const val SCORE_WIDTH_DP = 56

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * 造一个占满列宽的格子。表头与数据行共用，保证列宽一致。
     * @param horizontalMarginDp 列间距的一半，两侧各设一次。
     */
    fun cell(context: Context, horizontalMarginDp: Int = 2): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ).apply {
            val gap = dp(context, horizontalMarginDp)
            setMargins(gap, gap, gap, gap)
        }
        gravity = Gravity.CENTER
    }

    /** 表头单元格：项名 + 分值（文案由 [DayLabels.itemHeader] 拼好传入）。 */
    fun headerCell(context: Context, header: String): TextView =
        cell(context).apply {
            text = header
            textSize = 10f
            setTextColor(HEADER_FG)
            setBackgroundColor(HEADER_BG)
            setPadding(2, 4, 2, 4)
        }

    /** 表头行两侧的占位，宽度与数据行的星期 / 小计列对齐。 */
    fun spacer(context: Context, widthDp: Int): android.view.View =
        android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), MATCH_PARENT)
        }

    private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private val HEADER_BG = android.graphics.Color.parseColor("#E0E0E0")
    private val HEADER_FG = android.graphics.Color.parseColor("#424242")
}
