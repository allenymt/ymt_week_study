package com.vdian.screentime.data

import com.vdian.screentime.data.Mappers.toDomain
import com.vdian.screentime.data.Mappers.toEntity
import com.vdian.screentime.data.entity.ItemDefEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDefSeedTest {

    private fun byKey(rows: List<ItemDefEntity>) = rows.associateBy { it.key }

    /** 方案 3.2 的完整初始表：key -> (名称, 分值, 孩子可申报, 互斥组, 投诉规则, 顺序)。 */
    private val designTable = listOf(
        listOf("RAISED", "举手回答", 10, true, null, false),
        listOf("NEAT_WRITING", "字认真", 10, true, "writing", false),
        listOf("NEAT_WRITING_BAD", "字不认真", -10, false, "writing", false),
        listOf("ENGLISH", "主动英语", 10, true, null, false),
        listOf("LISTENING_PERFECT", "英语听力全对", 10, true, null, false),
        listOf("READING", "主动阅读", 15, true, null, false),
        listOf("EXERCISE", "主动运动", 15, true, null, false),
        listOf("HOMEWORK_ACTIVE", "作业积极", 10, true, "homework", false),
        listOf("HOMEWORK_PASSIVE", "作业不积极", -10, false, "homework", false),
        listOf("PRAISE", "老师表扬", 10, true, null, false),
        listOf("COMPLAINT", "投诉", -30, false, null, true)
    )

    @Test
    fun seed_without_legacy_matches_design_table_exactly() {
        val rows = ItemDefSeed.entities(emptyMap())
        assertEquals(11, rows.size)
        assertEquals(designTable.map { it[0] }, rows.sortedBy { it.sortOrder }.map { it.key })
        designTable.forEachIndexed { i, row ->
            val e = byKey(rows).getValue(row[0] as String)
            assertEquals(row[1], e.name)
            assertEquals("points of ${e.key}", row[2], e.points)
            assertEquals("childClaimable of ${e.key}", row[3], e.childClaimable)
            assertEquals("group of ${e.key}", row[4], e.exclusiveGroup)
            assertEquals("isComplaint of ${e.key}", row[5], e.isComplaint)
            assertTrue("seed items are enabled", e.enabled)
            assertEquals(i, e.sortOrder)
        }
        assertEquals(rows.size, rows.map { it.sortOrder }.toSet().size)
    }

    @Test
    fun seed_keys_cover_every_key_used_by_historical_records() {
        val keys = ItemDefSeed.entities(emptyMap()).map { it.key }.toSet()
        for (k in listOf("RAISED", "NEAT_WRITING", "ENGLISH", "READING", "EXERCISE", "PRAISE", "COMPLAINT")) {
            assertTrue("missing historical key $k", k in keys)
        }
    }

    @Test
    fun partial_legacy_overrides_only_the_matching_items() {
        val rows = byKey(ItemDefSeed.entities(mapOf("readingPoints" to 20, "complaintPenalty" to 40)))
        assertEquals(20, rows.getValue("READING").points)
        assertEquals(-40, rows.getValue("COMPLAINT").points)
        // 其余项保持默认
        assertEquals(10, rows.getValue("RAISED").points)
        assertEquals(15, rows.getValue("EXERCISE").points)
        assertEquals(-10, rows.getValue("NEAT_WRITING_BAD").points)
        assertEquals(10, rows.getValue("LISTENING_PERFECT").points)
    }

    @Test
    fun full_legacy_overrides_all_seven_existing_items() {
        val legacy = mapOf(
            "raisePoints" to 11, "neatWritingPoints" to 12, "englishPoints" to 13,
            "readingPoints" to 14, "exercisePoints" to 16, "praisePoints" to 17,
            "complaintPenalty" to 25
        )
        val rows = byKey(ItemDefSeed.entities(legacy))
        assertEquals(11, rows.getValue("RAISED").points)
        assertEquals(12, rows.getValue("NEAT_WRITING").points)
        assertEquals(13, rows.getValue("ENGLISH").points)
        assertEquals(14, rows.getValue("READING").points)
        assertEquals(16, rows.getValue("EXERCISE").points)
        assertEquals(17, rows.getValue("PRAISE").points)
        assertEquals(-25, rows.getValue("COMPLAINT").points)
        // 新增 4 项不受旧设置影响
        assertEquals(-10, rows.getValue("NEAT_WRITING_BAD").points)
        assertEquals(10, rows.getValue("LISTENING_PERFECT").points)
        assertEquals(10, rows.getValue("HOMEWORK_ACTIVE").points)
        assertEquals(-10, rows.getValue("HOMEWORK_PASSIVE").points)
    }

    @Test
    fun legacy_keys_match_rule_config_codec_names() {
        // 旧分值键名必须与 1.0 版写入 DataStore 的字段名一致，否则读不到用户改过的分值
        assertEquals(
            listOf(
                "raisePoints", "neatWritingPoints", "englishPoints", "readingPoints",
                "exercisePoints", "praisePoints", "complaintPenalty"
            ),
            RuleConfigCodec.LEGACY_POINT_KEYS
        )
    }

    @Test
    fun legacy_points_only_include_keys_actually_stored() {
        val stored = mapOf("raisePoints" to 12, "praisePoints" to 0, "baseMinutes" to 90)
        val legacy = RuleConfigCodec.legacyPoints { stored[it] }
        // 0 是用户存过的有效值，必须保留；非分值键不收集；没存过的键不出现
        assertEquals(mapOf("raisePoints" to 12, "praisePoints" to 0), legacy)
        assertNull(legacy["readingPoints"])
        assertEquals(15, byKey(ItemDefSeed.entities(legacy)).getValue("READING").points)
        assertEquals(0, byKey(ItemDefSeed.entities(legacy)).getValue("PRAISE").points)
    }

    @Test
    fun seeded_catalog_scores_like_legacy_config() {
        // 用户改过的旧分值经种子写入后，计分与 1.0 版的 RuleConfig 完全一致
        val cfg = RuleConfig(raisePoints = 12, readingPoints = 20, complaintPenalty = 50)
        val legacy = RuleConfigCodec.legacyPoints { RuleConfigCodec.encode(cfg)[it] }
        val catalog = ItemCatalog(ItemDefSeed.entities(legacy).map { it.toDomain() })
        assertEquals(12, catalog.points(Item.RAISED))
        assertEquals(20, catalog.points(Item.READING))
        assertEquals(-50, catalog.points(Item.COMPLAINT))
        assertEquals(50, catalog.complaintPenalty())
    }

    @Test
    fun entity_and_domain_roundtrip() {
        val def = ItemDef(
            Item("CUSTOM"), "自定义", -5, 42, enabled = false,
            childClaimable = true, exclusiveGroup = "g", isComplaint = true
        )
        assertEquals(def, def.toEntity().toDomain())
    }

    @Test
    fun references_are_found_in_confirmed_and_pending() {
        val days = listOf(
            DayRecord(1, items = setOf(Item.RAISED)),
            DayRecord(2, items = emptySet(), pending = setOf(Item.LISTENING_PERFECT)),
            DayRecord(3, items = setOf(Item("OLD_CUSTOM")))
        )
        assertTrue(ItemDefSeed.isReferenced(Item.RAISED, days))
        assertTrue(ItemDefSeed.isReferenced(Item.LISTENING_PERFECT, days))
        assertTrue(ItemDefSeed.isReferenced(Item("OLD_CUSTOM"), days))
        assertFalse(ItemDefSeed.isReferenced(Item.HOMEWORK_ACTIVE, days))
        assertFalse(ItemDefSeed.isReferenced(Item.RAISED, emptyList()))
    }

    @Test
    fun move_swaps_with_neighbour_and_renumbers() {
        val rows = ItemDefSeed.entities(emptyMap())
        val moved = byKey(ItemDefSeed.move(rows, Item.ENGLISH, up = true))
        assertEquals(2, moved.getValue("ENGLISH").sortOrder)
        assertEquals(3, moved.getValue("NEAT_WRITING_BAD").sortOrder)
        assertEquals(0, moved.getValue("RAISED").sortOrder)

        val down = byKey(ItemDefSeed.move(rows, Item.RAISED, up = false))
        assertEquals(1, down.getValue("RAISED").sortOrder)
        assertEquals(0, down.getValue("NEAT_WRITING").sortOrder)
    }

    @Test
    fun move_at_boundary_or_unknown_is_a_noop() {
        val rows = ItemDefSeed.entities(emptyMap())
        assertTrue(ItemDefSeed.move(rows, Item.RAISED, up = true).isEmpty())
        assertTrue(ItemDefSeed.move(rows, Item.COMPLAINT, up = false).isEmpty())
        assertTrue(ItemDefSeed.move(rows, Item("NOPE"), up = true).isEmpty())
    }

    @Test
    fun move_repairs_duplicate_sort_orders() {
        val rows = ItemDefSeed.entities(emptyMap()).map { it.copy(sortOrder = 0) }
        val moved = ItemDefSeed.move(rows, Item.RAISED, up = false)
        assertEquals((0 until rows.size).toList(), moved.map { it.sortOrder }.sorted())
    }
}
