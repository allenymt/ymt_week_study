package com.vdian.screentime.domain

/**
 * 日期工具,全部为纯函数。刻意不使用 java.time —— minSdk 21 上 API 26 以下不可用。
 * epochDay 约定:1970-01-01 为 0,与 java.time.LocalDate.toEpochDay() 一致。
 */
object WeekDates {

    fun toEpochDay(year: Int, month: Int, day: Int): Long {
        // Howard Hinnant, days_from_civil
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                    // [0, 399]
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy            // [0, 146096]
        return era.toLong() * 146097L + doe.toLong() - 719468L
    }

    /** 返回所属周的周一。epochDay 0 (1970-01-01) 是周四,故基准偏移为 3。 */
    fun weekStart(epochDay: Long): Long {
        val dow = dayOfWeek(epochDay)
        return epochDay - dow
    }

    /** 0 = 周一 … 6 = 周日。 */
    fun dayOfWeek(epochDay: Long): Int {
        // epochDay 0 是周四 = 索引 3
        val shifted = (epochDay + 3) % 7
        return (if (shifted < 0) shifted + 7 else shifted).toInt()
    }

    /** 格式化为 "9月21日",用于界面展示。 */
    fun format(epochDay: Long): String {
        val (_, m, d) = civilFromDays(epochDay)
        return "${m}月${d}日"
    }

    private fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
        var z = epochDay + 719468L
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097                                        // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365   // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)                 // [0, 365]
        val mp = (5 * doy + 2) / 153                                      // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
