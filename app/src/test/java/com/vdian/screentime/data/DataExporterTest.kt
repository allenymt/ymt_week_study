package com.vdian.screentime.data

import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 用 Robolectric 拿 android 的 org.json 实现。
 * 需在 app/build.gradle 加 testImplementation 'org.robolectric:robolectric:4.8.1'
 * 以及 android { testOptions { unitTests.includeAndroidResources = true } }
 */
@RunWith(RobolectricTestRunner::class)
class DataExporterTest {

    @Test
    fun export_contains_child_name_and_config() {
        val json = DataExporter.toJson(
            days = emptyList(),
            weeks = emptyList(),
            exams = emptyList(),
            cfg = RuleConfig(baseMinutes = 90),
            childName = "小明"
        )
        val root = JSONObject(json)
        assertEquals("小明", root.getString("childName"))
        assertEquals(90, root.getJSONObject("config").getInt("baseMinutes"))
    }

    @Test
    fun export_roundtrips_days_and_exams() {
        val json = DataExporter.toJson(
            days = listOf(
                DayRecord(100L, setOf(Item.RAISED, Item.READING), setOf(Item.ENGLISH))
            ),
            weeks = listOf(WeekMeta(usedMinutes = 30)),
            exams = listOf(ExamRecord(Subject.MATH, 95)),
            cfg = RuleConfig(),
            childName = "小明"
        )
        val root = JSONObject(json)
        val day = root.getJSONArray("days").getJSONObject(0)
        assertEquals(100L, day.getLong("epochDay"))
        assertTrue(day.getJSONArray("items").length() == 2)
        assertTrue(day.getJSONArray("pending").length() == 1)
        assertEquals("MATH", root.getJSONArray("exams").getJSONObject(0).getString("subject"))
        assertEquals(95, root.getJSONArray("exams").getJSONObject(0).getInt("score"))
    }

    @Test
    fun empty_export_is_still_valid_json() {
        val json = DataExporter.toJson(emptyList(), emptyList(), emptyList(), RuleConfig(), "")
        val root = JSONObject(json)
        assertEquals(0, root.getJSONArray("days").length())
        assertEquals(0, root.getJSONArray("weeks").length())
        assertEquals(0, root.getJSONArray("exams").length())
    }
}
