# 每周屏幕时间 App — 设计规格

日期：2026-09-23
状态：设计已确认，待出实施计划

## 1. 目标与定位

把纸质《每周屏幕时间记录表》电子化，做成一个**离线记账 App**。

- **只记账**：记录加分事实、自动算账、管理屏幕银行与存钱。不做系统级屏幕时间管控，不读取手机使用数据，不限制其他 App。
- **简单易懂**：主界面就是纸质表的那一页，家长一眼看懂、一指点中。
- **谁在用**：家长为主要录入者；孩子可以自己申报（待家长确认）。

成功标准：家长能在 30 秒内完成一天的记录；所有数字与纸质表算出来的完全一致；改规则改设置即可，不用改代码。

## 2. 非目标（明确不做）

- 不申请任何敏感权限，不申请网络权限（无账号、无云同步）
- 不做系统级时间管控、不做应用锁
- 不做两套界面（孩子视图/家长视图）——用一张大字余额卡片替代
- 不做多孩子
- 不做统计图表（暂缓，用顺了再说）

## 3. 技术选型与环境

对齐参考工程 `/Users/yulun/TestForGradle`：

| 项 | 值 |
|---|---|
| 语言 | Kotlin 1.6.10 |
| 构建 | Gradle 6.5 + AGP 4.1.3 |
| compileSdk / targetSdk / minSdk | 31 / 31 / 21 |
| UI | ViewBinding + DataBinding（均启用） |
| 持久化 | Room 2.4.2（本地）+ DataStore Preferences 1.0.0（设置项） |
| 异步 | Kotlin 协程 + Flow |
| 架构 | 单 Activity + 多 Fragment，底部导航三 Tab |

不引入 Retrofit / OkHttp / Fastjson 等网络与序列化库——本 App 无网络需求。

## 4. 分层架构

```
UI 层           WeekTableFragment / SummaryFragment / SettingsFragment
                │  只渲染 ViewModel 暴露的 UiState，只发出用户意图
ViewModel 层    WeekViewModel / SummaryViewModel / SettingsViewModel
                │  意图 → 仓库写入；不做算术
Domain 层       ScoreRules / WeekCalculator / BankCalculator
                │  纯 Kotlin 纯函数，不依赖 Android SDK
Data 层         Room (DayRecord / WeekMeta / ExamRecord)
                DataStore (规则参数、孩子姓名)
```

**分层硬约束**

1. Domain 层是纯 Kotlin，无 Android 依赖，可在 JVM 上直接单元测试。
2. UI 层不做任何算术，所有派生数字来自 ViewModel 暴露的 UiState。
3. 只存事实，不存派生值。分数、余额、封顶后的结果全部实时计算。

## 5. 数据模型

### 5.1 实体

```kotlin
enum class Item { RAISED, NEAT_WRITING, ENGLISH, READING, EXERCISE, COMPLAINT }
enum class Subject { ENGLISH, CHINESE, MATH, SCIENCE }

// 一天一条
@Entity data class DayRecord(
    @PrimaryKey val epochDay: Long,        // 2026-09-23
    val weekStart: Long,                   // 所属周的周一，冗余存储便于分周查询
    val items: Set<Item> = emptySet(),     // 家长已确认的项
    val pending: Set<Item> = emptySet()    // 孩子申报、待家长确认的项
)

// 一周一条
@Entity data class WeekMeta(
    @PrimaryKey val weekStart: Long,
    val usedMinutes: Int = 0,              // 本周已使用（事实，必须存）
    val overtimeMinutes: Int = 0,          // 超时惩罚（事实，1:1 扣减）
    val redeemedCents: Int = 0,            // 本周已兑换金额
    val redeemedMinutes: Int = 0,          // 本周已兑换消耗的额度
    val noteGood: String = "",
    val noteImprove: String = ""
)

// 考试：一条一科
@Entity data class ExamRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,
    val subject: Subject,
    val score: Int
)
```

### 5.2 三态流转（本 App 唯一有状态的交互）

同一个格子在不同人手里有三种状态：

| 状态 | 触发 | 显示 | 计分 |
|---|---|---|---|
| 空 | 初始 | 空方框 | 否 |
| 待确认 | 孩子点击 | 方框 + "待确认"标记 | **否** |
| 已确认 | 家长点击 | 实心勾 | 是 |

- 孩子点击：`Item` 加入 `pending`（已确认的项不可再由孩子操作）
- 家长点击：`Item` 从 `pending` 移入 `items`（若已在 `items` 则移出，即取消）
- 家长长按：`Item` 从 `pending` 移除（退回）

孩子的操作永远不会直接产生分数。

### 5.3 规则参数（DataStore）

分值、封顶值全部可配置，不硬编码：

```kotlin
data class RuleConfig(
    val raisePoints: Int = 10,
    val neatWritingPoints: Int = 10,
    val englishPoints: Int = 10,
    val readingPoints: Int = 15,
    val exercisePoints: Int = 15,
    val complaintPenalty: Int = 30,
    val complaintMaxPerWeek: Int = 2,      // 每周最多计几次投诉
    val baseMinutes: Int = 60,             // 保底（无条件给）
    val noComplaintBonusMinutes: Int = 60,
    val weekCapMinutes: Int = 240,         // 每周封顶 4 小时
    val bankCapMinutes: Int = 480,         // 银行累计上限 8 小时
    val redeemUnitMinutes: Int = 30,
    val redeemUnitCents: Int = 200,        // 每 30 分钟 = 2 元
    val weeklyRedeemCapCents: Int = 1000,  // 每周最多 10 元
    val savingCapCents: Int = 5000,        // 存钱上限 50 元
    val englishMidMin: Int = 85,
    val englishMidBonus: Int = 15,
    val englishHighMin: Int = 90,
    val englishHighBonus: Int = 30,
    val otherSubjectMin: Int = 90,
    val otherSubjectBonus: Int = 30
)
```

