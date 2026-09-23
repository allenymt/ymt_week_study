package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vdian.screentime.domain.Subject

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,
    val subject: Subject,
    val score: Int
)
