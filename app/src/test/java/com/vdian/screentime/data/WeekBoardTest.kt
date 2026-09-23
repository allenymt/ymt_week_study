package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WeekBoardTest {

    private val cfg = RuleConfig()
    private val weekStart = 100L

    @Test
    fun board_always_has_seven_days_even_when_database_is_empty() {
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = emptyList(),
            exams = emptyList(),
            weekEntity = null,
            prevEntity = null,
            cfg = cfg
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
            prevEntity = null,
            cfg = cfg
        )
        assertEquals(setOf(Item.RAISED), board.days[2].items)
        assertEquals(emptySet<Item>(), board.days[0].items)
    }

    @Test
    fun board_without_week_entity_uses_zero_meta() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, null, cfg
        )
        assertEquals(0, board.meta.usedMinutes)
        assertEquals(0, board.prevBankMinutes)
        assertEquals(0, board.prevSavingCents)
    }

    @Test
    fun board_reads_previous_week_totals_from_prev_entity() {
        val prev = WeekEntity(weekStart = 93L, prevBankMinutes = 120, prevSavingCents = 600)
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, prev, cfg
        )
        // prevBankMinutes 语义是该周结算后的银行余额
        assertEquals(120, board.prevBankMinutes)
        assertEquals(600, board.prevSavingCents)
    }

    @Test
    fun summary_and_bank_are_derived_and_non_null() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, null, cfg
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
            weekStart, emptyList(), emptyList(), entity, null, cfg
        )
        assertEquals(45, board.meta.usedMinutes)
        assertEquals(5, board.meta.overtimeMinutes)
        assertEquals(30, board.meta.redeemedMinutes)
    }
}
