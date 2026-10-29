package com.vdian.screentime.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vdian.screentime.databinding.ItemItemDefBinding
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.ItemDefEditor

/** 设置页考核项列表。只负责渲染与回调，所有写操作交给外部。 */
class ItemDefAdapter(
    private val onEdit: (ItemDef) -> Unit,
    private val onMove: (ItemDef, Boolean) -> Unit,
    private val onToggle: (ItemDef, Boolean) -> Unit,
    private val onDelete: (ItemDef) -> Unit
) : ListAdapter<ItemDef, ItemDefAdapter.Holder>(Diff) {

    class Holder(val b: ItemItemDefBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemItemDefBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val def = getItem(position)
        holder.b.apply {
            itemName.text = def.name
            itemPoints.text = ItemDefEditor.signedPoints(def.points)
            itemUp.isEnabled = position > 0
            itemDown.isEnabled = position < itemCount - 1
            // 先清掉旧监听再设状态，避免复用时误触发回调
            itemEnabled.setOnCheckedChangeListener(null)
            itemEnabled.isChecked = def.enabled
            itemEnabled.setOnCheckedChangeListener { _, checked -> onToggle(def, checked) }
            itemUp.setOnClickListener { onMove(def, true) }
            itemDown.setOnClickListener { onMove(def, false) }
            itemDelete.setOnClickListener { onDelete(def) }
            root.setOnClickListener { onEdit(def) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ItemDef>() {
        override fun areItemsTheSame(a: ItemDef, b: ItemDef) = a.item == b.item
        override fun areContentsTheSame(a: ItemDef, b: ItemDef) = a == b
    }
}
