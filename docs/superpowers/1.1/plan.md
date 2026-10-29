# 1.1 版本实施计划

> **执行方式**：逐任务实现，每个任务独立构建、独立测试、独立提交。每个任务结束时 `./gradlew :app:testDebugUnitTest :app:assembleDebug` 必须通过。

**目标**：考核项改为可动态增减（含 4 个新项），导出格式升级为 v2，支持导入（兼容 v1）。

**设计依据**：`docs/superpowers/1.1/design.md`（下称"方案"）。计划与方案冲突时以方案为准。

## 全局约束

- 包名 `com.vdian.screentime`；Kotlin 1.6.10；minSdk 21；不得使用 `java.time`
- `domain/` 包不得 import 任何 `android.*`
- UI 层不做算术，派生数字来自 ViewModel / domain
- 分值、封顶值不得写死在业务代码里
- 不新增三方库；不申请任何权限
- 不改 `gradle.properties` 中固定 JDK 11 那一行；工作区里未提交的 AGP 4.2.2 / Gradle 6.7.1 升级**不要提交、不要还原**，提交时只 `git add` 自己改动的文件
- 提交信息中文，`feat:` / `fix:` / `test:` / `refactor:` 前缀，结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## 关键设计：Item 改为值对象

```kotlin
/** 考核项标识。原为枚举，1.1 起改为任意字符串 key，内置项保留同名常量。 */
data class Item(val key: String) {
    companion object {
        val RAISED = Item("RAISED")
        val NEAT_WRITING = Item("NEAT_WRITING")
        val NEAT_WRITING_BAD = Item("NEAT_WRITING_BAD")
        val ENGLISH = Item("ENGLISH")
        val LISTENING_PERFECT = Item("LISTENING_PERFECT")
        val READING = Item("READING")
        val EXERCISE = Item("EXERCISE")
        val HOMEWORK_ACTIVE = Item("HOMEWORK_ACTIVE")
        val HOMEWORK_PASSIVE = Item("HOMEWORK_PASSIVE")
        val PRAISE = Item("PRAISE")
        val COMPLAINT = Item("COMPLAINT")
    }
}
```

`Set<Item>`、`Item.RAISED` 的写法保持不变，绝大多数测试无需改动。数据库里存的本来就是 key 字符串，历史数据零迁移。

## 测试关注点（规格隐含、最容易出错的地方）

1. **用户改过分值的老数据**：迁移/种子数据必须读用户在 DataStore 里存的分值，不能用默认值，否则升级后历史分数凭空变化
2. **互斥的两个方向**：家长确认 A 要清掉同组 B 的"已确认"和"待确认"；孩子申报 A 时同组 B 已确认则拒绝
3. **停用项的历史分数**：停用后表格不显示该列，但历史周小计不变
4. **v1 导入的周记录对应**：条数对不上时必须整体拒绝周数据，不能猜
5. **导入失败不留残局**：任一校验失败或写库异常，现有数据保持原样

---

### Task 1：Domain 层——Item 值对象、考核项目录、计分与互斥

**文件**
- 改：`domain/Item.kt`、`domain/ScoreRules.kt`、`domain/WeekCalculator.kt`、`domain/WeekSummary.kt`
- 建：`domain/ItemDef.kt`（含 `ItemDef`、`ItemCatalog`）
- 改：`data/AppDatabase.kt`（转换器）、`data/WeekBoard.kt`、`data/ScreenTimeRepository.kt`、`ui/DayLabels.kt`、`ui/WeekFragment.kt`、`ui/ItemColumns.kt`、`ui/SummaryBinder.kt`（如需）
- 测：`domain/ScoreRulesTest.kt`、`domain/WeekCalculatorTest.kt`、新建 `domain/ItemCatalogTest.kt`

