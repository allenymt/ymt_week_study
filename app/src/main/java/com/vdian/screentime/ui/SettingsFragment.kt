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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vdian.screentime.data.RuleConfigCodec
import com.vdian.screentime.data.SettingsStore
import com.vdian.screentime.databinding.DialogItemDefBinding
import com.vdian.screentime.databinding.FragmentSettingsBinding
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.ItemDefEditor
import com.vdian.screentime.domain.RuleConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：孩子姓名、规则参数、考核项管理分组编辑、导出、导入、清空。
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

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { readImport(it) } }

    /** 当前显示中的导入对话框。视图销毁时关掉，重建后按 [SettingsViewModel.importDialog] 再弹。 */
    private var importDialog: androidx.appcompat.app.AlertDialog? = null

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

        val adapter = ItemDefAdapter(
            onEdit = { showItemDialog(it) },
            onMove = { def, up -> vm.moveItem(def.item, up) },
            onToggle = { def, on -> vm.setItemEnabled(def.item, on) },
            onDelete = { confirmDelete(it) }
        )
        binding.itemDefList.layoutManager = LinearLayoutManager(requireContext())
        binding.itemDefList.adapter = adapter
        binding.itemDefList.itemAnimator = null
        binding.addItemButton.setOnClickListener { showItemDialog(null) }
        vm.itemDefs.observe(viewLifecycleOwner) { adapter.submitList(it) }
        binding.exportButton.setOnClickListener { vm.export() }
        binding.clearButton.setOnClickListener { confirmClear() }
        binding.importButton.setOnClickListener {
            openDocument.launch(arrayOf("application/json", "*/*"))
        }
        vm.importDialog.observe(viewLifecycleOwner) { showImportDialog(it) }

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
        // 只关窗口不改状态：dismiss() 不触发取消回调，重建后会按 ViewModel 里的状态再弹一次。
        importDialog?.dismiss()
        importDialog = null
        super.onDestroyView()
        _binding = null
    }

    /**
     * 参数输入框与字段名的对应表。顺序即界面上的分组顺序。
     * 字段名与默认值均取自 [RuleConfigCodec]，改默认值不必改这里。
     */
    private fun fieldViews(): List<Pair<String, EditText>> = binding.run {
        listOf(
            "complaintMaxPerWeek" to cfgComplaintMaxPerWeek,
            "baseMinutes" to cfgBaseMinutes,
            "noComplaintBonusMinutes" to cfgNoComplaintBonusMinutes,
            "weekCapMinutes" to cfgWeekCapMinutes,
            "bankCapMinutes" to cfgBankCapMinutes,
            "redeemUnitMinutes" to cfgRedeemUnitMinutes,
            "usageStepMinutes" to cfgUsageStepMinutes,
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

    private fun confirmDelete(def: ItemDef) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除「${def.name}」？")
            .setPositiveButton("删除") { _, _ -> vm.deleteItem(def.item) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新增（[old] 为空）或编辑考核项。校验不过时对话框保持打开并在输入框上提示。 */
    private fun showItemDialog(old: ItemDef?) {
        val d = DialogItemDefBinding.inflate(layoutInflater)
        if (old != null) {
            d.defName.setText(old.name)
            d.defPoints.setText(old.points.toString())
            d.defClaimable.isChecked = old.childClaimable
            d.defGroup.setText(old.exclusiveGroup.orEmpty())
        } else {
            d.defClaimable.isChecked = true
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (old == null) "新增考核项" else "编辑考核项")
            .setView(d.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = vm.itemDefs.value.orEmpty()
                val result = ItemDefEditor.build(
                    base = old,
                    newItem = Item(ItemDefEditor.newKey(System.currentTimeMillis())),
                    sortOrder = ItemDefEditor.nextSortOrder(current),
                    name = d.defName.text.toString(),
                    pointsText = d.defPoints.text.toString(),
                    childClaimable = d.defClaimable.isChecked,
                    exclusiveGroup = d.defGroup.text.toString()
                )
                d.defName.error = result.nameError
                d.defPoints.error = result.pointsError
                val def = result.def ?: return@setOnClickListener
                vm.saveItem(def, old)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认清空？")
            .setMessage("所有记录将被永久删除，无法恢复。")
            .setPositiveButton("清空") { _, _ -> vm.clearAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 读文件由 ViewModel 放到 IO 线程执行；只捕获 applicationContext 的 resolver，不持有 Fragment。 */
    private fun readImport(uri: Uri) {
        val resolver = requireContext().applicationContext.contentResolver
        vm.importFrom {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_IMPORT_BYTES) error("文件过大")
                String(bytes, Charsets.UTF_8)
            } ?: error("无法打开文件")
        }
    }

    private fun showImportDialog(state: ImportDialog?) {
        importDialog?.dismiss()
        importDialog = null
        if (state == null) return
        val builder = MaterialAlertDialogBuilder(requireContext())
        when (state) {
            is ImportDialog.Failed -> builder
                .setTitle("无法导入")
                .setMessage(state.reason)
                .setPositiveButton("知道了") { _, _ -> vm.dismissImport() }
            is ImportDialog.Confirm -> builder
                .setTitle("确认导入？")
                .setMessage(state.message)
                .setPositiveButton("覆盖并导入") { _, _ -> vm.confirmImport() }
                .setNegativeButton("取消") { _, _ -> vm.dismissImport() }
        }
        builder.setOnCancelListener { vm.dismissImport() }
        importDialog = builder.show()
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

    private companion object {
        /** 导入文件大小上限。正常备份只有几十 KB，超过这个数基本是选错了文件。 */
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
    }
}
