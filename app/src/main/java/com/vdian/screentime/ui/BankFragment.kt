package com.vdian.screentime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.vdian.screentime.data.BankRollup
import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.databinding.DialogRedeemBinding
import com.vdian.screentime.databinding.FragmentBankBinding
import com.vdian.screentime.domain.BankCalculator

/**
 * 屏幕银行页：大字余额、本周明细、兑换与记录入口。
 *
 * 界面不做任何算术 —— 分钟与钱的换算一律回到 [BankCalculator] 的纯函数，
 * 与仓库层的兑换规则共用同一套实现，避免两处口径漂移。
 */
class BankFragment : Fragment() {

    private var _binding: FragmentBankBinding? = null
    private val binding get() = _binding!!

    private val vm: BankViewModel by viewModels {
        BankViewModelFactory(AppContainer.repository(requireContext()))
    }

    /** 最近一次渲染的 board，供兑换对话框取本周额度与规则参数。 */
    private var lastBoard: WeekBoard? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBankBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bankRedeemButton.setOnClickListener { showRedeemDialog() }
        binding.bankRecordUsageButton.setOnClickListener { showMinutesDialog("记录使用时长") { vm.recordUsage(it) } }
        binding.bankRecordOvertimeButton.setOnClickListener { showMinutesDialog("超时扣减") { vm.recordOvertime(it) } }
        binding.bankSavingAddButton.setOnClickListener { showSavingDialog(isAdd = true) }
        binding.bankSavingDeductButton.setOnClickListener { showSavingDialog(isAdd = false) }

        vm.rollup.observe(viewLifecycleOwner) { renderRollup(it) }
        vm.board.observe(viewLifecycleOwner) { board ->
            lastBoard = board
            renderBoard(board)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderRollup(r: BankRollup) {
        binding.bankBalance.text = r.balanceText()
        binding.bankTotalRedeemed.text = yuanText(r.totalRedeemedCents)
        binding.bankSavingTotal.text = yuanText(r.cashAvailableCents)
    }

    private fun renderBoard(board: WeekBoard) {
        val cfg = board.config
        val bank = board.bank

        binding.bankWeekBalance.text = BankRollup.minutesText(board.summary.balance)
        binding.bankWeekRedeemed.text = BankRollup.minutesText(board.meta.redeemedMinutes)
        binding.bankRedeemable.text = BankRollup.minutesText(bank.redeemableMinutesThisWeek)

        binding.bankCapHint.text = "上限 ${BankRollup.minutesText(cfg.bankCapMinutes)}"
        binding.bankSavingHint.text = "存钱上限 ${yuanText(cfg.savingCapCents)}"

        // 本周额度用尽则置灰并说明原因。
        val exhausted = bank.redeemableMinutesThisWeek <= 0
        binding.bankRedeemButton.isEnabled = !exhausted
        binding.bankRedeemLimitHint.text = if (exhausted) {
            val moneyMaxed = bank.redeemableCentsThisWeek <= 0 &&
                board.meta.redeemedCents >= cfg.weeklyRedeemCapCents
            if (moneyMaxed) "已达每周兑换上限（${yuanText(cfg.weeklyRedeemCapCents)}）"
            else "暂无可兑换的余额"
        } else {
            "本周还能兑换 ${BankRollup.minutesText(bank.redeemableMinutesThisWeek)}，约 ${yuanText(bank.redeemableCentsThisWeek)}"
        }
    }

    /** 兑换对话框。选项按兑换单位生成，步长天然等于一个单位。 */
    private fun showRedeemDialog() {
        val board = lastBoard ?: return
        val cfg = board.config
        val options = BankViewModel.redeemOptions(cfg, board.bank.redeemableMinutesThisWeek)
        if (options.isEmpty()) return

        val dialogBinding = DialogRedeemBinding.inflate(layoutInflater)
        dialogBinding.redeemPicker.minValue = 0
        dialogBinding.redeemPicker.maxValue = options.size - 1
        dialogBinding.redeemPicker.displayedValues = options.map { "${it} 分钟" }.toTypedArray()
        dialogBinding.redeemPicker.wrapSelectorWheel = false
        dialogBinding.redeemHint.text = "本周还能兑换 ${BankRollup.minutesText(board.bank.redeemableMinutesThisWeek)}"

        fun updatePreview() {
            val minutes = options[dialogBinding.redeemPicker.value]
            val cents = BankCalculator.centsForMinutes(minutes, cfg)
            dialogBinding.redeemPreview.text = "可得 ${yuanText(cents)}"
        }
        dialogBinding.redeemPicker.setOnValueChangedListener { _, _, _ -> updatePreview() }
        updatePreview()

        AlertDialog.Builder(requireContext())
            .setTitle("兑换屏幕时间")
            .setView(dialogBinding.root)
            .setPositiveButton("确定") { _, _ ->
                vm.redeem(options[dialogBinding.redeemPicker.value])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** "记录使用时长 / 超时扣减"共用的分钟输入对话框。步长取自规则参数，默认 15 分钟。 */
    private fun showMinutesDialog(title: String, onConfirm: (Int) -> Unit) {
        val board = lastBoard ?: return
        val step = board.config.usageStepMinutes
        val values = (0..12).map { it * step }    // 0 ~ 12 个单位，够日常录入

        val picker = NumberPicker(requireContext()).apply {
            minValue = 0
            maxValue = values.size - 1
            displayedValues = values.map { "${it} 分钟" }.toTypedArray()
            wrapSelectorWheel = false
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(picker)
            .setPositiveButton("确定") { _, _ -> onConfirm(values[picker.value]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 存钱的人工存入 / 扣减。选项以 1 元为步长；扣减最多到当前可用金额，
     * 仓库层还会再夹一次，界面传多了也不会把余额扣成负数。
     */
    private fun showSavingDialog(isAdd: Boolean) {
        val board = lastBoard ?: return
        val available = vm.rollup.value?.cashAvailableCents ?: 0
        val capYuan = board.config.savingCapCents / 100
        // 存入时最多补到上限；扣减时最多扣掉当前可用金额。
        val maxYuan = if (isAdd) {
            ((board.config.savingCapCents - available) / 100).coerceAtLeast(0)
        } else {
            (available / 100).coerceAtLeast(0)
        }
        if (maxYuan <= 0) {
            val msg = if (isAdd) "已达存钱上限（$capYuan 元）" else "暂无可扣减的存钱"
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val values = (1..maxYuan).toList()
        val picker = NumberPicker(requireContext()).apply {
            minValue = 0
            maxValue = values.size - 1
            displayedValues = values.map { "$it 元" }.toTypedArray()
            wrapSelectorWheel = false
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (isAdd) "存入存钱" else "扣减存钱")
            .setView(picker)
            .setPositiveButton("确定") { _, _ ->
                val yuan = values[picker.value]
                vm.adjustSaving(if (isAdd) yuan * 100 else -yuan * 100)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 分 -> "X 元"。纯展示格式，不改变任何业务规则。 */
    private fun yuanText(cents: Int): String {
        val yuan = cents / 100
        val rest = cents % 100
        return if (rest == 0) "$yuan 元"
        else "%d.%02d 元".format(yuan, rest)
    }
}