**接口**
```kotlin
data class ItemDef(
    val item: Item,
    val name: String,
    val points: Int,              // 正加负扣
    val sortOrder: Int,
    val enabled: Boolean = true,
    val childClaimable: Boolean,
    val exclusiveGroup: String? = null,
    val isComplaint: Boolean = false
)

class ItemCatalog(val defs: List<ItemDef>) {
    fun points(item: Item): Int          // 未知 item 返回 0
    fun isComplaint(item: Item): Boolean
    fun def(item: Item): ItemDef?
    fun columns(): List<ItemDef>          // enabled 且按 sortOrder 升序
    fun groupOf(item: Item): String?
    /** 单次投诉的扣分绝对值，供汇总"投诉扣分"行展示；无投诉项时为 0。 */
    fun complaintPenalty(): Int
    companion object {
        /** 方案 3.2 的 11 条初始数据。现有 7 项分值由调用方传入（来自用户当前设置）。 */
        fun builtin(legacyPoints: Map<String, Int> = emptyMap()): ItemCatalog
    }
}
```

`builtin` 的 `legacyPoints` key 与旧 `RuleConfig` 字段名一致：`raisePoints / neatWritingPoints / englishPoints / readingPoints / exercisePoints / praisePoints / complaintPenalty`（投诉为正数，转换为负的 points）。缺失时用方案 3.2 的默认值。

**ScoreRules 签名调整**：所有原先取 `cfg: RuleConfig` 计算单项分值的函数，改为额外接收 `catalog: ItemCatalog`；投诉判断由 `item == Item.COMPLAINT` 改为 `catalog.isComplaint(item)`；`complaintMaxPerWeek` 仍取自 `RuleConfig`。

```kotlin
fun itemPoints(item: Item, catalog: ItemCatalog): Int = catalog.points(item)
fun dailyScores(days: List<DayRecord>, cfg: RuleConfig, catalog: ItemCatalog): List<Int>
fun effectiveComplaintCount(days: List<DayRecord>, cfg: RuleConfig, catalog: ItemCatalog): Int
fun claim(item: Item, items: Set<Item>, pending: Set<Item>, catalog: ItemCatalog): Set<Item>
fun advance(item: Item, items: Set<Item>, pending: Set<Item>, catalog: ItemCatalog): Pair<Set<Item>, Set<Item>>
```

`WeekCalculator.summarize(days, exams, meta, cfg, catalog)`；`WeekSummary.complaintPenalty` 取 `catalog.complaintPenalty()`。

**过渡**：本任务不删 `RuleConfig` 的 7 个分值字段。`WeekBoard` 暂用 `ItemCatalog.builtin(RuleConfigCodec.encode(config))` 构造目录，保证构建通过；Task 2 改为读数据库。`ScreenTimeRepository` 里调用 `claim` / `advance` 的三处，本任务同样用当前 config 构造过渡目录传入。`WeekFragment.renderHeader` 随 `DayLabels.itemHeader` 签名同步修改。

**数据库转换器**：`stringToItems` 由"按枚举名查找、未知丢弃"改为"任意非空 key 都保留为 `Item(key)`"。

**DayLabels**：`itemName(item)` / `itemHeader(item, cfg)` 改为 `itemName(item, catalog)` / `itemHeader(item, catalog)`，名称取自 `ItemDef.name`；未知 key 显示 key 本身。

**ItemColumns.ROW_ITEMS**：本任务暂改为 `ItemCatalog.builtin().columns().map { it.item }` 的过渡值，Task 3 改为随数据变化。

