package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.ItemDefEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.ScoreRules
import com.vdian.screentime.domain.Subject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 用 Robolectric 拿 android 的 org.json 实现。 */
@RunWith(RobolectricTestRunner::class)
class DataImporterTest {

    private val v1Text: String by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("screen-time-v1.json")
            ?: error("missing test resource screen-time-v1.json")
        stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun ok(json: String): ImportPlan = when (val r = DataImporter.parse(json)) {
        is ImportResult.Ok -> r.plan
        is ImportResult.Error -> { fail("expected Ok, got Error: ${r.reason}"); error("unreachable") }
    }

    private fun err(json: String): String = when (val r = DataImporter.parse(json)) {
        is ImportResult.Ok -> { fail("expected Error, got Ok"); error("unreachable") }
        is ImportResult.Error -> r.reason
    }

    private fun v1Plan() = ok(v1Text)

    private fun boards(plan: ImportPlan, catalog: ItemCatalog = ItemCatalog(plan.itemDefs)): List<WeekBoard> =
        plan.weeks.map { w ->
            WeekBoardAssembler.build(
                weekStart = w.weekStart,
                days = plan.days.filter { it.weekStart == w.weekStart }.map { Mappers.run { it.toDomain() } },
                exams = plan.exams.filter { it.weekStart == w.weekStart }.map { Mappers.run { it.toDomain() } },
                weekEntity = w,
                fallbackOpening = null,
                cfg = plan.config,
                catalog = catalog
            )
        }

    // ---- v1 资源文件 -----------------------------------------------------------

    @Test
    fun v1_resource_parses_with_expected_counts_and_weeks() {
        val plan = v1Plan()
        assertEquals(1, plan.schemaVersion)
        assertEquals("测试", plan.childName)
        assertEquals(24, plan.days.size)
        assertEquals(4, plan.weeks.size)
        assertEquals(0, plan.exams.size)
        assertEquals(listOf(20731L, 20738L, 20745L, 20752L), plan.weeks.map { it.weekStart })
        val first = plan.weeks[0]
        assertEquals(180, first.overtimeMinutes)
        assertEquals("作业积极，效率高", first.noteGood)
        assertTrue(plan.weeks.all { it.savingAdjustCents == 0 })
        assertTrue(plan.warnings.isNotEmpty())
    }

    @Test
    fun v1_resource_config_keeps_file_values_and_fills_defaults() {
        val cfg = v1Plan().config
        assertEquals(5000, cfg.savingCapCents)
        assertEquals(15, cfg.usageStepMinutes)
        assertEquals(30, cfg.baseMinutes)
        assertEquals(960, cfg.bankCapMinutes)
    }

    @Test
    fun v1_resource_item_defs_are_builtin_with_file_points() {
        val defs = v1Plan().itemDefs
        assertEquals(11, defs.size)
        val catalog = ItemCatalog(defs)
        assertEquals(20, catalog.points(Item.RAISED))
        assertEquals(10, catalog.points(Item.READING))
        assertEquals(15, catalog.points(Item.EXERCISE))
        assertEquals(-30, catalog.points(Item.COMPLAINT))
        assertEquals(-10, catalog.points(Item.NEAT_WRITING_BAD))
    }

    @Test
    fun v1_resource_bank_chain_is_replayed() {
        val boards = boards(v1Plan())
        assertEquals(0, boards[0].prevBankMinutes)
        assertEquals(0, boards[0].prevSavingCents)
        for (i in 1 until boards.size) {
            assertEquals(boards[i - 1].bank.bankBalanceMinutes, boards[i].prevBankMinutes)
            assertEquals(boards[i - 1].bank.savingCents, boards[i].prevSavingCents)
        }
    }

