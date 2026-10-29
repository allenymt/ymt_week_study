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
import com.vdian.screentime.domain.WeekMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class ScreenTimeRepository(
    private val dayDao: DayDao,
    private val weekDao: WeekDao,
    private val examDao: ExamDao,
    private val settingsStore: SettingsStore,
    /** 串行化单格子的"读-改-写"。可注入，便于单测替换。 */
    private val cellLock: KeyedMutex = KeyedMutex()
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

    /**
     * 银行页的数据源：当前周视图 + 全部历史周行，聚合成 [BankRollup]。
     *
     * 取 [WeekEntity] 原始行而不是逐周再造 [WeekBoard]，是因为累计已兑换只是历史事实的求和，
     * 再算一遍各周结余既慢又容易和 [observeWeek] 的口径产生偏差。
     */
    fun observeBankRollup(weekStart: Long): Flow<BankRollup> = combine(
        observeWeek(weekStart),
        weekDao.observeAll()
    ) { board, allWeeks ->
        BankRollupAggregator.build(latest = board, allWeeks = allWeeks)
    }

    /**
     * 孩子点击：把项加入待确认。已确认的项不受影响。
     *
     * 读-改-写整段持 (epochDay, item) 锁，见 [cellLock] 的说明。
     */
    suspend fun toggleChildClaim(weekStart: Long, epochDay: Long, item: Item) =
        cellLock.withLock(epochDay to item) {
            val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } }
                ?: DayRecord(epochDay)
            val items = current.items
            val pending = ScoreRules.claim(item, items, current.pending)
            saveDay(weekStart, current.copy(pending = pending))
        }

    /** 家长点击：确认待确认项 / 取消已确认项 / 直接确认空格。 */
    suspend fun toggleParentConfirm(weekStart: Long, epochDay: Long, item: Item) =
        cellLock.withLock(epochDay to item) {
            val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } }
                ?: DayRecord(epochDay)
            val (items, pending) = ScoreRules.advance(item, current.items, current.pending)
            saveDay(weekStart, current.copy(items = items, pending = pending))
        }

    /** 家长长按：退回孩子的申报。 */
    suspend fun retractClaim(weekStart: Long, epochDay: Long, item: Item) =
        cellLock.withLock(epochDay to item) {
            val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } }
            if (current != null) {
                val pending = ScoreRules.retract(item, current.pending)
                saveDay(weekStart, current.copy(pending = pending))
            }
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

    /**
     * 人工存入（正）/ 扣减（负）存钱金额。
     *
     * 扣减不能把存钱累计压到 0 以下，所以先按当前可用金额夹一次再写，
     * 避免负数落库后影响后续周的结算。
     */
    suspend fun adjustSaving(weekStart: Long, deltaCents: Int) {
        if (deltaCents == 0) return
        val board = observeWeek(weekStart).first()
        val available = board.bank.savingCents
        val applied = if (deltaCents < 0) deltaCents.coerceAtLeast(-available) else deltaCents
        if (applied == 0) return
        val row = ensureWeekRow(weekStart)
        weekDao.upsert(row.copy(savingAdjustCents = row.savingAdjustCents + applied))
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
    // ---- 设置页用：全量读取、导出与清空 ------------------------------------

    /** 全部日记录，按天升序。 */
    suspend fun allDays(): List<DayRecord> = dayDao.all().map { Mappers.run { it.toDomain() } }

    /** 全部周记录，按周升序。 */
    suspend fun allWeeks(): List<WeekMeta> = weekDao.all().map { Mappers.run { it.toDomain() } }

    /** 全部考试记录，按周、id 升序。 */
    suspend fun allExams(): List<ExamRecord> = examDao.all().map { Mappers.run { it.toDomain() } }

    /** 当前生效的规则参数。导出时要把它一并写出去，否则记录无法解读。 */
    suspend fun currentConfig(): RuleConfig = settingsStore.observeConfig().first()

    suspend fun childName(): String = settingsStore.observeChildName().first()

    /**
     * 清空三张记录表。
     *
     * 规则参数与孩子姓名属于设置，不在这里删 —— "清空记录"再把分值改回默认值会很意外。
     */
    suspend fun clearAllData() {
        dayDao.deleteAll()
        weekDao.deleteAll()
        examDao.deleteAll()
    }
}
