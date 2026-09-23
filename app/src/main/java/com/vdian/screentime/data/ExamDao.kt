package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.domain.Subject
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Query("SELECT * FROM exam_record WHERE weekStart = :weekStart ORDER BY id ASC")
    fun observeWeek(weekStart: Long): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exam_record ORDER BY weekStart ASC, id ASC")
    suspend fun all(): List<ExamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExamEntity)

    @Query("DELETE FROM exam_record WHERE weekStart = :weekStart AND subject = :subject")
    suspend fun delete(weekStart: Long, subject: Subject)

    @Query("DELETE FROM exam_record")
    suspend fun deleteAll()
}
