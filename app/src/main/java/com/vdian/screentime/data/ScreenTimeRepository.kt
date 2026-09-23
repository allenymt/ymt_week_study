package com.vdian.screentime.data

import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.ScoreRules
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekDates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class ScreenTimeRepository(
    private val dayDao: DayDao,
    private val weekDao: WeekDao,
    private val examDao: ExamDao,
    private val settingsStore: SettingsStore
) {

    fun observeWeek(weekStart: Long): Flow<WeekBoard> = combine(
        dayDao.observeWeek(weekStart),
        examDao.observeWeek(weekStart),
        weekDao.observe(weekStart),
        settingsStore.observeConfig()
    ) { dayEntities, examEntities, weekEntity, cfg ->
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = dayEntities.map { Mappers.run { it.toDomain() } },
            exams = examEntities.map { Mappers.run { it.toDomain() } },
            weekEntity = weekEntity,
            prevEntity = weekDao.getPrevious(weekStart),
            cfg = cfg
        )
        board
    }

    /** 孩子点击：把项加入待确认。已确认的项不受影响。 */
    suspend fun toggleChildClaim(weekStart: Long, epochDay: Long, item: Item) {
        val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } } ?: DayRecord(epochDay)
        val items = current.items
        val pending = ScoreRules.claim(item, items, current.pending)
        saveDay(weekStart, current.copy(pending = pending))
    }

    /** 家长点击：确认待确认项 / 取消已确认项 / 直接确认空格。 */
    suspend fun toggleParentConfirm(weekStart: Long, epochDay: Long, item: Item) {
        val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } } ?: DayRecord(epochDay)
        val (items, pending) = ScoreRules.advance(item, current.items, current.pending)
        saveDay(weekStart, current.copy(items = items, pending = pending))
    }

    /** 家长长按：退回孩子的申报。 */
    suspend fun retractClaim(weekStart: Long, epochDay: Long, item: Item) {
        val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } } ?: return
        val pending = ScoreRules.retract(item, current.pending)
        saveDay(weekStart, current.copy(pending = pending))
    }

    private suspend fun saveDay(weekStart: Long, day: DayRecord) {
        ensureWeekRow(weekStart)
        dayDao.upsert(Mappers.run { day.toEntity(weekStart) })
    }

    suspend fun setExam(weekStart: Long, subject: Subject, score: Int) {
        ensureWeekRow(weekStart)
        if (score <= 0) {
            examDao.delete(weekStart, subject)
        } else {
            examDao.upsert(ExamEntity(weekStart = weekStart, subject = subject, score = score))
        }
    }

    suspend fun addUsedMinutes(weekStart: Long, delta: Int) {
        val row = ensureWeekRow(weekStart)
        weekDao.upsert(row.copy(usedMinutes = (row.usedMinutes + delta).coerceAtLeast(0)))
    }

    /** 超时惩罚：按实际超出的分钟数 1:1 计入。 */
    suspend fun applyOvertime(weekStart: Long, minutes: Int) {
        val row = ensureWeekRow(weekStart)
        weekDao.upsert(row.copy(overtimeMinutes = (row.overtimeMinutes + minutes).coerceAtLeast(0)))
    }

    /** 兑换：扣减本周额度并累加金额。金额受每周上限约束。 */
    suspend fun redeem(weekStart: Long, minutes: Int) {
        val cfg = settingsStore.observeConfig().first()
        val row = ensureWeekRow(weekStart)
        val board = observeWeek(weekStart).first()
        val allowed = board.bank.redeemableMinutesThisWeek
        val actual = minutes.coerceAtMost(allowed)
        if (actual <= 0) return
        val cents = com.vdian.screentime.domain.BankCalculator.centsForMinutes(actual, cfg)
        weekDao.upsert(
            row.copy(
                redeemedMinutes = row.redeemedMinutes + actual,
                redeemedCents = row.redeemedCents + cents
            )
        )
    }

    suspend fun saveNotes(weekStart: Long, good: String, improve: String) {
        val row = ensureWeekRow(weekStart)
        weekDao.upsert(row.copy(noteGood = good, noteImprove = improve))
    }

    /** 取该周记录行；不存在则创建，并把上周的银行与存钱结算结果固化进来。 */
    private suspend fun ensureWeekRow(weekStart: Long): WeekEntity {
        weekDao.get(weekStart)?.let { return it }
        val prev = weekDao.getPrevious(weekStart)
        val settled = if (prev == null) {
            WeekEntity(weekStart = weekStart, prevBankMinutes = 0, prevSavingCents = 0)
        } else {
            val prevBoard = WeekBoardAssembler.build(
                weekStart = prev.weekStart,
                days = dayDao.all().filter { it.weekStart == prev.weekStart }.map { Mappers.run { it.toDomain() } },
                exams = emptyList(),
                weekEntity = prev,
                prevEntity = weekDao.getPrevious(prev.weekStart),
                cfg = settingsStore.observeConfig().first()
            )
            WeekEntity(
                weekStart = weekStart,
                prevBankMinutes = prevBoard.bank.bankBalanceMinutes,
                prevSavingCents = prevBoard.bank.savingCents
            )
        }
        weekDao.upsert(settled)
        return settled
    }

    /** 生成一周的空白 7 天记录，便于界面直接渲染。 */
    fun blankWeek(weekStart: Long): List<DayRecord> =
        (0L..6L).map { DayRecord(weekStart + it) }
}
