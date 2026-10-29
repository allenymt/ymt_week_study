package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vdian.screentime.data.entity.ItemDefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDefDao {

    @Query("SELECT * FROM item_def ORDER BY sortOrder ASC, `key` ASC")
    fun observeAll(): Flow<List<ItemDefEntity>>

    @Query("SELECT * FROM item_def ORDER BY sortOrder ASC, `key` ASC")
    suspend fun all(): List<ItemDefEntity>

    @Query("SELECT COUNT(*) FROM item_def")
    suspend fun count(): Int

    /** 种子写入：已存在的 key 一律忽略，绝不覆盖用户改过的定义。整批在一个事务内。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entities: List<ItemDefEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemDefEntity)

    /** 批量更新，整批在一个事务内（用于调整顺序）。 */
    @Update
    suspend fun updateAll(entities: List<ItemDefEntity>)

    @Query("UPDATE item_def SET enabled = :enabled WHERE `key` = :key")
    suspend fun setEnabled(key: String, enabled: Boolean)

    @Query("DELETE FROM item_def WHERE `key` = :key")
    suspend fun delete(key: String)
}
