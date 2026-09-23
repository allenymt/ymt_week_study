package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vdian.screentime.domain.Item

@Entity(tableName = "day_record")
data class DayEntity(
    @PrimaryKey val epochDay: Long,
    val weekStart: Long,
    val items: Set<Item> = emptySet(),
    val pending: Set<Item> = emptySet()
)