**必须新增的测试**
- 扣分项计入当日小计：勾 `NEAT_WRITING_BAD` + `RAISED` → 0
- 新扣分项不受每周 2 次限制：4 天各勾 `HOMEWORK_PASSIVE` → 周合计 −40
- 新扣分项不影响无投诉奖励：只勾 `HOMEWORK_PASSIVE` 的一周，`noComplaintBonusMinutes == 60`
- 投诉规则不变：现有投诉相关测试全部保持通过
- `builtin(legacyPoints)` 使用传入分值：`readingPoints=20` → `points(READING)==20`；`complaintPenalty=40` → `points(COMPLAINT)==-40`
- 互斥·家长：`items={NEAT_WRITING}`，家长确认 `NEAT_WRITING_BAD` → `items={NEAT_WRITING_BAD}`
- 互斥·家长清待确认：`pending={HOMEWORK_ACTIVE}`，家长确认 `HOMEWORK_PASSIVE` → `pending` 不含 `HOMEWORK_ACTIVE`
- 互斥·孩子拒绝：`items={NEAT_WRITING}`，孩子申报 `NEAT_WRITING_BAD` → `pending` 不变
- 停用项仍计分：目录中 `READING.enabled=false`，勾了 `READING` 的那天小计不变；`columns()` 不含 `READING`
- 未知 key 计 0 分不崩溃

**提交**：`refactor: 考核项改为值对象与可配置目录，新增 4 个考核项与互斥规则`

---

### Task 2：Data 层——item_def 表、迁移、种子数据、仓库接口

**文件**
- 建：`data/entity/ItemDefEntity.kt`、`data/ItemDefDao.kt`
- 改：`data/AppDatabase.kt`（v2→v3）、`data/ScreenTimeRepository.kt`、`data/WeekBoard.kt`、`data/Mappers.kt`、`data/SettingsStore.kt`
- 测：新建 `data/ItemDefSeedTest.kt`（纯函数部分）

**表结构**：见方案 3.1。`MIGRATION_2_3` 只建表，不插数据。

**种子数据**：`ScreenTimeRepository.ensureItemDefs()`——表为空时，从 DataStore 读取旧的 7 个分值键（`rule_raisePoints` 等，`SettingsStore` 新增 `readLegacyPoints(): Map<String, Int>`，读不到的键不放入 map），调用 `ItemCatalog.builtin(legacy)` 写入。在 `AppContainer` 首次创建仓库后、首个 `observeWeek` 之前触发一次（可放在 `observeItemCatalog()` 的 `onStart`）。

**仓库新增接口**
```kotlin
fun observeItemCatalog(): Flow<ItemCatalog>
suspend fun upsertItemDef(def: ItemDef)
suspend fun setItemEnabled(item: Item, enabled: Boolean)
suspend fun moveItem(item: Item, up: Boolean)          // 与相邻项交换 sortOrder
suspend fun deleteItemDef(item: Item): Boolean          // 被任何日记录引用过则返回 false 且不删
suspend fun isItemReferenced(item: Item): Boolean
```

`isItemReferenced`：扫描 `day_record` 的 `items` 与 `pending`（数据量小，全表读取在内存判断即可）。

**WeekBoard**：`observeWeek` 的 `combine` 增加 `observeItemCatalog()`，`WeekBoard` 新增字段 `catalog: ItemCatalog`，`summary` / `bank` 使用它。去掉 Task 1 的过渡构造。`ensureWeekRow` 里计算上周结余同样使用当前目录。

**三态操作**：`toggleChildClaim` / `toggleParentConfirm` 传入当前目录；`toggleChildClaim` 在目标项 `childClaimable == false` 时直接返回不写库。

**必须新增的测试**
- `builtin` 种子：不传 legacy → 11 条，分值等于方案 3.2；传入部分 legacy → 仅覆盖对应项
- 种子数据的 key 集合包含历史数据用到的全部 6 个 key（RAISED、NEAT_WRITING、ENGLISH、READING、EXERCISE、PRAISE）
- 引用判断的纯函数：给定日记录列表，正确识别 `items` 与 `pending` 中的引用

**提交**：`feat: 新增 item_def 表与迁移，考核项由数据库驱动`

---

### Task 3：本周表格——动态列与横向滚动

**文件**
- 改：`ui/ItemColumns.kt`、`ui/WeekRowAdapter.kt`、`ui/WeekFragment.kt`、`ui/WeekViewModel.kt`、`res/layout/fragment_week.xml`、`res/layout/item_week_row.xml`
- 测：`ui/DayLabelsTest.kt` 同步更新

