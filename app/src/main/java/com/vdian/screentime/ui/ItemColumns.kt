package com.vdian.screentime.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 表格列宽、行高与格子 TextView 的唯一来源。列的集合与顺序由 `ItemCatalog.columns()` 决定，
 * 表头（WeekFragment）与数据行（WeekRowAdapter）共用这里的尺寸以保证对齐。
 */
object ItemColumns {

    /** 单个考核项格子的固定宽度（不含两侧间距），满足触控尺寸。 */
    const val CELL_WIDTH_DP = 56
    const val CELL_GAP_DP = 2

    /** 数据行高度；左侧星期列、右侧小计列、中间格子必须同高才能对齐。 */
    const val ROW_HEIGHT_DP = 48
    const val HEADER_HEIGHT_DP = 44

    /** n 列占用的总宽度（px）：每列 = 格子宽 + 两侧间距。 */
    fun areaWidthPx(context: Context, columnCount: Int): Int =
        columnCount * dp(context, CELL_WIDTH_DP + 2 * CELL_GAP_DP)

    /** 行首星期标签 / 行尾当日小计的宽度，表头用同宽占位才能对齐。 */
    const val LABEL_WIDTH_DP = 56
    const val SCORE_WIDTH_DP = 56

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * 造一个占满列宽的格子。表头与数据行共用，保证列宽一致。
     * @param horizontalMarginDp 列间距的一半，两侧各设一次。
     */
    fun cell(context: Context, horizontalMarginDp: Int = CELL_GAP_DP): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            dp(context, CELL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT
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

    private val HEADER_BG = android.graphics.Color.parseColor("#E0E0E0")
    private val HEADER_FG = android.graphics.Color.parseColor("#424242")
}
