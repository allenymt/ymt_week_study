package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.WeekEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeekDao {

    @Query("SELECT * FROM weeks WHERE weekStart = :weekStart LIMIT 1")
    fun observe(weekStart: Long): Flow<WeekEntity?>

    @Query("SELECT * FROM weeks WHERE weekStart = :weekStart LIMIT 1")
    suspend fun get(weekStart: Long): WeekEntity?

    @Query("SELECT * FROM weeks WHERE weekStart < :weekStart ORDER BY weekStart DESC LIMIT 1")
    suspend fun getPrevious(weekStart: Long): WeekEntity?

    @Query("SELECT * FROM weeks ORDER BY weekStart ASC")
    suspend fun all(): List<WeekEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeekEntity)

    @Query("DELETE FROM weeks")
    suspend fun deleteAll()
}
