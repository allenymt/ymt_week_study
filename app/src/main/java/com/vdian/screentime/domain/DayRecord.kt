package com.vdian.screentime.domain

/**
 * 一天的记录。
 * @param items   家长已确认的项，只有这些计分
 * @param pending 孩子申报、待家长确认的项，不计分
 */
data class DayRecord(
    val epochDay: Long,
    val items: Set<Item> = emptySet(),
    val pending: Set<Item> = emptySet()
)