**要求**
- 列来自 `board.catalog.columns()`，不再使用 `ItemColumns.ROW_ITEMS`（删除该常量）
- 目录变化（新增、停用、排序）后表头与数据行同步重建
- 布局：左侧星期列、右侧小计列固定；中间考核项区域（表头 + 7 行）放在**同一个** `HorizontalScrollView` 里，保证表头与数据行一起滚动、列对齐。单格固定宽度 `56dp`（不再按权重均分），满足触控尺寸
- 孩子模式下点击 `childClaimable == false` 的格子不做任何事
- 表头文案用 `DayLabels.itemHeader(item, catalog)`，扣分项显示负号

**验证**：单测通过 + `assembleDebug` 通过。真机检查项写入报告（表头与数据行滚动同步、固定列不动、11 列可完整操作），由用户真机验证。

**提交**：`feat: 本周表格按考核项目录动态生成列并支持横向滚动`

---

### Task 4：设置页——考核项管理，移除 RuleConfig 中的逐项分值

**文件**
- 改：`domain/RuleConfig.kt`、`data/SettingsStore.kt`（Codec）、`ui/SettingsFragment.kt`、`ui/SettingsViewModel.kt`、`res/layout/fragment_settings.xml`
- 建：`ui/ItemDefAdapter.kt`、`res/layout/item_item_def.xml`、`res/layout/dialog_item_def.xml`
- 测：`data/RuleConfigSerializationTest.kt`、`ui/DayLabelsTest.kt`、`data/DataExporterTest.kt`（去掉对 `raisePoints` 的使用）

**RuleConfig**：删除 `raisePoints`、`neatWritingPoints`、`englishPoints`、`readingPoints`、`exercisePoints`、`praisePoints`、`complaintPenalty` 7 个字段。Codec 字段数 23 → 16，测试断言同步修改。Codec 解码遇到这 7 个旧键时忽略（现有"未知键忽略"已支持）。`SettingsStore.readLegacyPoints()` 保留，直接读原始 DataStore 键，不经过 Codec。

**设置页**：移除 7 个分值输入框，替换为"考核项"列表（RecyclerView，非滚动，嵌在现有 ScrollView 中）：
- 每行：名称、分值（带正负号）、上移、下移、停用/启用开关、删除
- 点击行打开编辑对话框：名称、分值（允许负数）、孩子可申报、互斥组（文本，可空）
- 底部"新增考核项"：key 自动生成为 `CUSTOM_<时间戳>`
- 删除被引用的项时，提示"该项有历史记录，只能停用"
- 修改分值保存时提示一次："修改分值会重新计算所有历史周的分数，银行余额不受影响"
- `isComplaint` 不在界面暴露

**提交**：`feat: 设置页支持考核项增删改与排序`

---

### Task 5：导出 v2

**文件**
- 改：`data/DataExporter.kt`、`data/ScreenTimeRepository.kt`（导出取数）、`ui/SettingsViewModel.kt`
- 测：`data/DataExporterTest.kt`

**格式**：方案第 4 节，字段一个不少。`days` 不输出 `weekStart`（可推导），`weeks` 必须输出 `weekStart`、`prevBankMinutes`、`prevSavingCents`、`savingAdjustCents`，`exams` 必须输出 `weekStart`。导出取数改为直接读实体（`WeekEntity`、`ExamEntity`），不再经过丢字段的 domain 映射。

**必须新增的测试**
- 顶层含 `schemaVersion == 2` 与 `itemDefs`
- `weeks[0]` 含上述全部字段且值正确
- `exams[0]` 含 `weekStart`

**提交**：`feat: 导出格式升级为 v2，补齐周记录主键与考核项定义`

---

### Task 6：导入（v2 + v1 兼容）

