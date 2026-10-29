package com.vdian.screentime.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdian.screentime.data.DataExporter
import com.vdian.screentime.data.RuleConfigCodec
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.SettingsStore
import com.vdian.screentime.domain.RuleConfig
import kotlinx.coroutines.Dispatchers
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

    init {
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
                    days = repo.allDays(),
                    weeks = repo.allWeeks(),
                    exams = repo.allExams(),
                    cfg = repo.currentConfig(),
                    childName = repo.childName()
                )
            }
            _exportJson.value = json
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repo.clearAllData()
            _message.value = "已清空所有记录"
        }
    }
}
