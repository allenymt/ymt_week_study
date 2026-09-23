package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.WeekMeta

/** Room 实体与 Domain 模型之间的双向映射。纯函数，无 Android 依赖。 */
object Mappers {

    fun DayEntity.toDomain(): DayRecord = DayRecord(
        epochDay = epochDay,
        items = items,
        pending = pending
    )

    fun DayRecord.toEntity(weekStart: Long): DayEntity = DayEntity(
        epochDay = epochDay,
        weekStart = weekStart,
        items = items,
        pending = pending
    )

    fun WeekEntity.toDomain(): WeekMeta = WeekMeta(
        usedMinutes = usedMinutes,
        overtimeMinutes = overtimeMinutes,
        redeemedMinutes = redeemedMinutes,
        redeemedCents = redeemedCents,
        noteGood = noteGood,
        noteImprove = noteImprove
    )

    fun WeekMeta.toEntity(
        weekStart: Long,
        prevBankMinutes: Int,
        prevSavingCents: Int
    ): WeekEntity = WeekEntity(
        weekStart = weekStart,
        usedMinutes = usedMinutes,
        overtimeMinutes = overtimeMinutes,
        redeemedMinutes = redeemedMinutes,
        redeemedCents = redeemedCents,
        prevBankMinutes = prevBankMinutes,
        prevSavingCents = prevSavingCents,
        noteGood = noteGood,
        noteImprove = noteImprove
    )

    fun ExamEntity.toDomain(): ExamRecord = ExamRecord(subject = subject, score = score)
}
