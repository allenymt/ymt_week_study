package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.DayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {

    @Query("SELECT * FROM days WHERE weekStart = :weekStart ORDER BY epochDay ASC")
    fun observeWeek(weekStart: Long): Flow<List<DayEntity>>

    @Query("SELECT * FROM days WHERE epochDay = :epochDay LIMIT 1")
    suspend fun getByDay(epochDay: Long): DayEntity?

    @Query("SELECT * FROM days ORDER BY epochDay ASC")
    suspend fun all(): List<DayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DayEntity)

    @Query("DELETE FROM days")
    suspend fun deleteAll()
}