    /** 4 周的每日小计之和：导入不改动任何一天的勾选。 */
    @Test
    fun v1_resource_weekly_daily_score_sums() {
        val plan = v1Plan()
        fun sums(catalog: ItemCatalog) = plan.weeks.map { w ->
            val days = plan.days.filter { it.weekStart == w.weekStart }.map { Mappers.run { it.toDomain() } }
            ScoreRules.dailyScores(days, plan.config, catalog).sum()
        }
        // 默认目录（举手 10、阅读 15）
        assertEquals(listOf(85, 205, 280, 170), sums(ItemCatalog.builtin()))
        // 文件里的分值（举手 20、阅读 10），即导入后实际生效的目录
        assertEquals(listOf(75, 185, 310, 155), sums(ItemCatalog(plan.itemDefs)))
        assertEquals(listOf(75, 185, 310, 155), boards(plan).map { it.summary.dailyTotal })
    }

    @Test
    fun v1_week_count_mismatch_is_error() {
        val root = JSONObject(v1Text)
        root.getJSONArray("weeks").remove(3)
        err(root.toString())
    }

    @Test
    fun v1_with_exams_is_error() {
        val root = JSONObject(v1Text)
        root.put("exams", JSONArray().put(JSONObject().put("subject", "MATH").put("score", 95)))
        err(root.toString())
    }

    @Test
    fun invalid_json_is_error() {
        err("{not json")
        err("")
        err("[1,2,3]")
    }

    @Test
    fun newer_schema_version_is_error() {
        assertTrue(err("""{"schemaVersion":3}""").contains("3"))
    }

    // ---- v2 -----------------------------------------------------------------

    private val ws1 = 20731L
    private val ws2 = 20738L
    private val cfg = RuleConfig(baseMinutes = 90, savingCapCents = 6000)

    private val defs = listOf(
        ItemDefEntity("RAISED", "举手回答", 10, 0, true, true, null, false),
        ItemDefEntity("CUSTOM_1", "弹琴", 25, 1, true, true, "music", false),
        ItemDefEntity("COMPLAINT", "被投诉", -20, 2, false, false, null, true)
    )

    private val days = listOf(
        DayEntity(ws1, ws1, setOf(Item.RAISED, Item("CUSTOM_1")), emptySet()),
        DayEntity(ws1 + 2, ws1, setOf(Item.COMPLAINT), setOf(Item.RAISED)),
        DayEntity(ws2 + 1, ws2, setOf(Item("CUSTOM_1")), emptySet())
    )

    private val exams = listOf(
        ExamEntity(7, ws1, Subject.MATH, 95),
        ExamEntity(8, ws2, Subject.ENGLISH, 88)
    )

    private fun consistentWeeks(): List<WeekEntity> = BankCarry.replay(
        weeks = listOf(
            WeekEntity(ws1, usedMinutes = 30, overtimeMinutes = 5, redeemedMinutes = 30, redeemedCents = 200,
                savingAdjustCents = 100, noteGood = "好", noteImprove = "改"),
            WeekEntity(ws2, usedMinutes = 60)
        ),
        daysByWeek = days.groupBy({ it.weekStart }, { Mappers.run { it.toDomain() } }),
        examsByWeek = exams.groupBy({ it.weekStart }, { Mappers.run { it.toDomain() } }),
        cfg = cfg,
        catalog = ItemCatalog(defs.map { Mappers.run { it.toDomain() } })
    )

    private fun v2Json(weeks: List<WeekEntity> = consistentWeeks()) =
        DataExporter.toJson(days, weeks, exams, defs, cfg, "小明")

    @Test
    fun v2_roundtrip_restores_every_field() {
        val weeks = consistentWeeks()
        val plan = ok(v2Json(weeks))
        assertEquals(2, plan.schemaVersion)
        assertEquals("小明", plan.childName)
        assertEquals(cfg, plan.config)
        assertEquals(defs, plan.itemDefs.map { Mappers.run { it.toEntity() } })
        assertEquals(days, plan.days)
        assertEquals(weeks, plan.weeks)
        assertEquals(exams.map { it.copy(id = 0) }, plan.exams)
        assertTrue(plan.warnings.toString(), plan.warnings.isEmpty())
        assertTrue(weeks[1].prevBankMinutes != 0)
    }

