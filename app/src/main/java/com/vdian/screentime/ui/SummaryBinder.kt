package com.vdian.screentime.ui

import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekSummary

/**
 * 把 [WeekSummary] 转成界面要显示的行，顺序固定，与纸质记录表下半部分一致。
 * 纯函数，不碰 Android，可单测。
 *
 * 这里只做格式化，不做算术——每个数字都来自 [WeekSummary] 的字段。
 */
object SummaryBinder {

    /**
     * 汇总卡片显示的行，"项目名 to 值"。
     * 值全部由 [WeekSummary] 给出，界面不再重算。
     */
    fun lines(s: WeekSummary): List<Pair<String, String>> = listOf(
        "保底" to s.baseMinutes.toString(),
        "一周无投诉" to s.noComplaintBonusMinutes.toString(),
        "每日加分合计" to s.dailyTotal.toString(),
        "考试奖励合计" to s.examTotal.toString(),
        // 投诉扣分显示为负数，便于和纸质表对照；单次扣分与"已使用"均由 WeekSummary 给出
        "投诉扣分" to (-s.complaintDeductionMinutes).toString(),
        "本周总赚取" to s.totalEarned.toString(),
        "本周已使用" to s.totalUsedMinutes.toString(),
        "本周结余" to s.balance.toString()
    )

    /**
     * 封顶提示。未封顶时返回空串，调用方据此隐藏该行。
     * 封顶时长由 [WeekSummary.capMinutes] 换算成小时，不写死 4。
     */
    fun overflowHint(s: WeekSummary): String =
        if (s.overflow > 0) "已封顶 ${s.capMinutes / 60} 小时，超出 ${s.overflow} 分钟可换小奖品" else ""

    /** 本周已录入的各科分数。没有记录的科目不出现在 map 里。 */
    fun examScores(b: WeekBoard): Map<Subject, Int> =
        b.exams.associate { it.subject to it.score }

    /**
     * 考试录入情况的一行摘要，例如"英语 92；数学 95"。
     * 一科都没录时返回提示语，避免卡片上出现一行空白让人以为没生效。
     */
    fun examSummary(b: WeekBoard): String {
        val scores = examScores(b)
        val parts = Subject.values().mapNotNull { subject ->
            scores[subject]?.takeIf { it > 0 }?.let { "${subject.label()} $it" }
        }
        return if (parts.isEmpty()) "本周未录入考试成绩" else parts.joinToString("；") + "（合计 ${b.summary.examTotal}）"
    }

    /** 科目中文名。与纸质记录表用词一致。 */
    private fun Subject.label(): String = when (this) {
        Subject.ENGLISH -> "英语"
        Subject.CHINESE -> "语文"
        Subject.MATH -> "数学"
        Subject.SCIENCE -> "科学"
    }
}
