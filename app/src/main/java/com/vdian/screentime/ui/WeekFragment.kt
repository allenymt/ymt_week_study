package com.vdian.screentime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.vdian.screentime.databinding.FragmentWeekBinding
import com.vdian.screentime.domain.Item

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
            items = ROW_ITEMS,
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
            adapter.submit(board.days, board.summary.dailyScores)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** 表格列顺序，与纸质记录表一致。 */
        val ROW_ITEMS = listOf(
            Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH,
            Item.READING, Item.EXERCISE, Item.COMPLAINT
        )
    }
}
