package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.ItemDefEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.ItemDef
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
        savingAdjustCents = savingAdjustCents,
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
        savingAdjustCents = savingAdjustCents,
        noteGood = noteGood,
        noteImprove = noteImprove
    )

    fun ExamEntity.toDomain(): ExamRecord = ExamRecord(subject = subject, score = score)

    fun ItemDefEntity.toDomain(): ItemDef = ItemDef(
        item = Item(key),
        name = name,
        points = points,
        sortOrder = sortOrder,
        enabled = enabled,
        childClaimable = childClaimable,
        exclusiveGroup = exclusiveGroup,
        isComplaint = isComplaint
    )

    fun ItemDef.toEntity(): ItemDefEntity = ItemDefEntity(
        key = item.key,
        name = name,
        points = points,
        sortOrder = sortOrder,
        enabled = enabled,
        childClaimable = childClaimable,
        exclusiveGroup = exclusiveGroup,
        isComplaint = isComplaint
    )
}

/** item_def 的种子数据与引用判断。纯函数，可单测。 */
object ItemDefSeed {

    /**
     * 首次启动时写入 item_def 的初始数据（方案 3.2）。
     * @param legacyPoints 用户 DataStore 中实际存在的旧分值键（见 [RuleConfigCodec.LEGACY_POINT_KEYS]），
     *                     缺失的键回落到默认值
     */
    fun entities(legacyPoints: Map<String, Int>): List<ItemDefEntity> =
        ItemCatalog.builtin(legacyPoints).defs.map { Mappers.run { it.toEntity() } }

    /** [item] 是否出现在任一日记录的已确认或待确认中。被引用过的项不允许删除。 */
    fun isReferenced(item: Item, days: List<DayRecord>): Boolean =
        days.any { item in it.items || item in it.pending }

    /**
     * 把 [item] 与相邻项交换位置，返回需要写回的全部定义（sortOrder 重新编为 0..n-1，
     * 顺带修复历史上可能出现的重复 sortOrder）。已在边界或不存在时返回空列表。
     */
    fun move(defs: List<ItemDefEntity>, item: Item, up: Boolean): List<ItemDefEntity> {
        val sorted = defs.sortedWith(compareBy({ it.sortOrder }, { it.key })).toMutableList()
        val index = sorted.indexOfFirst { it.key == item.key }
        if (index < 0) return emptyList()
        val target = if (up) index - 1 else index + 1
        if (target !in sorted.indices) return emptyList()
        val tmp = sorted[index]
        sorted[index] = sorted[target]
        sorted[target] = tmp
        return sorted.mapIndexed { i, e -> e.copy(sortOrder = i) }
    }
}
