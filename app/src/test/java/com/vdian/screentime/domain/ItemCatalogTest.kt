package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemCatalogTest {

    private val catalog = ItemCatalog.builtin()

    @Test
    fun builtin_has_eleven_items_in_design_order() {
        assertEquals(
            listOf(
                Item.RAISED, Item.NEAT_WRITING, Item.NEAT_WRITING_BAD, Item.ENGLISH,
                Item.LISTENING_PERFECT, Item.READING, Item.EXERCISE,
                Item.HOMEWORK_ACTIVE, Item.HOMEWORK_PASSIVE, Item.PRAISE, Item.COMPLAINT
            ),
            catalog.columns().map { it.item }
        )
    }

    @Test
    fun builtin_default_points_match_design() {
        val expected = mapOf(
            Item.RAISED to 10, Item.NEAT_WRITING to 10, Item.NEAT_WRITING_BAD to -10,
            Item.ENGLISH to 10, Item.LISTENING_PERFECT to 10, Item.READING to 15,
            Item.EXERCISE to 15, Item.HOMEWORK_ACTIVE to 10, Item.HOMEWORK_PASSIVE to -10,
            Item.PRAISE to 10, Item.COMPLAINT to -30
        )
        expected.forEach { (item, points) -> assertEquals(item.key, points, catalog.points(item)) }
    }

    @Test
    fun builtin_flags_match_design() {
        val notClaimable = setOf(Item.NEAT_WRITING_BAD, Item.HOMEWORK_PASSIVE, Item.COMPLAINT)
        catalog.defs.forEach { def ->
            assertEquals(def.item.key, def.item !in notClaimable, def.childClaimable)
            assertEquals(def.item.key, def.item == Item.COMPLAINT, def.isComplaint)
            assertTrue(def.enabled)
        }
        assertEquals("writing", catalog.groupOf(Item.NEAT_WRITING))
        assertEquals("writing", catalog.groupOf(Item.NEAT_WRITING_BAD))
        assertEquals("homework", catalog.groupOf(Item.HOMEWORK_ACTIVE))
        assertEquals("homework", catalog.groupOf(Item.HOMEWORK_PASSIVE))
        assertNull(catalog.groupOf(Item.RAISED))
    }

    @Test
    fun builtin_uses_legacy_points() {
        val c = ItemCatalog.builtin(mapOf("readingPoints" to 20, "complaintPenalty" to 40))
        assertEquals(20, c.points(Item.READING))
        assertEquals(-40, c.points(Item.COMPLAINT))
        assertEquals(40, c.complaintPenalty())
        // 未传入的项保留默认值
        assertEquals(15, c.points(Item.EXERCISE))
    }

    @Test
    fun builtin_maps_every_legacy_field() {
        val c = ItemCatalog.builtin(
            mapOf(
                "raisePoints" to 1, "neatWritingPoints" to 2, "englishPoints" to 3,
                "readingPoints" to 4, "exercisePoints" to 5, "praisePoints" to 6,
                "complaintPenalty" to 7, "baseMinutes" to 999
            )
        )
        assertEquals(1, c.points(Item.RAISED))
        assertEquals(2, c.points(Item.NEAT_WRITING))
        assertEquals(3, c.points(Item.ENGLISH))
        assertEquals(4, c.points(Item.READING))
        assertEquals(5, c.points(Item.EXERCISE))
        assertEquals(6, c.points(Item.PRAISE))
        assertEquals(-7, c.points(Item.COMPLAINT))
    }

    @Test
    fun unknown_item_is_harmless() {
        val unknown = Item("NO_SUCH_ITEM")
        assertEquals(0, catalog.points(unknown))
        assertFalse(catalog.isComplaint(unknown))
        assertNull(catalog.def(unknown))
        assertNull(catalog.groupOf(unknown))
    }

    @Test
    fun columns_skip_disabled_and_sort_by_order() {
        val c = ItemCatalog(
            listOf(
                ItemDef(Item("B"), "b", 1, sortOrder = 2, childClaimable = true),
                ItemDef(Item("A"), "a", 1, sortOrder = 1, childClaimable = true),
                ItemDef(Item.READING, "r", 1, sortOrder = 0, enabled = false, childClaimable = true)
            )
        )
        assertEquals(listOf(Item("A"), Item("B")), c.columns().map { it.item })
        val disabled = ItemCatalog(catalog.defs.map {
            if (it.item == Item.READING) it.copy(enabled = false) else it
        })
        assertFalse(disabled.columns().any { it.item == Item.READING })
        assertEquals(15, disabled.points(Item.READING))
    }

    @Test
    fun complaint_penalty_is_zero_without_complaint_item() {
        val c = ItemCatalog(catalog.defs.filterNot { it.isComplaint })
        assertEquals(0, c.complaintPenalty())
    }

    @Test
    fun item_equality_is_by_key() {
        assertEquals(Item.RAISED, Item("RAISED"))
        assertEquals(1, setOf(Item.RAISED, Item("RAISED")).size)
    }
}