**文件**
- 建：`data/DataImporter.kt`（纯解析与校验，返回 `ImportPlan` 或错误）、`data/ImportPlan.kt`
- 改：`data/ScreenTimeRepository.kt`（`applyImport(plan)`，单事务）、`data/AppDatabase.kt`（如需暴露事务）、`ui/SettingsFragment.kt`、`ui/SettingsViewModel.kt`、`res/layout/fragment_settings.xml`
- 测：新建 `data/DataImporterTest.kt`；测试资源 `app/src/test/resources/screen-time-v1.json`

**测试资源**：复制 `docs/superpowers/1.1/screen-time-20261028.json`，**把 `childName` 改为 `"测试"`**，其余不动。不要直接引用 docs 下的原文件。

**ImportPlan**
```kotlin
data class ImportPlan(
    val schemaVersion: Int,
    val childName: String,
    val config: RuleConfig,
    val itemDefs: List<ItemDef>,
    val days: List<DayEntity>,
    val weeks: List<WeekEntity>,
    val exams: List<ExamEntity>,
    val warnings: List<String>      // 例如"规则参数将被覆盖"、"v1 文件"
)
sealed class ImportResult { data class Ok(val plan: ImportPlan) ; data class Error(val reason: String) }
object DataImporter { fun parse(json: String): ImportResult }
```

`DataImporter` 只做解析与校验，不碰数据库，便于单测。

**v1 规则**：方案 5.2，逐条实现。周记录的 `prevBankMinutes` / `prevSavingCents` 按周升序重放计算：第一周为 0；之后每周取上一周 `WeekBoard.bank` 的 `bankBalanceMinutes` / `savingCents`（用导入后的 config、目录、该周日记录、该周周记录、空考试列表构造），与 `ensureWeekRow` 算法一致。v1 的 `itemDefs` 用 `ItemCatalog.builtin(文件 config 中的 7 个旧分值键)`。

**applyImport**：一个 Room 事务内：清空 `day_record`、`week_meta`、`exam_record`、`item_def`，写入 plan 的全部行；事务成功后再写 DataStore（config、childName）。事务失败抛出，界面提示，数据不变。

**界面**：设置页"导入数据"按钮 → `ACTION_OPEN_DOCUMENT`（`application/json`、`*/*`）→ 读文本 → `parse` → 失败弹出原因；成功弹确认框，显示版本、孩子姓名、天数、周数、考试条数、警告，明示"将覆盖全部现有数据和规则参数"→ 确认后 `applyImport`。

**必须新增的测试**
- v1 资源文件：解析成功；24 天、4 周、0 考试；4 条周记录的 `weekStart` 依次为 20731、20738、20745、20752；第一周 `overtimeMinutes == 180`、`noteGood == "作业积极，效率高"`
- v1 资源文件：`config.savingCapCents == 5000`，`usageStepMinutes` 为默认 15
- v1 资源文件：`itemDefs` 为 11 条
- v1 资源文件：用 plan 构造 4 周的 `WeekBoard`，第二周起 `prevBankMinutes` 等于上一周 `bank.bankBalanceMinutes`
- v1 周记录条数与日记录周数不一致 → `Error`
- v1 含非空 `exams` → `Error`
- 非法 JSON → `Error`；`schemaVersion` 大于 2 → `Error`
- v2 往返：用 Task 5 的导出函数导出一份构造数据 → `parse` → 各字段与原数据一致

**提交**：`feat: 支持导入数据（v2 完整还原，兼容 v1）`

---

## 完成标准

1. `./gradlew :app:testDebugUnitTest :app:assembleRelease` 通过
2. `domain/` 无 `android.*` import；manifest 无 `<uses-permission>`
3. 用 v1 资源文件导入后，4 周的每日小计与周合计与导入前一致（Task 6 测试覆盖）
4. 真机验证清单（用户执行）：11 列横向滚动与对齐、互斥勾选、孩子模式下扣分项不可点、考核项增删改排序、导出 → 清空 → 导入后数据一致
