package com.vdian.screentime.data

import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.ScoreRules
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekDates
import com.vdian.screentime.domain.WeekMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScreenTimeRepository(
    private val dayDao: DayDao,
    private val weekDao: WeekDao,
    private val examDao: ExamDao,
    private val itemDefDao: ItemDefDao,
    private val settingsStore: SettingsStore,
    /**
     * 串行化同一天的"读-改-写"，按 epochDay 加锁。
     * 不按 (epochDay, item) 分格：每次写都会整行替换 [com.vdian.screentime.data.entity.DayEntity]，
     * 且确认时会顺带改动同一互斥组的另一项，分格加锁会让同一天的两次操作互相覆盖。
     */
    private val dayLock: KeyedMutex = KeyedMutex()
) {

    /** 种子写入只做一次：进程内标记 + 互斥锁防止并发重复写入（insert 本身也是 IGNORE）。 */
    private val seedLock = Mutex()

    @Volatile
    private var itemDefsReady = false

    /** 串行化 item_def 的"读-改-写"（排序、删除）。 */
    private val itemDefLock = Mutex()

    // ---- 考核项目录 ------------------------------------------------------------

    /**
     * item_def 为空时写入方案 3.2 的初始数据，现有 7 项的分值取自用户 DataStore 中存过的旧设置。
     * 迁移 v2→v3 只建表，这一步在首次读取目录时完成。所有读写目录的入口都会先调用它。
     */
    suspend fun ensureItemDefs() {
        if (itemDefsReady) return
        seedLock.withLock {
            if (itemDefsReady) return
            if (itemDefDao.count() == 0) {
                itemDefDao.insertIfAbsent(ItemDefSeed.entities(settingsStore.readLegacyPoints()))
            }
            itemDefsReady = true
        }
    }

    /** 考核项目录。订阅时先确保种子数据已写入，因此首个发射值不会是空目录。 */
    fun observeItemCatalog(): Flow<ItemCatalog> =
        itemDefDao.observeAll()
            .onStart { ensureItemDefs() }
            .map { rows -> ItemCatalog(rows.map { Mappers.run { it.toDomain() } }) }

    private suspend fun currentCatalog(): ItemCatalog = observeItemCatalog().first()

    /** 新增或整条覆盖一个考核项定义（按 key）。 */
    suspend fun upsertItemDef(def: ItemDef) {
        ensureItemDefs()
        itemDefLock.withLock { itemDefDao.upsert(Mappers.run { def.toEntity() }) }
    }

    suspend fun setItemEnabled(item: Item, enabled: Boolean) {
        ensureItemDefs()
        itemDefLock.withLock { itemDefDao.setEnabled(item.key, enabled) }
    }

    /** 与相邻项交换顺序；已在边界则不动。 */
    suspend fun moveItem(item: Item, up: Boolean) {
        ensureItemDefs()
        itemDefLock.withLock {
            val updated = ItemDefSeed.move(itemDefDao.all(), item, up)
            if (updated.isNotEmpty()) itemDefDao.updateAll(updated)
        }
    }

    /** 删除考核项。被任何日记录引用过（已确认或待确认）则不删并返回 false——否则历史分数会凭空变化。 */
    suspend fun deleteItemDef(item: Item): Boolean {
        ensureItemDefs()
        return itemDefLock.withLock {
            if (isItemReferenced(item)) {
                false
            } else {
                itemDefDao.delete(item.key)
                true
            }
        }
    }

    /** 数据量小，全表读入内存判断。 */
    suspend fun isItemReferenced(item: Item): Boolean =
        ItemDefSeed.isReferenced(item, allDays())

    // ---- 周视图 ----------------------------------------------------------------

    fun observeWeek(weekStart: Long): Flow<WeekBoard> = combine(
        dayDao.observeWeek(weekStart),
        examDao.observeWeek(weekStart),
        weekDao.observe(weekStart),
        settingsStore.observeConfig(),
        observeItemCatalog()
    ) { dayEntities, examEntities, weekEntity, cfg, catalog ->
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = dayEntities.map { Mappers.run { it.toDomain() } },
            exams = examEntities.map { Mappers.run { it.toDomain() } },
            weekEntity = weekEntity,
            prevEntity = weekDao.getPrevious(weekStart),
            cfg = cfg,
            catalog = catalog
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
     * 目标项不在目录中或 [ItemDef.childClaimable] 为 false 时直接返回，不写库。
     *
     * 读-改-写整段持当天的锁，见 [dayLock] 的说明。
     */
    suspend fun toggleChildClaim(weekStart: Long, epochDay: Long, item: Item) {
        val catalog = currentCatalog()
        if (catalog.def(item)?.childClaimable != true) return
        dayLock.withLock(epochDay) {
            val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } }
                ?: DayRecord(epochDay)
            val pending = ScoreRules.claim(item, current.items, current.pending, catalog)
            if (pending != current.pending) saveDay(weekStart, current.copy(pending = pending))
        }
    }

    /** 家长点击：确认待确认项 / 取消已确认项 / 直接确认空格。确认时清掉同一互斥组的其他项。 */
    suspend fun toggleParentConfirm(weekStart: Long, epochDay: Long, item: Item) {
        val catalog = currentCatalog()
        dayLock.withLock(epochDay) {
            val current = dayDao.getByDay(epochDay)?.let { Mappers.run { it.toDomain() } }
                ?: DayRecord(epochDay)
            val (items, pending) = ScoreRules.advance(item, current.items, current.pending, catalog)
            saveDay(weekStart, current.copy(items = items, pending = pending))
        }
    }

    /** 家长长按：退回孩子的申报。 */
    suspend fun retractClaim(weekStart: Long, epochDay: Long, item: Item) =
        dayLock.withLock(epochDay) {
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
                cfg = settingsStore.observeConfig().first(),
                catalog = currentCatalog()
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