    @Test
    fun v2_stale_bank_cache_is_recomputed_with_warning() {
        val good = consistentWeeks()
        val stale = good.map { it.copy(prevBankMinutes = 999, prevSavingCents = 999) }
        val plan = ok(v2Json(stale))
        assertEquals(good, plan.weeks)
        assertTrue(plan.warnings.any { it.contains("重算") })
    }

    @Test
    fun v2_days_without_week_row_get_blank_week() {
        val plan = ok(v2Json(consistentWeeks().take(1)))
        assertEquals(listOf(ws1, ws2), plan.weeks.map { it.weekStart })
        assertEquals(0, plan.weeks[1].usedMinutes)
        assertTrue(plan.warnings.any { it.contains("补上") })
    }

    @Test
    fun v2_unknown_subject_is_error() {
        val root = JSONObject(v2Json())
        root.getJSONArray("exams").getJSONObject(0).put("subject", "PHYSICS")
        assertTrue(err(root.toString()).contains("PHYSICS"))
    }

    @Test
    fun v2_empty_item_defs_is_error() {
        val root = JSONObject(v2Json())
        root.put("itemDefs", JSONArray())
        err(root.toString())
        root.remove("itemDefs")
        err(root.toString())
    }

    @Test
    fun v2_malformed_fields_are_errors() {
        fun mutate(block: (JSONObject) -> Unit): String {
            val root = JSONObject(v2Json())
            block(root)
            return root.toString()
        }
        err(mutate { it.getJSONArray("days").getJSONObject(0).put("epochDay", "20731") })
        err(mutate { it.getJSONArray("days").getJSONObject(0).put("items", "RAISED") })
        err(mutate { it.getJSONArray("days").getJSONObject(0).put("items", JSONArray().put("A,B")) })
        err(mutate { it.getJSONArray("days").getJSONObject(1).put("epochDay", ws1) })      // 重复日
        err(mutate { it.getJSONArray("weeks").getJSONObject(0).remove("weekStart") })
        err(mutate { it.getJSONArray("weeks").getJSONObject(0).put("weekStart", ws1 + 1) }) // 不是周一
        err(mutate { it.getJSONArray("weeks").getJSONObject(0).put("usedMinutes", 1.5) })
        err(mutate { it.getJSONArray("exams").getJSONObject(0).remove("weekStart") })
        err(mutate { it.getJSONArray("exams").getJSONObject(0).put("score", "95") })
        err(mutate { it.getJSONArray("itemDefs").getJSONObject(1).put("key", "RAISED") }) // key 重复
        err(mutate { it.getJSONArray("itemDefs").getJSONObject(0).remove("points") })
        err(mutate { it.getJSONArray("itemDefs").getJSONObject(0).put("childClaimable", "yes") })
        err(mutate { it.getJSONObject("config").put("baseMinutes", "60") })
        err(mutate { it.put("days", JSONObject()) })
    }

    @Test
    fun v2_unknown_item_keys_in_days_are_kept() {
        val root = JSONObject(v2Json())
        root.getJSONArray("days").getJSONObject(0).put("items", JSONArray().put("GONE"))
        val plan = ok(root.toString())
        assertEquals(setOf(Item("GONE")), plan.days[0].items)
        assertTrue(plan.warnings.any { it.contains("GONE") })
    }

    // ---- 确认框文案 ---------------------------------------------------------------

    @Test
    fun saving_cap_change_is_warned() {
        val plan = v1Plan()
        assertEquals(
            listOf("存钱上限将改为 50 元（当前 100 元）"),
            DataImporter.diffWarnings(plan, RuleConfig())
        )
        assertTrue(DataImporter.diffWarnings(plan, RuleConfig(savingCapCents = 5000)).isEmpty())
        val msg = DataImporter.confirmMessage(plan, RuleConfig())
        assertTrue(msg.contains("将覆盖全部现有数据和规则参数"))
        assertTrue(msg.contains("测试"))
        assertTrue(msg.contains("24 天"))
        assertEquals("50.05", DataImporter.yuan(5005))
    }
}
