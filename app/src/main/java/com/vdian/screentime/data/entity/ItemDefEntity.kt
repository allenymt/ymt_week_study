package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 考核项定义表（方案 3.1）。`key` 即日记录 items / pending 中存的字符串，一旦写入不可更改。
 *
 * 注意：Kotlin 默认值不会变成 SQL DEFAULT，迁移里的建表语句与此处字段必须逐列一致。
 */
@Entity(tableName = "item_def")
data class ItemDefEntity(
    @PrimaryKey val key: String,
    val name: String,
    val points: Int,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val childClaimable: Boolean,
    val exclusiveGroup: String? = null,
    val isComplaint: Boolean = false
)
