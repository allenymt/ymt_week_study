# 1.1 版本技术方案

日期：2026-10-29
依据：`prd.md`、历史数据 `screen-time-20261028.json`
状态：决策已确认，待评审

## 1. 范围

| 需求 | 本方案处理方式 |
|---|---|
| 新增 4 个数据项 | 作为动态考核项的初始数据，不写死在代码里 |
| 支持动态增减考核项（额外评估） | 本次实现，是本次迭代的主体 |
| 支持导入数据 | 导出格式升级为 v2，同时兼容导入现有 v1 文件 |

实施顺序：**动态考核项 → 导出 v2 → 导入**。反过来做的话导出格式要改两次，备份文件会出现三种版本。

## 2. 已确认的决策

| # | 问题 | 决策 |
|---|---|---|
| 1 | 导入时是否覆盖规则参数 | 覆盖 |
| 2 | 字认真/字不认真、作业积极/作业不积极 | 互斥 |
| 3 | 新扣分项的规则 | 普通每日扣分，每天都能记；不受"每周最多扣 2 次"限制，不影响"一周无投诉"奖励 |
| 4 | 改了分值后历史周怎么办 | 全部重算，不做分值快照 |
| 5 | 导入策略 | 覆盖全部现有数据 |

决策 1 的已知后果：`screen-time-20261028.json` 里的存钱上限是 5000 分（50 元），导入后会覆盖当前的 10000 分（100 元）。导入确认框需要明示这一点，导入后可在设置页改回。

## 3. 动态考核项

### 3.1 数据模型

考核项从 `enum class Item` 改为数据库里的一张表：

```kotlin
@Entity(tableName = "item_def")
data class ItemDefEntity(
    @PrimaryKey val key: String,     // 稳定标识，日记录里存的就是它
    val name: String,                // 界面显示名
    val points: Int,                 // 正数加分，负数扣分
    val sortOrder: Int,              // 表格列顺序
    val enabled: Boolean = true,     // 停用后不在表格显示，历史分数照常计入
    val childClaimable: Boolean,     // 孩子能否申报
    val exclusiveGroup: String? = null, // 同组互斥，同一天只能勾一个
    val isComplaint: Boolean = false    // 投诉专用规则，见 3.3
)
```

### 3.2 初始数据

`key` 直接沿用现有枚举名。日记录里存的本来就是 `RAISED` 这类字符串，所以**历史数据一行都不用改**。

| key | 名称 | 分值 | 孩子可申报 | 互斥组 | 投诉规则 | 来源 |
|---|---|---|---|---|---|---|
| RAISED | 举手回答 | +10 | 是 | — | 否 | 现有 |
| NEAT_WRITING | 字认真 | +10 | 是 | writing | 否 | 现有 |
| NEAT_WRITING_BAD | 字不认真 | −10 | 否 | writing | 否 | 新增 |
| ENGLISH | 主动英语 | +10 | 是 | — | 否 | 现有 |
| LISTENING_PERFECT | 英语听力全对 | +10 | 是 | — | 否 | 新增 |
| READING | 主动阅读 | +15 | 是 | — | 否 | 现有 |
| EXERCISE | 主动运动 | +15 | 是 | — | 否 | 现有 |
| HOMEWORK_ACTIVE | 作业积极 | +10 | 是 | homework | 否 | 新增 |
| HOMEWORK_PASSIVE | 作业不积极 | −10 | 否 | homework | 否 | 新增 |
| PRAISE | 老师表扬 | +10 | 是 | — | 否 | 现有 |
| COMPLAINT | 投诉 | −30 | 否 | — | **是** | 现有 |

现有 7 项的分值取自当前 `RuleConfig`。用户若改过分值，迁移时读取 DataStore 里的值写入，不用默认值。

假设：扣分项默认孩子不能申报（孩子自己报"字不认真"没有意义）。可在设置页逐项修改。

### 3.3 计分规则调整

- 普通项：`items` 里每有一项，当日小计加上该项 `points`（扣分项 `points` 为负）
- 投诉项（`isComplaint = true`）：保留现有两条特殊规则——每周最多计 `complaintMaxPerWeek` 次、有投诉则无"一周无投诉"奖励。判断条件从 `item == Item.COMPLAINT` 改为 `def.isComplaint`
- 新扣分项都是普通项，不受上面两条约束（决策 3）
- 当日小计、周合计可以为负，不截断（与现在一致）
- 分值改动后，所有历史周按新分值重算（决策 4）。银行余额不受影响，它在跨周时已固化在 `prevBankMinutes` 里

### 3.4 互斥

同一 `exclusiveGroup` 的项，同一天最多一个处于"已确认"或"待确认"状态。

- 家长确认某项时，自动移除同组其他项（无论已确认还是待确认）
- 孩子申报某项时，若同组已有已确认项，不允许申报

规则放在 `ScoreRules.advance` / `claim` 里，纯函数，单测覆盖。

### 3.5 已停用和已删除的项

- **停用**：表格不再显示该列，历史上勾过的仍计分
- **删除**：只允许删除从未被任何日记录引用过的项。被引用过的只能停用，否则历史分数会凭空变化

### 3.6 RuleConfig 收缩

