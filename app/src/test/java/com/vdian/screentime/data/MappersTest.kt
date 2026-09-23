package com.vdian.screentime.data

import com.vdian.screentime.data.Mappers.toDomain
import com.vdian.screentime.data.Mappers.toEntity
import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun day_entity_roundtrips_items_and_pending() {
        val entity = DayEntity(
            epochDay = 100L,
            weekStart = 98L,
            items = setOf(Item.RAISED, Item.READING),
            pending = setOf(Item.ENGLISH)
        )
        val domain = entity.toDomain()
        assertEquals(setOf(Item.RAISED, Item.READING), domain.items)
        assertEquals(setOf(Item.ENGLISH), domain.pending)
        assertEquals(100L, domain.epochDay)

        val back = domain.toEntity(98L)
        assertEquals(entity.epochDay, back.epochDay)
        assertEquals(entity.weekStart, back.weekStart)
        assertEquals(entity.items, back.items)
        assertEquals(entity.pending, back.pending)
    }

    @Test
    fun week_entity_roundtrips_meta_fields() {
        val entity = WeekEntity(
            weekStart = 98L,
            usedMinutes = 30,
            overtimeMinutes = 10,
            redeemedMinutes = 60,
            redeemedCents = 400,
            noteGood = "很好",
            noteImprove = "少涂改"
        )
        val domain = entity.toDomain()
        assertEquals(30, domain.usedMinutes)
        assertEquals(10, domain.overtimeMinutes)
        assertEquals(60, domain.redeemedMinutes)
        assertEquals(400, domain.redeemedCents)
        assertEquals("很好", domain.noteGood)
        assertEquals("少涂改", domain.noteImprove)
    }

    @Test
    fun exam_entity_roundtrips() {
        val e = ExamEntity(id = 1, weekStart = 98L, subject = Subject.ENGLISH, score = 92)
        assertEquals(Subject.ENGLISH, e.toDomain().subject)
        assertEquals(92, e.toDomain().score)
    }
}
