package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.WeekEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeekDao {

    @Query("SELECT * FROM week_meta WHERE weekStart = :weekStart LIMIT 1")
    fun observe(weekStart: Long): Flow<WeekEntity?>

    @Query("SELECT * FROM week_meta WHERE weekStart = :weekStart LIMIT 1")
    suspend fun get(weekStart: Long): WeekEntity?

    @Query("SELECT * FROM week_meta WHERE weekStart < :weekStart ORDER BY weekStart DESC LIMIT 1")
    suspend fun getPrevious(weekStart: Long): WeekEntity?

    @Query("SELECT * FROM week_meta ORDER BY weekStart ASC")
    suspend fun all(): List<WeekEntity>

    /** 银行页要按历史累计兑换求和，需要全部周行并随写入实时更新。 */
    @Query("SELECT * FROM week_meta ORDER BY weekStart ASC")
    fun observeAll(): Flow<List<WeekEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeekEntity)

    @Query("DELETE FROM week_meta")
    suspend fun deleteAll()
}
