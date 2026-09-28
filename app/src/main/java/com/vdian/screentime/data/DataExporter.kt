package com.vdian.screentime.data

import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.WeekMeta
import org.json.JSONArray
import org.json.JSONObject

/** 导出为 JSON 文本。结构固定，手写序列化即可，不引入 JSON 库。 */
object DataExporter {

    fun toJson(
        days: List<DayRecord>,
        weeks: List<WeekMeta>,
        exams: List<ExamRecord>,
        cfg: RuleConfig,
        childName: String
    ): String {
        val root = JSONObject()

        val dayArray = JSONArray()
        days.forEach { d ->
            dayArray.put(JSONObject().apply {
                put("epochDay", d.epochDay)
                put("items", JSONArray(d.items.map { it.name }))
                put("pending", JSONArray(d.pending.map { it.name }))
            })
        }
        root.put("days", dayArray)

        val weekArray = JSONArray()
        weeks.forEach { w ->
            weekArray.put(JSONObject().apply {
                put("usedMinutes", w.usedMinutes)
                put("overtimeMinutes", w.overtimeMinutes)
                put("redeemedMinutes", w.redeemedMinutes)
                put("redeemedCents", w.redeemedCents)
                put("noteGood", w.noteGood)
                put("noteImprove", w.noteImprove)
            })
        }
        root.put("weeks", weekArray)

        val examArray = JSONArray()
        exams.forEach { e ->
            examArray.put(JSONObject().apply {
                put("subject", e.subject.name)
                put("score", e.score)
            })
        }
        root.put("exams", examArray)

        val configObject = JSONObject()
        RuleConfigCodec.encode(cfg).forEach { (k, v) -> configObject.put(k, v) }
        root.put("config", configObject)

        root.put("childName", childName)
        return root.toString(2)
    }
}
