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
        "englishMidMin" to { it.englishMidMin },
        "englishHighMin" to { it.englishHighMin },
        "englishMidBonus" to { it.englishMidBonus },
        "englishHighBonus" to { it.englishHighBonus },
        "otherSubjectMin" to { it.otherSubjectMin },
        "otherSubjectBonus" to { it.otherSubjectBonus }
    )

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

    fun observeChildName(): Flow<String> =
        context.dataStore.data.map { it[childNameKey].orEmpty() }

    suspend fun saveChildName(name: String) {
        context.dataStore.edit { it[childNameKey] = name }
    }
}
