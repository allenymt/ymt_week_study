package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ItemDefEditorTest {

    private fun add(name: String = "新项", points: String = "5", group: String = "") =
        ItemDefEditor.build(null, Item("CUSTOM_1"), 11, name, points, true, group)

    @Test
    fun key_is_custom_prefixed_timestamp() {
        assertEquals("CUSTOM_1700000000000", ItemDefEditor.newKey(1700000000000L))
    }

    @Test
    fun new_item_is_enabled_last_and_trims_input() {
        val d = add(name = "  画画 ", points = " -5 ", group = "  ").def!!
        assertEquals("画画", d.name)
        assertEquals(-5, d.points)
        assertEquals(11, d.sortOrder)
        assertNull(d.exclusiveGroup)
        assertEquals(true, d.enabled)
        assertEquals(false, d.isComplaint)
    }

    @Test
    fun rejects_blank_name_and_bad_points() {
        assertNotNull(add(name = " ").nameError)
        assertNotNull(add(points = "abc").pointsError)
        assertNotNull(add(points = "").pointsError)
        assertNotNull(add(points = "1001").pointsError)
        assertNull(add(points = "-1000").pointsError)
    }

    @Test
    fun edit_keeps_identity_order_enabled_and_complaint_flag() {
        val base = ItemDef(Item.COMPLAINT, "投诉", -30, 10, enabled = false,
            childClaimable = false, isComplaint = true)
        val d = ItemDefEditor.build(base, null, 99, "投诉2", "-40", false, "g").def!!
        assertEquals(Item.COMPLAINT, d.item)
        assertEquals(10, d.sortOrder)
        assertEquals(false, d.enabled)
        assertEquals(true, d.isComplaint)
        assertEquals(-40, d.points)
        assertEquals("g", d.exclusiveGroup)
    }

    @Test
    fun complaint_item_rejects_zero_and_positive() {
        val base = ItemDef(Item.COMPLAINT, "投诉", -30, 10, childClaimable = false, isComplaint = true)
        assertNotNull(ItemDefEditor.build(base, null, 0, "投诉", "0", false, "").pointsError)
        assertNotNull(ItemDefEditor.build(base, null, 0, "投诉", "10", false, "").pointsError)
        assertNull(ItemDefEditor.build(base, null, 0, "投诉", "-1", false, "").pointsError)
    }

    @Test
    fun sort_order_and_points_changed_and_sign() {
        assertEquals(0, ItemDefEditor.nextSortOrder(emptyList()))
        val a = ItemDef(Item("A"), "a", 10, 3, childClaimable = true)
        assertEquals(4, ItemDefEditor.nextSortOrder(listOf(a)))
        assertEquals(false, ItemDefEditor.pointsChanged(a, a.copy(name = "b")))
        assertEquals(true, ItemDefEditor.pointsChanged(a, a.copy(points = 5)))
        assertEquals(false, ItemDefEditor.pointsChanged(null, a))
        assertEquals("+10", ItemDefEditor.signedPoints(10))
        assertEquals("-10", ItemDefEditor.signedPoints(-10))
        assertEquals("0", ItemDefEditor.signedPoints(0))
    }
}
