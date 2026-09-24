package com.vdian.screentime.ui

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vdian.screentime.databinding.ItemWeekRowBinding
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.WeekDates

/** 三态：空 / 孩子已申报待确认 / 家长已确认。只有 [CONFIRMED] 计分。 */
enum class CellState { EMPTY, PENDING, CONFIRMED }

object CellStateResolver {
    fun resolve(item: Item, day: DayRecord): CellState = when {
        day.items.contains(item) -> CellState.CONFIRMED
        day.pending.contains(item) -> CellState.PENDING
        else -> CellState.EMPTY
    }
}

/**
 * 7 行 × 6 列的可点表格。每行一个 [DayRecord]，格子由代码生成，避免 6 份重复 XML。
 *
 * 点击路由由 [childMode] 决定：孩子模式走 [onChildTap]（只申报，不计分），
 * 家长模式走 [onParentTap]（确认/取消）。
 */
class WeekRowAdapter(
    private val items: List<Item>,
    private val childMode: () -> Boolean,
    private val onChildTap: (epochDay: Long, item: Item) -> Unit,
    private val onParentTap: (epochDay: Long, item: Item) -> Unit,
    private val onParentLongPress: (epochDay: Long, item: Item) -> Unit
) : RecyclerView.Adapter<WeekRowAdapter.RowHolder>() {

    private var days: List<DayRecord> = emptyList()
    private var scores: List<Int> = emptyList()

    fun submit(newDays: List<DayRecord>, newScores: List<Int>) {
        days = newDays
        scores = newScores
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = days.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ItemWeekRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RowHolder(binding)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val day = days[position]
        holder.bind(day, scores.getOrElse(position) { 0 })
    }

    inner class RowHolder(private val binding: ItemWeekRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val cells = mutableListOf<TextView>()

        init {
            items.forEach { _ ->
                val ctx = binding.root.context
                val tv = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    ).apply {
                        val gap = (2 * ctx.resources.displayMetrics.density).toInt()
                        setMargins(gap, gap, gap, gap)
                    }
                    gravity = Gravity.CENTER
                }
                binding.cellContainer.addView(tv)
                cells.add(tv)
            }
        }

        fun bind(day: DayRecord, score: Int) {
            binding.dayLabel.text = DayLabels.weekdayName(WeekDates.dayOfWeek(day.epochDay))
            binding.dayScore.text = score.toString()
            binding.dayScore.setTextColor(if (score < 0) Color.RED else Color.BLACK)

            items.forEachIndexed { index, item ->
                val cell = cells[index]
                render(cell, CellStateResolver.resolve(item, day))
                cell.setOnClickListener {
                    if (childMode()) onChildTap(day.epochDay, item)
                    else onParentTap(day.epochDay, item)
                }
                cell.setOnLongClickListener {
                    onParentLongPress(day.epochDay, item)
                    true
                }
            }
        }

        /** 空＝灰圆；待确认＝黄底"待确认"；已确认＝绿底勾。 */
        private fun render(cell: TextView, state: CellState) {
            when (state) {
                CellState.EMPTY -> {
                    cell.text = "○"
                    cell.textSize = 16f
                    cell.setBackgroundColor(EMPTY_BG)
                    cell.setTextColor(EMPTY_FG)
                }
                CellState.PENDING -> {
                    cell.text = "待确认"
                    cell.textSize = 11f
                    cell.setBackgroundColor(PENDING_BG)
                    cell.setTextColor(PENDING_FG)
                }
                CellState.CONFIRMED -> {
                    cell.text = "✓"
                    cell.textSize = 18f
                    cell.setBackgroundColor(CONFIRMED_BG)
                    cell.setTextColor(CONFIRMED_FG)
                }
            }
        }
    }

    private companion object {
        val EMPTY_BG = Color.parseColor("#F5F5F5")
        val EMPTY_FG = Color.parseColor("#9E9E9E")
        val PENDING_BG = Color.parseColor("#FFF9C4")
        val PENDING_FG = Color.parseColor("#F57F17")
        val CONFIRMED_BG = Color.parseColor("#C8E6C9")
        val CONFIRMED_FG = Color.parseColor("#2E7D32")
    }
}
