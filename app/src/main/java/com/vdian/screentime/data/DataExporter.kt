package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.ItemDefEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.RuleConfig
import org.json.JSONArray
import org.json.JSONObject

/** 导出为 JSON 文本（格式 v2，见方案第 4 节）。直接序列化实体，保证可完整还原。 */
object DataExporter {

    const val SCHEMA_VERSION = 2

    fun toJson(
        days: List<DayEntity>,
        weeks: List<WeekEntity>,
        exams: List<ExamEntity>,
        itemDefs: List<ItemDefEntity>,
        cfg: RuleConfig,
        childName: String
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("childName", childName)

        val configObject = JSONObject()
        RuleConfigCodec.encode(cfg).forEach { (k, v) -> configObject.put(k, v) }
        root.put("config", configObject)

        val defArray = JSONArray()
        itemDefs.forEach { d ->
            defArray.put(JSONObject().apply {
                put("key", d.key)
                put("name", d.name)
                put("points", d.points)
                put("sortOrder", d.sortOrder)
                put("enabled", d.enabled)
                put("childClaimable", d.childClaimable)
                put("exclusiveGroup", d.exclusiveGroup ?: JSONObject.NULL)
                put("isComplaint", d.isComplaint)
            })
        }
        root.put("itemDefs", defArray)

        val dayArray = JSONArray()
        days.forEach { d ->
            dayArray.put(JSONObject().apply {
                put("epochDay", d.epochDay)
                put("items", JSONArray(d.items.map { it.key }))
                put("pending", JSONArray(d.pending.map { it.key }))
            })
        }
        root.put("days", dayArray)

        val weekArray = JSONArray()
        weeks.forEach { w ->
            weekArray.put(JSONObject().apply {
                put("weekStart", w.weekStart)
                put("usedMinutes", w.usedMinutes)
                put("overtimeMinutes", w.overtimeMinutes)
                put("redeemedMinutes", w.redeemedMinutes)
                put("redeemedCents", w.redeemedCents)
                put("savingAdjustCents", w.savingAdjustCents)
                put("prevBankMinutes", w.prevBankMinutes)
                put("prevSavingCents", w.prevSavingCents)
                put("noteGood", w.noteGood)
                put("noteImprove", w.noteImprove)
            })
        }
        root.put("weeks", weekArray)

        val examArray = JSONArray()
        exams.forEach { e ->
            examArray.put(JSONObject().apply {
                put("weekStart", e.weekStart)
                put("subject", e.subject.name)
                put("score", e.score)
            })
        }
        root.put("exams", examArray)

        return root.toString(2)
    }
}
