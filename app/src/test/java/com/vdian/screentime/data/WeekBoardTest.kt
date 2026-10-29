package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WeekBoardTest {

    private val cfg = RuleConfig()
    private val catalog = ItemCatalog.builtin()
    private val weekStart = 100L

    @Test
    fun board_always_has_seven_days_even_when_database_is_empty() {
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = emptyList(),
            exams = emptyList(),
            weekEntity = null,
            fallbackOpening = null,
            cfg = cfg,
            catalog = catalog
        )
        assertEquals(7, board.days.size)
        assertEquals(weekStart, board.days.first().epochDay)
        assertEquals(weekStart + 6, board.days.last().epochDay)
    }

    @Test
    fun missing_days_are_filled_with_empty_records() {
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = listOf(DayRecord(weekStart + 2, setOf(Item.RAISED))),
            exams = emptyList(),
            weekEntity = null,
            fallbackOpening = null,
            cfg = cfg,
            catalog = catalog
        )
        assertEquals(setOf(Item.RAISED), board.days[2].items)
        assertEquals(emptySet<Item>(), board.days[0].items)
    }

    @Test
    fun board_without_week_entity_uses_zero_meta() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, null, cfg, catalog
        )
        assertEquals(0, board.meta.usedMinutes)
        assertEquals(0, board.prevBankMinutes)
        assertEquals(0, board.prevSavingCents)
    }

    @Test
    fun board_opening_comes_from_own_week_entity() {
        // 本周行固化的 prevBankMinutes / prevSavingCents 就是本周期初；fallback 被忽略
        val own = WeekEntity(weekStart = weekStart, prevBankMinutes = 120, prevSavingCents = 600)
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), own, BankOpening(999, 999), cfg, catalog
        )
        assertEquals(120, board.prevBankMinutes)
        assertEquals(600, board.prevSavingCents)
    }

    @Test
    fun board_without_own_row_uses_fallback_opening() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, BankOpening(150, 300), cfg, catalog
        )
        assertEquals(150, board.prevBankMinutes)
        assertEquals(300, board.prevSavingCents)
    }

    @Test
    fun summary_and_bank_are_derived_and_non_null() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, null, cfg, catalog
        )
        assertNotNull(board.summary)
        assertNotNull(board.bank)
        assertEquals(60, board.summary.baseMinutes)
    }

    @Test
    fun week_entity_overrides_stored_meta_fields() {
        val entity = WeekEntity(
            weekStart = weekStart, usedMinutes = 45, overtimeMinutes = 5, redeemedMinutes = 30
        )
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), entity, null, cfg, catalog
        )
        assertEquals(45, board.meta.usedMinutes)
        assertEquals(5, board.meta.overtimeMinutes)
        assertEquals(30, board.meta.redeemedMinutes)
    }

    @Test
    fun summary_uses_the_injected_catalog_not_config_points() {
        // config 里 raisePoints 仍是 10，但目录里改成了 25：必须以目录为准
        val custom = ItemCatalog(catalog.defs.map {
            if (it.item == Item.RAISED) it.copy(points = 25) else it
        })
        val board = WeekBoardAssembler.build(
            weekStart, listOf(DayRecord(weekStart, setOf(Item.RAISED))),
            emptyList(), null, null, cfg, custom
        )
        assertEquals(25, board.summary.dailyTotal)
        assertEquals(custom, board.catalog)
    }
}
