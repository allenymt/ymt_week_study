package com.vdian.screentime.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vdian.screentime.databinding.ItemWeekRowBinding
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemDef
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
 * 7 行 × N 列（N 随目录变化）的可点表格。每行一个 [DayRecord]，格子由代码生成。
 * 本 Adapter 只渲染中间的考核项格子；星期标签与小计列由 [WeekFragment] 在两侧固定渲染。
 *
 * 点击路由由 [childMode] 决定：孩子模式走 [onChildTap]（只申报，不计分），
 * 家长模式走 [onParentTap]（确认/取消）。
 */
class WeekRowAdapter(
    private val childMode: () -> Boolean,
    private val onChildTap: (epochDay: Long, item: Item) -> Unit,
    private val onParentTap: (epochDay: Long, item: Item) -> Unit,
    private val onParentLongPress: (epochDay: Long, item: Item) -> Unit
) : RecyclerView.Adapter<WeekRowAdapter.RowHolder>() {

    private var days: List<DayRecord> = emptyList()
    private var columns: List<ItemDef> = emptyList()

    /** 列集合与数据一起提交；列变化时 [onBindViewHolder] 会让各行重建格子。 */
    fun submit(newColumns: List<ItemDef>, newDays: List<DayRecord>) {
        columns = newColumns
        days = newDays
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
        holder.bind(day, columns)
    }

    inner class RowHolder(private val binding: ItemWeekRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val cells = mutableListOf<TextView>()

        private var builtFor: List<Item>? = null

        /** 列集合（项及顺序）变了就整行重建；否则复用已有格子。 */
        private fun ensureCells(columns: List<ItemDef>) {
            val key = columns.map { it.item }
            if (key == builtFor) return
            binding.root.removeAllViews()
            cells.clear()
            columns.forEach { _ ->
                val tv = ItemColumns.cell(binding.root.context)
                binding.root.addView(tv)
                cells.add(tv)
            }
            builtFor = key
        }

        fun bind(day: DayRecord, columns: List<ItemDef>) {
            ensureCells(columns)
            columns.forEachIndexed { index, def ->
                val item = def.item
                val cell = cells[index]
                render(cell, CellStateResolver.resolve(item, day))
                cell.setOnClickListener {
                    if (childMode()) {
                        // 孩子不可申报的项：UI 不派发（仓库层另有兜底）
                        if (def.childClaimable) onChildTap(day.epochDay, item)
                    } else onParentTap(day.epochDay, item)
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
