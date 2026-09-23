package com.vdian.screentime.domain

/** 一周的使用与兑换事实。这些是实际发生的数据，不是推导出来的，所以必须存。 */
data class WeekMeta(
    val usedMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    val redeemedMinutes: Int = 0,
    val redeemedCents: Int = 0,
    val noteGood: String = "",
    val noteImprove: String = ""
)
