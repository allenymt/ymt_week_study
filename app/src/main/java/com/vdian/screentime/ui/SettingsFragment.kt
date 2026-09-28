package com.vdian.screentime.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vdian.screentime.data.RuleConfigCodec
import com.vdian.screentime.data.SettingsStore
import com.vdian.screentime.databinding.FragmentSettingsBinding
import com.vdian.screentime.domain.RuleConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：孩子姓名、21 个规则参数分组编辑、导出、清空。
 *
 * 输入框与字段的对应关系只在这里列一次；字段名与默认值都取自 [RuleConfigCodec]，
 * 和存储层共用同一份清单，新增参数时不会两边写岔。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val vm: SettingsViewModel by viewModels {
        SettingsViewModelFactory(
            AppContainer.repository(requireContext()),
            SettingsStore(requireContext().applicationContext)
        )
    }

    /** 最近一次渲染进输入框的配置。用户没改过就不必回写。 */
    private var rendered: RuleConfig? = null

    /** 回填输入框期间置位，避免 TextWatcher 之类的回写打断正在输入的内容。 */
    private var filling = false

    /** 已经为哪一份导出文本拉起过"另存为"。旋转重建时不该再弹一次。 */
    private var launchedExportJson: String? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument()
    ) { uri: Uri? -> uri?.let { writeExport(it) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.saveSettingsButton.setOnClickListener { save() }
        binding.exportButton.setOnClickListener { vm.export() }
        binding.clearButton.setOnClickListener { confirmClear() }

        vm.config.observe(viewLifecycleOwner) { renderConfig(it) }
        vm.childName.observe(viewLifecycleOwner) { renderChildName(it) }
        vm.exportJson.observe(viewLifecycleOwner) { json ->
            if (json != launchedExportJson) {
                launchedExportJson = json
                createDocument.launch(exportFileName())
            }
        }
        vm.message.observe(viewLifecycleOwner) { msg ->
            binding.settingsMessage.text = msg
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 参数输入框与字段名的对应表。顺序即界面上的分组顺序。
     * 字段名与默认值均取自 [RuleConfigCodec]，改默认值不必改这里。
     */
    private fun fieldViews(): List<Pair<String, EditText>> = binding.run {
        listOf(
            "raisePoints" to cfgRaisePoints,
            "neatWritingPoints" to cfgNeatWritingPoints,
            "englishPoints" to cfgEnglishPoints,
            "readingPoints" to cfgReadingPoints,
            "exercisePoints" to cfgExercisePoints,
            "complaintPenalty" to cfgComplaintPenalty,
            "complaintMaxPerWeek" to cfgComplaintMaxPerWeek,
            "baseMinutes" to cfgBaseMinutes,
            "noComplaintBonusMinutes" to cfgNoComplaintBonusMinutes,
            "weekCapMinutes" to cfgWeekCapMinutes,
            "bankCapMinutes" to cfgBankCapMinutes,
            "redeemUnitMinutes" to cfgRedeemUnitMinutes,
            "redeemUnitCents" to cfgRedeemUnitCents,
            "weeklyRedeemCapCents" to cfgWeeklyRedeemCapCents,
            "savingCapCents" to cfgSavingCapCents,
            "englishMidMin" to cfgEnglishMidMin,
            "englishHighMin" to cfgEnglishHighMin,
            "englishMidBonus" to cfgEnglishMidBonus,
            "englishHighBonus" to cfgEnglishHighBonus,
            "otherSubjectMin" to cfgOtherSubjectMin,
            "otherSubjectBonus" to cfgOtherSubjectBonus
        )
    }

    /** 用规则参数回填输入框，并对齐光标位置，避免每次回写都跳回开头。 */
    private fun renderConfig(cfg: RuleConfig) {
        if (filling) return
        filling = true
        try {
            val fields = fieldViews().toMap()
            RuleConfigCodec.encode(cfg).forEach { (name, value) ->
                val field = fields[name] ?: return@forEach
                val text = value.toString()
                if (field.text.toString() != text) {
                    val selection = field.selectionStart
                    field.setText(text)
                    field.setSelection(selection.coerceIn(0, text.length))
                }
            }
            rendered = cfg
        } finally {
            filling = false
        }
    }

    /** 孩子姓名同样只在值真的变了才回填，否则打字打一半会被数据库回写打断。 */
    private fun renderChildName(name: String) {
        if (filling) return
        if (binding.childNameInput.text.toString() == name) return
        filling = true
        try {
            binding.childNameInput.setText(name)
        } finally {
            filling = false
        }
    }

    private fun save() {
        val base = rendered ?: RuleConfig()
        val texts = fieldViews().associate { (name, field) -> name to field.text.toString() }
        vm.save(texts, binding.childNameInput.text.toString().trim(), base)
        // 收起软键盘，让用户看到"已保存"的提示。
        activity?.currentFocus?.clearFocus()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认清空？")
            .setMessage("所有记录将被永久删除，无法恢复。")
            .setPositiveButton("清空") { _, _ -> vm.clearAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun writeExport(uri: Uri) {
        val json = launchedExportJson ?: return
        val ok = runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("无法打开输出流")
        }.isSuccess
        Toast.makeText(
            requireContext(),
            if (ok) "已导出" else "导出失败",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun exportFileName(): String =
        "screen-time-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json"
}
