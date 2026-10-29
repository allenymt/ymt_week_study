package com.vdian.screentime.domain

/** 删除考核项的结果。 */
enum class DeleteItemResult { DELETED, COMPLAINT, REFERENCED }

/** 设置页新增 / 编辑考核项时的校验与构造。纯逻辑，不依赖 Android。 */
object ItemDefEditor {

    /** 投诉项永不可删；其余被历史引用过也不可删。 */
    fun deleteDecision(isComplaint: Boolean, referenced: Boolean): DeleteItemResult = when {
        isComplaint -> DeleteItemResult.COMPLAINT
        referenced -> DeleteItemResult.REFERENCED
        else -> DeleteItemResult.DELETED
    }

    const val MAX_POINTS = 1000

    /** 新增项的 key，用时间戳保证唯一且不与内置项冲突。 */
    fun newKey(nowMillis: Long): String = "CUSTOM_$nowMillis"

    /** 列表中的带符号分值文案：+10 / -10 / 0。 */
    fun signedPoints(points: Int): String = if (points > 0) "+$points" else "$points"

    /** 新增项排在最后。 */
    fun nextSortOrder(defs: List<ItemDef>): Int = (defs.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** 编辑改了分值时才需要提示"会重算历史"。 */
    fun pointsChanged(old: ItemDef?, new: ItemDef): Boolean = old != null && old.points != new.points

    /** 校验结果：成功时 [def] 非空，否则 [nameError] / [pointsError] 至少一个非空。 */
    data class Result(
        val def: ItemDef? = null,
        val nameError: String? = null,
        val pointsError: String? = null
    )

    /**
     * 由对话框输入构造 [ItemDef]。
     * [base] 为被编辑的原定义（保留 key、排序、启用状态、isComplaint）；新增时传 null 并给出 [newItem]。
     */
    fun build(
        base: ItemDef?,
        newItem: Item?,
        sortOrder: Int,
        name: String,
        pointsText: String,
        childClaimable: Boolean,
        exclusiveGroup: String
    ): Result {
        val trimmedName = name.trim()
        val nameError = if (trimmedName.isEmpty()) "名称不能为空" else null
        val points = pointsText.trim().toIntOrNull()
        val isComplaint = base?.isComplaint ?: false
        val pointsError = when {
            points == null -> "请输入整数分值"
            points > MAX_POINTS || points < -MAX_POINTS -> "分值范围 -$MAX_POINTS 到 $MAX_POINTS"
            isComplaint && points >= 0 -> "投诉项分值必须为负数"
            else -> null
        }
        if (nameError != null || pointsError != null || points == null) {
            return Result(nameError = nameError, pointsError = pointsError)
        }
        val item = base?.item ?: newItem ?: error("base 与 newItem 不能同时为空")
        val group = exclusiveGroup.trim().ifEmpty { null }
        return Result(
            def = ItemDef(
                item = item,
                name = trimmedName,
                points = points,
                sortOrder = base?.sortOrder ?: sortOrder,
                enabled = base?.enabled ?: true,
                childClaimable = childClaimable,
                exclusiveGroup = group,
                isComplaint = isComplaint
            )
        )
    }
}
