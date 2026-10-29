package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.DayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {

    @Query("SELECT * FROM day_record WHERE weekStart = :weekStart ORDER BY epochDay ASC")
    fun observeWeek(weekStart: Long): Flow<List<DayEntity>>

    @Query("SELECT * FROM day_record WHERE weekStart = :weekStart ORDER BY epochDay ASC")
    suspend fun getWeek(weekStart: Long): List<DayEntity>

    @Query("SELECT * FROM day_record WHERE epochDay = :epochDay LIMIT 1")
    suspend fun getByDay(epochDay: Long): DayEntity?

    @Query("SELECT * FROM day_record ORDER BY epochDay ASC")
    suspend fun all(): List<DayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DayEntity)

    /** 导入用：表已清空，冲突即说明数据有误，直接失败（默认 ABORT）。 */
    @Insert
    suspend fun insertAll(entities: List<DayEntity>)

    @Query("DELETE FROM day_record")
    suspend fun deleteAll()
}
