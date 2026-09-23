package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一周的使用与兑换事实。
 * @param prevBankMinutes     上周银行结余（冗余存储，避免回溯计算全部历史）
 * @param prevSavingCents     上周存钱累计（同上）
 */
@Entity(tableName = "weeks")
data class WeekEntity(
    @PrimaryKey val weekStart: Long,
    val usedMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    val redeemedMinutes: Int = 0,
    val redeemedCents: Int = 0,
    val prevBankMinutes: Int = 0,
    val prevSavingCents: Int = 0,
    val noteGood: String = "",
    val noteImprove: String = ""
)
