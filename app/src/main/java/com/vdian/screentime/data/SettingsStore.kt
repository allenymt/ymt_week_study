package com.vdian.screentime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vdian.screentime.domain.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** RuleConfig 与键值对的互相转换。纯函数，可单测。 */
object RuleConfigCodec {

    /** 字段名 -> 取值函数。新增字段时必须同步加到这里。 */
    private val fields: List<Pair<String, (RuleConfig) -> Int>> = listOf(
        "raisePoints" to { it.raisePoints },
        "neatWritingPoints" to { it.neatWritingPoints },
        "englishPoints" to { it.englishPoints },
        "readingPoints" to { it.readingPoints },
        "exercisePoints" to { it.exercisePoints },
        "praisePoints" to { it.praisePoints },
        "complaintPenalty" to { it.complaintPenalty },
        "complaintMaxPerWeek" to { it.complaintMaxPerWeek },
        "baseMinutes" to { it.baseMinutes },
        "noComplaintBonusMinutes" to { it.noComplaintBonusMinutes },
        "weekCapMinutes" to { it.weekCapMinutes },
        "bankCapMinutes" to { it.bankCapMinutes },
        "redeemUnitMinutes" to { it.redeemUnitMinutes },
        "redeemUnitCents" to { it.redeemUnitCents },
        "weeklyRedeemCapCents" to { it.weeklyRedeemCapCents },
        "savingCapCents" to { it.savingCapCents },
        "usageStepMinutes" to { it.usageStepMinutes },
        "englishMidMin" to { it.englishMidMin },
        "englishHighMin" to { it.englishHighMin },
        "englishMidBonus" to { it.englishMidBonus },
        "englishHighBonus" to { it.englishHighBonus },
        "otherSubjectMin" to { it.otherSubjectMin },
        "otherSubjectBonus" to { it.otherSubjectBonus }
    )

    /**
     * 1.0 版的 7 个分值键。1.1 起分值存于 item_def 表，这些键只在首次写入种子数据时读取一次。
     * 名称必须与 [com.vdian.screentime.domain.ItemCatalog.builtin] 认的 legacy key 一致。
     */
    val LEGACY_POINT_KEYS: List<String> = listOf(
        "raisePoints", "neatWritingPoints", "englishPoints", "readingPoints",
        "exercisePoints", "praisePoints", "complaintPenalty"
    )

    /** 只收集 [lookup] 能读到的旧分值键；读不到的不放入结果，以便回落到默认值。 */
    fun legacyPoints(lookup: (String) -> Int?): Map<String, Int> =
        LEGACY_POINT_KEYS.mapNotNull { name -> lookup(name)?.let { name to it } }.toMap()

    fun encode(cfg: RuleConfig): Map<String, Int> =
        fields.associate { (key, getter) -> key to getter(cfg) }

    /** 缺失的键回落到 [defaults]，未知的键忽略——保证旧存档可读。 */
    fun decode(map: Map<String, Int>, defaults: RuleConfig): RuleConfig {
        fun v(key: String): Int = map[key] ?: fields
            .firstOrNull { it.first == key }
            ?.let { it.second(defaults) }
            ?: error("unknown key: $key")

        return RuleConfig(
            raisePoints = v("raisePoints"),
            neatWritingPoints = v("neatWritingPoints"),
            englishPoints = v("englishPoints"),
            readingPoints = v("readingPoints"),
            exercisePoints = v("exercisePoints"),
            praisePoints = v("praisePoints"),
            complaintPenalty = v("complaintPenalty"),
            complaintMaxPerWeek = v("complaintMaxPerWeek"),
            baseMinutes = v("baseMinutes"),
            noComplaintBonusMinutes = v("noComplaintBonusMinutes"),
            weekCapMinutes = v("weekCapMinutes"),
            bankCapMinutes = v("bankCapMinutes"),
            redeemUnitMinutes = v("redeemUnitMinutes"),
            redeemUnitCents = v("redeemUnitCents"),
            weeklyRedeemCapCents = v("weeklyRedeemCapCents"),
            savingCapCents = v("savingCapCents"),
            usageStepMinutes = v("usageStepMinutes"),
            englishMidMin = v("englishMidMin"),
            englishHighMin = v("englishHighMin"),
            englishMidBonus = v("englishMidBonus"),
            englishHighBonus = v("englishHighBonus"),
            otherSubjectMin = v("otherSubjectMin"),
            otherSubjectBonus = v("otherSubjectBonus")
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private val childNameKey = stringPreferencesKey("child_name")

    private fun configKey(name: String) = intPreferencesKey("rule_$name")

    fun observeConfig(): Flow<RuleConfig> = context.dataStore.data.map { prefs ->
        val map = RuleConfigCodec.encode(RuleConfig()).keys
            .mapNotNull { name -> prefs[configKey(name)]?.let { name to it } }
            .toMap()
        RuleConfigCodec.decode(map, RuleConfig())
    }

    suspend fun saveConfig(cfg: RuleConfig) {
        context.dataStore.edit { prefs ->
            RuleConfigCodec.encode(cfg).forEach { (name, value) ->
                prefs[configKey(name)] = value
            }
        }
    }

    /**
     * 用户 DataStore 中实际存过的旧分值（见 [RuleConfigCodec.LEGACY_POINT_KEYS]）。
     * 没存过的键不出现在结果里。仅用于首次写入 item_def 种子数据。
     */
    suspend fun readLegacyPoints(): Map<String, Int> {
        val prefs = context.dataStore.data.first()
        return RuleConfigCodec.legacyPoints { name -> prefs[configKey(name)] }
    }

    fun observeChildName(): Flow<String> =
        context.dataStore.data.map { it[childNameKey].orEmpty() }

    suspend fun saveChildName(name: String) {
        context.dataStore.edit { it[childNameKey] = name }
    }
}