## 6. 计算规则（Domain 层，单位统一为分钟）

### 6.1 每日小计

```
每日小计 = 举手 + 字认真 + 主动英语 + 主动阅读 + 主动运动 − 投诉扣分
```

- 负值保留，不截断为 0（真实反映当天）
- **投诉扣分按周统计**：该周前 `complaintMaxPerWeek`（默认 2）次投诉各扣 `complaintPenalty`（30），第 3 次起不再扣分

### 6.2 考试奖励

```
英语：  85 ≤ 分数 ≤ 89 → +15      分数 ≥ 90 → +30      < 85 → 0
语文/数学/科学： 分数 ≥ 90 → +30    85 ≤ 分数 ≤ 89 → 0   < 85 → 0
```

### 6.3 本周汇总

```
本周总赚取 = baseMinutes (60，无条件)
          + noComplaintBonus (60，仅当 7 天全部无投诉)
          + 每日加分合计 (可为负)
          + 考试奖励合计

每周封顶 = weekCapMinutes (240)
overflow = max(0, 本周总赚取 − weekCapMinutes)   // 超出部分不累计，可换小奖品
本周结余 = 本周总赚取 − 本周已使用 − 超时惩罚 − 本周已兑换消耗额度
```

### 6.4 屏幕银行

```
新银行累计      = min(上周银行结余 + max(0, 本周结余), bankCapMinutes /*480*/)
超出 8 小时部分 = max(0, 上周银行结余 + max(0, 本周结余) − bankCapMinutes)
兑换金额       = (兑换分钟数 / redeemUnitMinutes) × redeemUnitCents
                 受 weeklyRedeemCapCents (1000) 限制
存钱累计       = min(上周存钱累计 + 本周兑换金额, savingCapCents /*5000*/)
```

**兑换扣的是分钟额度，不是扣钱。** "银行余额"（分钟）与"存钱累计"（钱）是两本独立的账。家长给钱，App 只记账。

### 6.5 超时惩罚

超时按实际超出的分钟数 1:1 扣减下周可用额度，记在 `WeekMeta.overtimeMinutes`。

### 6.6 两条容易混淆的量，代码里必须分开命名

| 名称 | 含义 | 来源 |
|---|---|---|
| `overflow` | **单周**赚超 240 分钟的部分 | 6.3 |
| `bankOverflow` | **银行累计**超 480 分钟的部分 | 6.4 |

纸质表第四部分"超出 8 小时部分 ___ 分钟"对应的是 `bankOverflow`。

## 7. 界面设计

底部导航三个 Tab。

### 7.1 Tab 1 — 本周（主界面）

- 顶部：周次与日期范围，本周总赚取 / 结余的实时数字
- 中部：**7 行 × 6 列可点勾选格**（周一…周日 × 举手/字认真/英语/阅读/运动/投诉），与纸质表一一对应
  - 空格＝空方框；孩子申报＝方框 + "待确认"；已确认＝实心勾
  - 每行右端显示当日小计
- 底部：汇总卡片（保底 / 无投诉奖 / 加分合计 / 考试奖励 / 投诉扣分 / 总赚取 / 已使用 / 结余 / 封顶提示）
- 操作：左右滑动切换上一周/下一周；点击行首展开该天的考试录入

### 7.2 Tab 2 — 银行

- 大字余额卡片：当前可用余额（分钟 / 小时）
- 银行累计、超出 8 小时部分、存钱累计、本周兑换额度剩余
- "兑换"按钮：选择分钟数 → 显示可得金额 → 确认写入 `WeekMeta`

### 7.3 Tab 3 — 设置

- 孩子姓名
- 全部 `RuleConfig` 参数，分组可编辑
- 导出数据为 JSON / 清空所有数据（二次确认）
- 本周一句话总结（做得好的 / 下周改进）录入

## 8. 测试策略

| 层 | 方式 | 重点 |
|---|---|---|
| Domain | JVM 单元测试（JUnit 4.12） | 边界值：投诉第 2 次与第 3 次的差异、封顶 240 的临界、银行 480 截断、英语 84/85/89/90 四个分界、兑换 10 元上限 |
| Data | Room in-memory 测试 | 分周查询、`pending` 三态流转、周切换 |
| UI | 手工验证 | 一屏能否放下 7×6 表格、误触保护、大字可读性 |

Domain 层是纯函数，测试覆盖必须先做——它是全 App 唯一出错就会算错账的地方。

## 9. 风险与取舍

1. **纸质表只有一页，App 一屏放不下 7×6 + 汇总。** 取舍：主界面优先保证表格完整，汇总做成可折叠卡片。
2. **孩子误触。** 已用三态流转隔离——孩子的操作不产生分数，家长确认才生效。
3. **多孩子、跨设备同步**明确不做，换设备靠导出 JSON。
4. **规则可配置带来复杂度。** 取舍：只把数值做成配置项，规则结构（哪些项、怎么算）保持硬编码。
