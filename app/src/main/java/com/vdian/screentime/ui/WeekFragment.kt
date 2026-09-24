package com.vdian.screentime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.vdian.screentime.databinding.FragmentWeekBinding
import com.vdian.screentime.domain.RuleConfig

/**
 * 本周表格：7 天 × 6 项的可点格子，对应纸质记录表。
 * 点击语义随"孩子申报模式"开关切换，见 [WeekViewModel]。
 */
class WeekFragment : Fragment() {

    private var _binding: FragmentWeekBinding? = null
    private val binding get() = _binding!!

    private val vm: WeekViewModel by viewModels {
        WeekViewModelFactory(AppContainer.repository(requireContext()))
    }

    private lateinit var adapter: WeekRowAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeekBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = WeekRowAdapter(
            items = ItemColumns.ROW_ITEMS,
            childMode = { vm.childMode.value == true },
            onChildTap = { day, item -> vm.onChildTap(day, item) },
            onParentTap = { day, item -> vm.onParentTap(day, item) },
            onParentLongPress = { day, item -> vm.onParentLongPress(day, item) }
        )
        binding.weekRows.layoutManager = LinearLayoutManager(requireContext())
        binding.weekRows.adapter = adapter

        binding.prevWeek.setOnClickListener { vm.prevWeek() }
        binding.nextWeek.setOnClickListener { vm.nextWeek() }
        binding.childModeSwitch.setOnCheckedChangeListener { _, checked ->
            vm.childMode.value = checked
        }

        vm.board.observe(viewLifecycleOwner) { board ->
            binding.weekTitle.text = DayLabels.formatDateRange(board.weekStart)
            renderHeader(board.config)
            adapter.submit(board.days, board.summary.dailyScores)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 画列标题行。两侧用与数据行同宽的占位，中间的格子走 [ItemColumns]，
     * 这样表头与数据行的列宽、列序都锁在一起。
     */
    private fun renderHeader(cfg: RuleConfig) {
        val ctx = requireContext()
        val row = binding.headerRow
        row.removeAllViews()
        row.addView(ItemColumns.spacer(ctx, ItemColumns.LABEL_WIDTH_DP))
        ItemColumns.ROW_ITEMS.forEach { item ->
            row.addView(ItemColumns.headerCell(ctx, DayLabels.itemHeader(item, cfg)))
        }
        row.addView(ItemColumns.spacer(ctx, ItemColumns.SCORE_WIDTH_DP))
    }
}
