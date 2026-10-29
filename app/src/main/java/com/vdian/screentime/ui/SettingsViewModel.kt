package com.vdian.screentime.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdian.screentime.data.DataExporter
import com.vdian.screentime.data.DataImporter
import com.vdian.screentime.data.ImportPlan
import com.vdian.screentime.data.ImportResult
import com.vdian.screentime.data.ImportSettingsException
import com.vdian.screentime.domain.DeleteItemResult
import com.vdian.screentime.data.RuleConfigCodec
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.SettingsStore
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.ItemDefEditor
import com.vdian.screentime.domain.RuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的视图模型：规则参数、孩子姓名、导出与清空。
 *
 * 规则参数的字段名一律取自 [RuleConfigCodec]，界面只按名字读写字符串，
 * 新增字段时这里不必再列一遍，也不会和存储层对不上。
 */
class SettingsViewModel(
    private val repo: ScreenTimeRepository,
    private val settings: SettingsStore
) : ViewModel() {

    private val _config = MutableLiveData<RuleConfig>()
    val config: LiveData<RuleConfig> = _config

    private val _childName = MutableLiveData("")
    val childName: LiveData<String> = _childName

    /** 导出文本。生成成功才赋值，界面据此拉起系统"另存为"。 */
    private val _exportJson = MutableLiveData<String>()
    val exportJson: LiveData<String> = _exportJson

    private val _message = MutableLiveData<String>()
    val message: LiveData<String> = _message

    /** 导入流程中等待用户处理的对话框；null 表示没有。放在这里，旋转重建后能再弹出来。 */
    private val _importDialog = MutableLiveData<ImportDialog?>(null)
    val importDialog: LiveData<ImportDialog?> = _importDialog

    private var importJob: Job? = null

    private val _itemDefs = MutableLiveData<List<ItemDef>>(emptyList())

    /** 全部考核项（含已停用），按 sortOrder 升序。 */
    val itemDefs: LiveData<List<ItemDef>> = _itemDefs

    init {
        viewModelScope.launch {
            repo.observeItemCatalog().collectLatest { c ->
                _itemDefs.value = c.defs.sortedBy { it.sortOrder }
            }
        }
        viewModelScope.launch {
            settings.observeConfig().collectLatest { _config.value = it }
        }
        viewModelScope.launch {
            settings.observeChildName().collectLatest { _childName.value = it }
        }
    }

    /**
     * 保存设置。
     *
     * [texts] 的键是 [RuleConfigCodec] 的字段名。空串、非数字一律回落到 [base] 里同名字段的值，
     * 所以用户不小心删空了某一格，得到的是"保持原值"，不会把一分/一分钟静默改成 0。
     */
    fun save(texts: Map<String, String>, childName: String, base: RuleConfig) {
        val values = RuleConfigCodec.encode(base).mapValues { (name, fallback) ->
            texts[name]?.trim()?.toIntOrNull() ?: fallback
        }
        val cfg = RuleConfigCodec.decode(values, base)
        viewModelScope.launch {
            repo.saveConfig(cfg)  // 经仓库保存：写完后重放银行链
            settings.saveChildName(childName)
            _message.value = "已保存"
        }
    }

    /** 取全部数据生成 JSON 文本。一次全表读 + 字符串拼接，放到 IO 线程上做。 */
    fun export() {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                DataExporter.toJson(
                    days = repo.exportDays(),
                    weeks = repo.exportWeeks(),
                    exams = repo.exportExams(),
                    itemDefs = repo.exportItemDefs(),
                    cfg = repo.currentConfig(),
                    childName = repo.childName()
                )
            }
            _exportJson.value = json
        }
    }

    /**
     * 读取并解析用户选中的文件。[readText] 在 IO 线程执行。
     * 只解析校验、不碰数据库：失败弹原因，成功弹确认框。
     */
    fun importFrom(readText: () -> String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val text = try {
                    readText()
                } catch (e: Exception) {
                    return@withContext ImportResult.Error("无法读取文件：${e.message ?: e.javaClass.simpleName}")
                }
                DataImporter.parse(text)
            }
            _importDialog.value = when (result) {
                is ImportResult.Error -> ImportDialog.Failed(result.reason)
                is ImportResult.Ok -> ImportDialog.Confirm(
                    plan = result.plan,
                    message = DataImporter.confirmMessage(result.plan, repo.currentConfig())
                )
            }
        }
    }

    /** 关闭导入对话框（取消或已读失败原因）。 */
    fun dismissImport() {
        _importDialog.value = null
    }

    /** 用户确认覆盖。导入进行中再点一次不会重复执行。 */
    fun confirmImport() {
        val plan = (_importDialog.value as? ImportDialog.Confirm)?.plan ?: return
        _importDialog.value = null
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _message.value = "正在导入…"
            _message.value = try {
                repo.applyImport(plan)
                "已导入：${plan.days.size} 天、${plan.weeks.size} 周、${plan.exams.size} 条考试记录"
            } catch (e: ImportSettingsException) {
                "${e.message}。请核对规则参数与孩子姓名后点保存"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "导入失败，现有数据未改动：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repo.clearAllData()
            _message.value = "已清空所有记录"
        }
    }

    /** 新增或编辑后保存。改了分值时提示会重算历史。 */
    fun saveItem(def: ItemDef, old: ItemDef?) {
        viewModelScope.launch {
            repo.upsertItemDef(def)
            _message.value = if (ItemDefEditor.pointsChanged(old, def)) {
                "已保存。修改分值会重新计算所有历史周的分数，当前银行余额也会随之变化"
            } else {
                "已保存"
            }
        }
    }

    fun setItemEnabled(item: Item, enabled: Boolean) {
        viewModelScope.launch { repo.setItemEnabled(item, enabled) }
    }

    fun moveItem(item: Item, up: Boolean) {
        viewModelScope.launch { repo.moveItem(item, up) }
    }

    fun deleteItem(item: Item) {
        viewModelScope.launch {
            _message.value = when (repo.deleteItemDef(item)) {
                DeleteItemResult.DELETED -> "已删除"
                DeleteItemResult.COMPLAINT -> "投诉项不能删除，可以停用"
                DeleteItemResult.REFERENCED -> "该项有历史记录，只能停用"
            }
        }
    }
}

/** 导入流程的对话框状态。 */
sealed class ImportDialog {
    data class Failed(val reason: String) : ImportDialog()
    data class Confirm(val plan: ImportPlan, val message: String) : ImportDialog()
}
