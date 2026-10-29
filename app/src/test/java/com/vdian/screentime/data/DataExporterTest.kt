package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.ItemDefEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 用 Robolectric 拿 android 的 org.json 实现。 */
@RunWith(RobolectricTestRunner::class)
class DataExporterTest {

    private fun export(
        days: List<DayEntity> = emptyList(),
        weeks: List<WeekEntity> = emptyList(),
        exams: List<ExamEntity> = emptyList(),
        defs: List<ItemDefEntity> = emptyList(),
        cfg: RuleConfig = RuleConfig(),
        name: String = "小明"
    ) = DataExporter.toJson(days, weeks, exams, defs, cfg, name)

    private val fixture: String by lazy {
        export(
            days = listOf(DayEntity(100L, 98L, setOf(Item.RAISED, Item.READING), setOf(Item.ENGLISH))),
            weeks = listOf(
                WeekEntity(
                    weekStart = 98L, usedMinutes = 30, overtimeMinutes = 5, redeemedMinutes = 10,
                    redeemedCents = 200, prevBankMinutes = 40, prevSavingCents = 350,
                    savingAdjustCents = -50, noteGood = "好", noteImprove = "改"
                )
            ),
            exams = listOf(ExamEntity(1, 98L, Subject.MATH, 95)),
            defs = listOf(
                ItemDefEntity("RAISED", "举手回答", 10, 0, true, true, null, false),
                ItemDefEntity("COMPLAINT", "被投诉", -20, 1, false, false, "G", true)
            ),
            cfg = RuleConfig(baseMinutes = 90)
        )
    }

    @Test
    fun top_level_has_schema_version_and_item_defs() {
        val root = JSONObject(fixture)
        assertEquals(2, root.getInt("schemaVersion"))
        assertEquals("小明", root.getString("childName"))
        assertEquals(90, root.getJSONObject("config").getInt("baseMinutes"))
        val defs = root.getJSONArray("itemDefs")
        assertEquals(2, defs.length())
        val first = defs.getJSONObject(0)
        assertTrue(first.has("exclusiveGroup"))
        assertEquals(JSONObject.NULL, first.get("exclusiveGroup"))
        val c = defs.getJSONObject(1)
        assertEquals("COMPLAINT", c.getString("key"))
        assertEquals(false, c.getBoolean("enabled"))
        assertEquals("G", c.getString("exclusiveGroup"))
        assertEquals(true, c.getBoolean("isComplaint"))
        assertEquals(-20, c.getInt("points"))
        assertEquals(1, c.getInt("sortOrder"))
        assertEquals(false, c.getBoolean("childClaimable"))
    }

    @Test
    fun week_has_all_fields() {
        val w = JSONObject(fixture).getJSONArray("weeks").getJSONObject(0)
        assertEquals(98L, w.getLong("weekStart"))
        assertEquals(30, w.getInt("usedMinutes"))
        assertEquals(5, w.getInt("overtimeMinutes"))
        assertEquals(10, w.getInt("redeemedMinutes"))
        assertEquals(200, w.getInt("redeemedCents"))
        assertEquals(-50, w.getInt("savingAdjustCents"))
        assertEquals(40, w.getInt("prevBankMinutes"))
        assertEquals(350, w.getInt("prevSavingCents"))
        assertEquals("好", w.getString("noteGood"))
        assertEquals("改", w.getString("noteImprove"))
    }

    @Test
    fun exam_has_week_start_and_days_roundtrip() {
        val root = JSONObject(fixture)
        val e = root.getJSONArray("exams").getJSONObject(0)
        assertEquals(98L, e.getLong("weekStart"))
        assertEquals("MATH", e.getString("subject"))
        assertEquals(95, e.getInt("score"))
        val day = root.getJSONArray("days").getJSONObject(0)
        assertEquals(100L, day.getLong("epochDay"))
        assertEquals(2, day.getJSONArray("items").length())
        assertEquals("ENGLISH", day.getJSONArray("pending").getString(0))
    }

    @Test
    fun empty_export_is_still_valid_json() {
        val root = JSONObject(export(name = ""))
        assertEquals(0, root.getJSONArray("days").length())
        assertEquals(0, root.getJSONArray("weeks").length())
        assertEquals(0, root.getJSONArray("exams").length())
        assertEquals(0, root.getJSONArray("itemDefs").length())
    }
}
