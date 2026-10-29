package com.vdian.screentime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.databinding.DialogExamInputBinding
import com.vdian.screentime.databinding.FragmentWeekBinding
import com.vdian.screentime.databinding.ItemSummaryLineBinding
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.Subject

/**
 * 本周表格：7 天 × 6 项的可点格子，对应纸质记录表。
 * 点击语义随"孩子申报模式"开关切换，见 [WeekViewModel]。
 *
 * 下半张纸质表（汇总卡片 + 一句话总结）也在这里渲染。界面不做任何算术，
 * 所有数字都直接取自 [WeekBoard.summary]。
 */
class WeekFragment : Fragment() {

    private var _binding: FragmentWeekBinding? = null
    private val binding get() = _binding!!

    private val vm: WeekViewModel by viewModels {
        WeekViewModelFactory(AppContainer.repository(requireContext()))
    }

    private lateinit var adapter: WeekRowAdapter

    /** 最近一次渲染用的 board，供考试对话框回填当前分数。 */
    private var lastBoard: WeekBoard? = null

    /**
     * 正在用 board 回填输入框时为 true，用来吞掉由此产生的文字变化回调，
     * 避免"回填 -> 触发保存 -> 又写库 -> 又回填"的回声。
     */
    private var fillingNotes = false

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

        // 整页套在 ScrollView 里，表格自己不滚，滚动手势交给父容器统一处理，
        // 否则在表格区域起手时手势会被 RecyclerView 吃掉，页面卡住不动。
        binding.weekRows.isNestedScrollingEnabled = false
        binding.weekRows.itemAnimator = null

        binding.prevWeek.setOnClickListener { vm.prevWeek() }
        binding.nextWeek.setOnClickListener { vm.nextWeek() }
        binding.childModeSwitch.setOnCheckedChangeListener { _, checked ->
            vm.childMode.value = checked
        }

        binding.summaryCard.examButton.setOnClickListener { showExamDialog() }
        binding.saveNotes.setOnClickListener { saveNotes() }

        vm.board.observe(viewLifecycleOwner) { board ->
            lastBoard = board
            binding.weekTitle.text = DayLabels.formatDateRange(board.weekStart)
            renderHeader(board.catalog)
            adapter.submit(board.days, board.summary.dailyScores)
            renderSummary(board)
            renderNotes(board)
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
    private fun renderHeader(catalog: ItemCatalog) {
        val ctx = requireContext()
        val row = binding.headerRow
        row.removeAllViews()
        row.addView(ItemColumns.spacer(ctx, ItemColumns.LABEL_WIDTH_DP))
        ItemColumns.ROW_ITEMS.forEach { item ->
            row.addView(ItemColumns.headerCell(ctx, DayLabels.itemHeader(item, catalog)))
        }
        row.addView(ItemColumns.spacer(ctx, ItemColumns.SCORE_WIDTH_DP))
    }

    /**
     * 画汇总卡片。行内容与数值都来自 [SummaryBinder]，此处只负责塞进容器。
     * 行首行尾的占位按表格的列宽设置，让"项目名 / 值"与上方数据行对齐。
     */
    private fun renderSummary(board: WeekBoard) {
        val container = binding.summaryCard.summaryLines
        container.removeAllViews()
        val leadWidth = ItemColumns.dp(requireContext(), ItemColumns.LABEL_WIDTH_DP)
        val tailWidth = ItemColumns.dp(requireContext(), ItemColumns.SCORE_WIDTH_DP)
        SummaryBinder.lines(board.summary).forEach { (name, value) ->
            val row = ItemSummaryLineBinding.inflate(layoutInflater, container, false)
            row.lineName.text = name
            row.lineValue.text = value
            row.lineLeadSpacer.layoutParams =
                row.lineLeadSpacer.layoutParams.apply { width = leadWidth }
            row.lineTailSpacer.layoutParams =
                row.lineTailSpacer.layoutParams.apply { width = tailWidth }
            container.addView(row.root)
        }

        val hint = SummaryBinder.overflowHint(board.summary)
        binding.summaryCard.overflowHint.text = hint
        binding.summaryCard.overflowHint.visibility =
            if (hint.isEmpty()) View.GONE else View.VISIBLE

        binding.summaryCard.examSummary.text = SummaryBinder.examSummary(board)
    }

    /**
     * 回填一句话总结。**只在文字确实不同时才写**：
     * 这个回调挂在 board 的观察上，而每次格子变更、考试录入都会触发一次数据库写入、
     * 进而触发一次回调。若无条件 setText，用户打字打到一半就会被重置、光标跳到末尾。
     */
    private fun renderNotes(board: WeekBoard) {
        val good = board.meta.noteGood
        val improve = board.meta.noteImprove
        if (binding.noteGood.text.toString() == good &&
            binding.noteImprove.text.toString() == improve
        ) {
            return
        }
        fillingNotes = true
        try {
            if (binding.noteGood.text.toString() != good) binding.noteGood.setText(good)
            if (binding.noteImprove.text.toString() != improve) binding.noteImprove.setText(improve)
        } finally {
            fillingNotes = false
        }
    }

    /** 保存总结。文字与库里一致时不写，省掉一次无谓的写入。 */
    private fun saveNotes() {
        if (fillingNotes) return
        val board = lastBoard ?: return
        val good = binding.noteGood.text.toString()
        val improve = binding.noteImprove.text.toString()
        if (good == board.meta.noteGood && improve == board.meta.noteImprove) return
        vm.saveNotes(good, improve)
    }

    /**
     * 考试录入对话框。四科各一个输入框，确定后逐科提交；
     * 空串或非数字由 [WeekViewModel.setExam] 按 0 处理，等于撤销该科成绩。
     */
    private fun showExamDialog() {
        val dialogBinding = DialogExamInputBinding.inflate(layoutInflater)
        prefillExams(dialogBinding)

        AlertDialog.Builder(requireContext())
            .setTitle("录入考试成绩")
            .setView(dialogBinding.root)
            .setPositiveButton("确定") { _, _ ->
                vm.setExam(Subject.ENGLISH, dialogBinding.examEnglish.text.toString())
                vm.setExam(Subject.CHINESE, dialogBinding.examChinese.text.toString())
                vm.setExam(Subject.MATH, dialogBinding.examMath.text.toString())
                vm.setExam(Subject.SCIENCE, dialogBinding.examScience.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用当前周的已有成绩回填，便于只改其中一科。 */
    private fun prefillExams(b: DialogExamInputBinding) {
        val board = lastBoard ?: return
        val scores = SummaryBinder.examScores(board)
        b.examEnglish.setText(scores[Subject.ENGLISH]?.takeIf { it > 0 }?.toString() ?: "")
        b.examChinese.setText(scores[Subject.CHINESE]?.takeIf { it > 0 }?.toString() ?: "")
        b.examMath.setText(scores[Subject.MATH]?.takeIf { it > 0 }?.toString() ?: "")
        b.examScience.setText(scores[Subject.SCIENCE]?.takeIf { it > 0 }?.toString() ?: "")
    }
}
