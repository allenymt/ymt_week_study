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

    /** 是否存在比 [weekStart] 更晚的周行——写入第 W 周后据此决定要不要重放银行链。 */
    @Query("SELECT EXISTS(SELECT 1 FROM week_meta WHERE weekStart > :weekStart)")
    suspend fun hasLater(weekStart: Long): Boolean

    /** 银行页要按历史累计兑换求和，需要全部周行并随写入实时更新。 */
    @Query("SELECT * FROM week_meta ORDER BY weekStart ASC")
    fun observeAll(): Flow<List<WeekEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeekEntity)

    /** 批量写回。Room 把列表插入放在同一个事务里，重放结果要么全部落库要么全不落。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WeekEntity>)

    @Query("DELETE FROM week_meta")
    suspend fun deleteAll()
}
