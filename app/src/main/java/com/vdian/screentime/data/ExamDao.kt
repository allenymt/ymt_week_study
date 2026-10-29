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

    @Query("SELECT * FROM exam_record WHERE weekStart = :weekStart ORDER BY id ASC")
    suspend fun getWeek(weekStart: Long): List<ExamEntity>

    @Query("SELECT * FROM exam_record ORDER BY weekStart ASC, id ASC")
    suspend fun all(): List<ExamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExamEntity)

    @Query("DELETE FROM exam_record WHERE weekStart = :weekStart AND subject = :subject")
    suspend fun delete(weekStart: Long, subject: Subject)

    /**
     * 每个 (weekStart, subject) 只保留 id 最大的一行，删掉其余重复行。启动校准时调用，
     * 清理 1.1 之前 setExam 每次新插一行留下的重复成绩。id 自增，最大即最后写入。
     */
    @Query("DELETE FROM exam_record WHERE id NOT IN (SELECT MAX(id) FROM exam_record GROUP BY weekStart, subject)")
    suspend fun dedupe()

    /** 导入用：id 为 0，由 Room 重新生成。 */
    @Insert
    suspend fun insertAll(entities: List<ExamEntity>)

    @Query("DELETE FROM exam_record")
    suspend fun deleteAll()
}