`RuleConfig` 去掉 7 个逐项分值字段（`raisePoints` … `complaintPenalty` 等），由 `item_def` 承担。保留的字段：投诉次数上限、保底、无投诉奖励、各类封顶、兑换、考试奖励、使用时长步长。

`RuleConfigCodec` 解码旧数据时，遇到这些已删除字段只用于第一次迁移，之后忽略（现有"未知键忽略"的逻辑已经支持）。

### 3.7 数据库迁移 v2 → v3

1. 建 `item_def` 表
2. 插入 3.2 的 11 条初始数据，现有 7 项的分值取自当前生效的规则参数
3. `day_record` 不变

Room 的迁移拿不到 DataStore，所以第 2 步放在首次启动时由仓库层完成：检测 `item_def` 为空则写入初始数据。

### 3.8 界面

**本周表格**：列数从 7 列变为 11 列，单格宽度会降到约 25dp，低于 48dp 触控标准。改为**表格横向滚动**，左侧星期列和右侧小计列固定不动，中间考核项区域可左右滑。表头与数据行共用一个横向滚动容器，保证列对齐。

**设置页**：7 个固定分值输入框替换为考核项列表，每行显示名称、分值，可编辑、上下移动、停用、删除，底部"新增考核项"。

**孩子申报模式**：`childClaimable = false` 的列在孩子模式下点击无效。

## 4. 导出格式 v2

```json
{
  "schemaVersion": 2,
  "childName": "...",
  "config": { "...": 0 },
  "itemDefs": [
    { "key": "RAISED", "name": "举手回答", "points": 10, "sortOrder": 0,
      "enabled": true, "childClaimable": true, "exclusiveGroup": null, "isComplaint": false }
  ],
  "days":  [ { "epochDay": 20731, "items": ["RAISED"], "pending": [] } ],
  "weeks": [ { "weekStart": 20731, "usedMinutes": 0, "overtimeMinutes": 0,
               "redeemedMinutes": 0, "redeemedCents": 0, "savingAdjustCents": 0,
               "prevBankMinutes": 0, "prevSavingCents": 0,
               "noteGood": "", "noteImprove": "" } ],
  "exams": [ { "weekStart": 20731, "subject": "MATH", "score": 95 } ]
}
```

相对 v1 补齐的字段：`schemaVersion`、`itemDefs`、`weeks[].weekStart`、`weeks[].savingAdjustCents`、`weeks[].prevBankMinutes`、`weeks[].prevSavingCents`、`exams[].weekStart`。

## 5. 导入

### 5.1 流程

1. 设置页"导入数据"按钮 → `ACTION_OPEN_DOCUMENT` 选择 JSON 文件（无需申请权限）
2. **先完整解析与校验，不碰数据库**。任何一项失败就整体放弃，提示原因
3. 弹确认框：显示文件版本、孩子姓名、天数、周数、考试条数，以及"将覆盖全部现有数据和规则参数"
4. 确认后在**一个 Room 事务**里清空三张记录表与 `item_def`，再写入；规则参数与孩子姓名写入 DataStore
5. 任何一步失败，事务回滚，现有数据保持不变

### 5.2 v1 文件的兼容规则

v1 文件（无 `schemaVersion`）缺少周记录主键等字段，按以下规则还原：

| 数据 | 还原方式 |
|---|---|
| days | 直接导入，`weekStart` 由 `WeekDates.weekStart(epochDay)` 推出 |
| weeks | 按顺序对应日记录覆盖的各周（导出时按 `weekStart` 升序）。**仅当周记录条数等于日记录覆盖的周数时才对应**，否则放弃周数据并提示原因，不去猜 |
| weeks 的上周余额 / 上周存钱 | 按周升序重放一遍，与 `ensureWeekRow` 的算法相同 |
| weeks 的人工存取 | 置 0 |
| exams | 缺 `weekStart` 无法还原。条数为 0 时无影响；非 0 时放弃并提示 |
| itemDefs | 用 3.2 的初始数据，现有 7 项的分值取自文件里的 `config` |
| config 缺失的键 | 用默认值补齐（如 `usageStepMinutes`），现有解码逻辑已支持 |

以 `screen-time-20261028.json` 为例：24 天分布在 4 周，周记录正好 4 条，考试 0 条 —— **可以完整恢复**。

## 6. 测试

| 层 | 测试内容 |
|---|---|
| 计分 | 扣分项计入小计；扣分项不受每周 2 次限制、不影响无投诉奖励；停用项历史仍计分 |
| 互斥 | 家长确认时移除同组项；孩子申报时同组已有确认项则拒绝 |
| 导出 | v2 往返：导出 → 导入 → 再导出，两份 JSON 一致 |
| 导入 v1 | 用 `screen-time-20261028.json` 作为测试资源：导入后 4 周的每日小计、周合计、银行余额与导出前一致 |
| 导入失败 | 文件格式错误、周记录条数对不上、v1 含考试记录 —— 均整体放弃，数据库不变 |
| 迁移 | v2 → v3 后历史分数不变 |

## 7. 风险

1. **规则参数被覆盖**：见第 2 节决策 1 的说明
2. **改分值影响历史**：按决策 4 全部重算，过去各周的汇总数字会变，银行余额不变。设置页修改分值时提示一次
3. **横向滚动的可用性**：需真机确认表头与数据行滚动同步、固定列不错位
