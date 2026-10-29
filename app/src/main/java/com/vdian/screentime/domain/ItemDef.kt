package com.vdian.screentime.domain

import kotlin.math.abs

/**
 * 一个考核项的定义。
 * @param points 正数加分，负数扣分
 * @param enabled 停用后不在表格显示，历史上勾过的照常计分
 * @param exclusiveGroup 同组互斥，同一天最多一个处于已确认或待确认
 * @param isComplaint 走投诉专用规则：每周计次上限、影响"一周无投诉"奖励
 */
data class ItemDef(
    val item: Item,
    val name: String,
    val points: Int,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val childClaimable: Boolean,
    val exclusiveGroup: String? = null,
    val isComplaint: Boolean = false
)

/** 考核项目录。纯数据查询，不依赖 Android。未知 item 一律按 0 分、非投诉、无互斥组处理。 */
class ItemCatalog(val defs: List<ItemDef>) {

    private val byItem: Map<Item, ItemDef> = defs.associateBy { it.item }

    fun def(item: Item): ItemDef? = byItem[item]

    /** 未知 item 返回 0。 */
    fun points(item: Item): Int = byItem[item]?.points ?: 0

    fun isComplaint(item: Item): Boolean = byItem[item]?.isComplaint ?: false

    /** 表格列：enabled 且按 sortOrder 升序。 */
    fun columns(): List<ItemDef> = defs.filter { it.enabled }.sortedBy { it.sortOrder }

    fun groupOf(item: Item): String? = byItem[item]?.exclusiveGroup

    /** 单次投诉的扣分绝对值，供汇总"投诉扣分"行展示；无投诉项时为 0。 */
    fun complaintPenalty(): Int =
        defs.sortedBy { it.sortOrder }.firstOrNull { it.isComplaint }?.let { abs(it.points) } ?: 0

    companion object {
        /** legacyPoints 中投诉扣分的 key，取值为正数。 */
        private const val COMPLAINT_PENALTY_KEY = "complaintPenalty"

        /** 方案 3.2 的 11 条初始数据。现有 7 项分值由调用方传入（来自用户当前设置）。 */
        fun builtin(legacyPoints: Map<String, Int> = emptyMap()): ItemCatalog {
            fun p(key: String, default: Int) = legacyPoints[key] ?: default
            val complaint = -(legacyPoints[COMPLAINT_PENALTY_KEY] ?: 30)
            return ItemCatalog(
                listOf(
                    ItemDef(Item.RAISED, "举手回答", p("raisePoints", 10), 0, childClaimable = true),
                    ItemDef(Item.NEAT_WRITING, "字认真", p("neatWritingPoints", 10), 1,
                        childClaimable = true, exclusiveGroup = GROUP_WRITING),
                    ItemDef(Item.NEAT_WRITING_BAD, "字不认真", -10, 2,
                        childClaimable = false, exclusiveGroup = GROUP_WRITING),
                    ItemDef(Item.ENGLISH, "主动英语", p("englishPoints", 10), 3, childClaimable = true),
                    ItemDef(Item.LISTENING_PERFECT, "英语听力全对", 10, 4, childClaimable = true),
                    ItemDef(Item.READING, "主动阅读", p("readingPoints", 15), 5, childClaimable = true),
                    ItemDef(Item.EXERCISE, "主动运动", p("exercisePoints", 15), 6, childClaimable = true),
                    ItemDef(Item.HOMEWORK_ACTIVE, "作业积极", 10, 7,
                        childClaimable = true, exclusiveGroup = GROUP_HOMEWORK),
                    ItemDef(Item.HOMEWORK_PASSIVE, "作业不积极", -10, 8,
                        childClaimable = false, exclusiveGroup = GROUP_HOMEWORK),
                    ItemDef(Item.PRAISE, "老师表扬", p("praisePoints", 10), 9, childClaimable = true),
                    ItemDef(Item.COMPLAINT, "投诉", complaint, 10,
                        childClaimable = false, isComplaint = true)
                )
            )
        }

        private const val GROUP_WRITING = "writing"
        private const val GROUP_HOMEWORK = "homework"
    }
}
