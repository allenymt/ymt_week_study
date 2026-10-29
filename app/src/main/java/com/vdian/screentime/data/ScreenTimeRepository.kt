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

    /**
     * 全局周锁：串行化所有对 `week_meta` 的"读-改-写"与银行链重放。
     *
     * 锁顺序（固定，不得反向）：`dayLock` → `repairLock` → `weekLock` → `seedLock`。
     * - 持 `weekLock` 时绝不再取 `dayLock` / `repairLock`，也绝不再取 `weekLock`（Mutex 不可重入）：
     *   因此锁内只调用带 `Locked` 后缀的私有助手，它们假定调用方已持锁、自身不取锁、
     *   也不调用 [repairBankCarry] / [observeWeek]。
     * - `itemDefLock` 与 `weekLock` 从不嵌套：目录写完、放锁之后才重放。
     */
    private val weekLock = Mutex()

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
        recomputeBankChain()
    }

    suspend fun setItemEnabled(item: Item, enabled: Boolean) {
        ensureItemDefs()
        itemDefLock.withLock { itemDefDao.setEnabled(item.key, enabled) }
        recomputeBankChain()
    }

    /** 与相邻项交换顺序；已在边界则不动。 */
    suspend fun moveItem(item: Item, up: Boolean) {
        ensureItemDefs()
        itemDefLock.withLock {
            val updated = ItemDefSeed.move(itemDefDao.all(), item, up)
            if (updated.isNotEmpty()) itemDefDao.updateAll(updated)
        }
        recomputeBankChain()
    }

    /** 删除考核项。被任何日记录引用过（已确认或待确认）则不删并返回 false——否则历史分数会凭空变化。 */
    suspend fun deleteItemDef(item: Item): Boolean {
        ensureItemDefs()
        val deleted = itemDefLock.withLock {
            if (isItemReferenced(item)) {
                false
            } else {
                itemDefDao.delete(item.key)
                true
            }
        }
        if (deleted) recomputeBankChain()
        return deleted
    }

    /** 数据量小，全表读入内存判断。 */
    suspend fun isItemReferenced(item: Item): Boolean =
        ItemDefSeed.isReferenced(item, allDays())

    // ---- 周视图 ----------------------------------------------------------------

    /**
     * 只读：不写任何行。期初规则见 [WeekBoardAssembler.build]——本周行不存在时
     * 现算上一周行的期末作为期初。订阅前先完成一次性的银行期初修复。
     */
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
            fallbackOpening = if (weekEntity == null) previousClosing(weekStart, cfg, catalog) else null,
            cfg = cfg,
            catalog = catalog
        )
        board
    }.onStart { repairBankCarry() }

    /** 上一条已存在周行的期末；没有上一周行时为 null（即期初为 0）。 */
    private suspend fun previousClosing(
        weekStart: Long,
        cfg: RuleConfig,
        catalog: ItemCatalog
    ): BankOpening? {
        val prev = weekDao.getPrevious(weekStart) ?: return null
        return BankCarry.closingOf(
            row = prev,
            days = dayDao.getWeek(prev.weekStart).map { Mappers.run { it.toDomain() } },
            exams = examDao.getWeek(prev.weekStart).map { Mappers.run { it.toDomain() } },
            cfg = cfg,
            catalog = catalog
        )
    }

    private val repairLock = Mutex()

    @Volatile
    private var bankCarryRepaired = false

    /**
     * 一次性修复 1.0 固化下来的错误期初（错位一周 + 考试奖励未结转）。
     *
     * 等价于启动时跑一次 [recomputeBankChain]。DataStore 标记 `bank_carry_repaired_v1`
     * 只在重放写回完成之后才写，进程中途被杀则下次启动重做（重放幂等）。
     *
     * 取锁顺序 `repairLock` → `weekLock`；调用方不得持有 `weekLock`。
     */
    suspend fun repairBankCarry() {
        if (bankCarryRepaired) return
        repairLock.withLock {
            if (bankCarryRepaired) return
            if (!settingsStore.isBankCarryRepaired()) {
                recomputeBankChain()
                settingsStore.markBankCarryRepaired()
            }
            bankCarryRepaired = true
        }
    }

    /**
     * 银行余额完全由历史推导：按 weekStart 升序重放全部周行，把期初缓存
     * （`prevBankMinutes / prevSavingCents`）写回为真值。取 `weekLock`。
     *
     * 调用时机：考核项目录写入、规则参数保存、一次性修复。写入某一周后的重放
     * 在写入方法自己的锁段内经 [recomputeIfLaterLocked] 完成，不走这里。
     */
    private suspend fun recomputeBankChain() = weekLock.withLock { recomputeBankChainLocked() }

    /** 调用方必须已持 `weekLock`。只写回值有变化的行，且在一个事务里写完。 */
    private suspend fun recomputeBankChainLocked() {
        val weeks = weekDao.all()
        if (weeks.isEmpty()) return
        val fixed = BankCarry.replay(
            weeks = weeks,
            daysByWeek = dayDao.all().groupBy({ it.weekStart }, { Mappers.run { it.toDomain() } }),
            examsByWeek = examDao.all().groupBy({ it.weekStart }, { Mappers.run { it.toDomain() } }),
            cfg = settingsStore.observeConfig().first(),
            catalog = currentCatalog()
        )
        val original = weeks.associateBy { it.weekStart }
        val changed = fixed.filter { it != original[it.weekStart] }
        if (changed.isNotEmpty()) weekDao.upsertAll(changed)
    }

    /**
     * 调用方必须已持 `weekLock`。写入第 [weekStart] 周（日记录、考试、周行，含新建行）之后调用：
     * 存在更晚的周行时重放整条链；写的是最新一周则无事可做。
     */
    private suspend fun recomputeIfLaterLocked(weekStart: Long) {
        if (weekDao.hasLater(weekStart)) recomputeBankChainLocked()
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

    /** 调用方持当天的 `dayLock`；这里再取 `weekLock`（顺序 dayLock → weekLock）。 */
    private suspend fun saveDay(weekStart: Long, day: DayRecord) = writeWeek(weekStart) {
        ensureWeekRowLocked(weekStart)
        dayDao.upsert(Mappers.run { day.toEntity(weekStart) })
    }

    suspend fun setExam(weekStart: Long, subject: Subject, score: Int) = writeWeek(weekStart) {
        ensureWeekRowLocked(weekStart)
        if (score <= 0) {
            examDao.delete(weekStart, subject)
        } else {
            examDao.upsert(ExamEntity(weekStart = weekStart, subject = subject, score = score))
        }
    }

    suspend fun addUsedMinutes(weekStart: Long, delta: Int) = writeWeek(weekStart) {
        val row = ensureWeekRowLocked(weekStart)
        weekDao.upsert(row.copy(usedMinutes = (row.usedMinutes + delta).coerceAtLeast(0)))
    }

    /** 超时惩罚：按实际超出的分钟数 1:1 计入。 */
    suspend fun applyOvertime(weekStart: Long, minutes: Int) = writeWeek(weekStart) {
        val row = ensureWeekRowLocked(weekStart)
        weekDao.upsert(row.copy(overtimeMinutes = (row.overtimeMinutes + minutes).coerceAtLeast(0)))
    }

    /** 兑换：扣减本周额度并累加金额。金额受每周上限约束。 */
    suspend fun redeem(weekStart: Long, minutes: Int) = writeWeek(weekStart) {
        val cfg = settingsStore.observeConfig().first()
        val row = ensureWeekRowLocked(weekStart)
        val board = boardLocked(weekStart, cfg, currentCatalog())
        val allowed = board.bank.redeemableMinutesThisWeek
        val actual = minutes.coerceAtMost(allowed)
        if (actual > 0) {
            val cents = com.vdian.screentime.domain.BankCalculator.centsForMinutes(actual, cfg)
            weekDao.upsert(
                row.copy(
                    redeemedMinutes = row.redeemedMinutes + actual,
                    redeemedCents = row.redeemedCents + cents
                )
            )
        }
    }

    suspend fun saveNotes(weekStart: Long, good: String, improve: String) = writeWeek(weekStart) {
        val row = ensureWeekRowLocked(weekStart)
        weekDao.upsert(row.copy(noteGood = good, noteImprove = improve))
    }

    /**
     * 人工存入（正）/ 扣减（负）存钱金额。
     *
     * 扣减不能把存钱累计压到 0 以下，所以先按当前可用金额夹一次再写，
     * 避免负数落库后影响后续周的结算。夹完为 0 时不建行、不写库。
     */
    suspend fun adjustSaving(weekStart: Long, deltaCents: Int) {
        if (deltaCents == 0) return
        writeWeek(weekStart) {
            val board = boardLocked(weekStart, settingsStore.observeConfig().first(), currentCatalog())
            val available = board.bank.savingCents
            val applied = if (deltaCents < 0) deltaCents.coerceAtLeast(-available) else deltaCents
            if (applied != 0) {
                val row = ensureWeekRowLocked(weekStart)
                weekDao.upsert(row.copy(savingAdjustCents = row.savingAdjustCents + applied))
            }
        }
    }

    /**
     * 对第 [weekStart] 周的一次写入：先确保一次性修复已完成（它自己取 `repairLock` → `weekLock`，
     * 所以必须在取 `weekLock` 之前），再在 `weekLock` 内执行 [block]，最后同一锁段内按需重放后续周。
     *
     * [block] 只能调用 `*Locked` 助手与 DAO，不得调用任何会取 `weekLock` 的公开方法。
     */
    private suspend fun <T> writeWeek(weekStart: Long, block: suspend () -> T): T {
        repairBankCarry()
        return weekLock.withLock {
            val result = block()
            recomputeIfLaterLocked(weekStart)
            result
        }
    }

    /**
     * 调用方必须已持 `weekLock`。取该周记录行；不存在则创建，期初取上一条周行的期末。
     * 新行插在两条已有周行之间时，后续周由 [writeWeek] 末尾的重放修正。
     */
    private suspend fun ensureWeekRowLocked(weekStart: Long): WeekEntity {
        weekDao.get(weekStart)?.let { return it }
        val opening = previousClosing(
            weekStart, settingsStore.observeConfig().first(), currentCatalog()
        ) ?: BankOpening.ZERO
        val settled = WeekEntity(
            weekStart = weekStart,
            prevBankMinutes = opening.bankMinutes,
            prevSavingCents = opening.savingCents
        )
        weekDao.upsert(settled)
        return settled
    }

    /**
     * 调用方必须已持 `weekLock`。与 [observeWeek] 同口径地现算一周的 [WeekBoard]，
     * 但不走 Flow（避免 `onStart` 里的 [repairBankCarry] 在锁内再取锁）。
     */
    private suspend fun boardLocked(weekStart: Long, cfg: RuleConfig, catalog: ItemCatalog): WeekBoard {
        val row = weekDao.get(weekStart)
        return WeekBoardAssembler.build(
            weekStart = weekStart,
            days = dayDao.getWeek(weekStart).map { Mappers.run { it.toDomain() } },
            exams = examDao.getWeek(weekStart).map { Mappers.run { it.toDomain() } },
            weekEntity = row,
            fallbackOpening = if (row == null) previousClosing(weekStart, cfg, catalog) else null,
            cfg = cfg,
            catalog = catalog
        )
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
     * 保存规则参数。参数（保底、封顶、考试奖励等）影响每一周的期末，所以写完 DataStore 后
     * 重放整条银行链。设置页必须走这里，不得直接调 [SettingsStore.saveConfig]。
     */
    suspend fun saveConfig(cfg: RuleConfig) {
        settingsStore.saveConfig(cfg)
        recomputeBankChain()
    }

    /**
     * 清空三张记录表。
     *
     * 规则参数与孩子姓名属于设置，不在这里删 —— "清空记录"再把分值改回默认值会很意外。
     */
    suspend fun clearAllData() = weekLock.withLock {
        // 在 weekLock 内执行：与重放（含一次性修复）串行，避免重放把刚删掉的行写回去。
        dayDao.deleteAll()
        weekDao.deleteAll()
        examDao.deleteAll()
    }
}
