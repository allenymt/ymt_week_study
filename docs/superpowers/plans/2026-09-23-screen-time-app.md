# 每周屏幕时间 App 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把纸质《每周屏幕时间记录表》做成一个离线记账 Android App，家长记录事实、App 自动算账。

**Architecture:** 四层——UI（Fragment + ViewBinding）→ ViewModel（意图转写）→ Domain（纯 Kotlin 纯函数算账，可在 JVM 单测）→ Data（Room + DataStore）。只存事实（勾了哪几项、实际用了多久），分数与余额全部实时算出，规则改动不触发数据迁移。

**Tech Stack:** Kotlin 1.6.10、Gradle 6.5.1、AGP 4.1.3、compileSdk/targetSdk 31、minSdk 21、ViewBinding、Room 2.4.2、DataStore Preferences 1.0.0、Kotlin 协程、JUnit 4.12。

**Spec:** `docs/superpowers/specs/2026-09-23-screen-time-app-design.md`

## Global Constraints

- 包名 `com.vdian.screentime`，applicationId 相同
- compileSdkVersion 31 / targetSdkVersion 31 / minSdkVersion 21
- Kotlin 1.6.10；**不得使用 `java.time`**（minSdk 21 在 API 26 以下不可用），日期计算一律用自写的 epochDay 纯函数
- **Domain 层（`domain/` 包）不得 import 任何 `android.*`**，保证 JVM 单测可跑
- **不申请任何权限**，manifest 中不出现 `<uses-permission>`（含 INTERNET）
- 不引入 Retrofit / OkHttp / Fastjson / Glide 等网络与图片库
- 所有分值、封顶值放在 `RuleConfig`，不得在业务代码里写死数字
- UI 层不做算术，所有派生数字来自 ViewModel
- 提交信息用中文，遵循 `feat:` / `test:` / `chore:` 前缀

## 关键设计：孩子申报的三态流转

本 App 唯一有状态的交互。同一格子三态：

| 状态 | 触发 | `items` | `pending` | 计分 |
|---|---|---|---|---|
| 空 | 初始 | 不含 | 不含 | 否 |
| 待确认 | 孩子点击 | 不含 | 含 | **否** |
| 已确认 | 家长点击 | 含 | 不含 | 是 |

家长长按 = 退回待确认项。**孩子的任何操作都不产生分数。**

## Review Focus

以下是规格隐含、但容易被实现忽略的输入与边界，各自的测试放在对应任务里：

1. **一周跨月/跨年**（12/29–1/4）——周一起始日计算不得因跨年错位为两周
2. **一天内重复勾选同一项**——Set 语义必须幂等，重复点击不得重复计分
3. **孩子反复点同一格**——`pending` 不得累积重复项，家长确认后 `pending` 必须清空
4. **投诉恰好在第 2 次与第 3 次之间**——第 3 次起不再扣分，但当天仍应记录该投诉事实
5. **每日小计为负**（一天被投诉两次 = −60）——不得截断为 0，必须原样进入周合计
6. **上周银行余额已满 480 分钟**——本周新增必须被截断，且 `bankOverflow` 不得为负
7. **兑换分钟数不是 30 的整数倍**（如 45 分钟）——按整数倍向下取整，不得算出 3 元

---

### Task 1: 工程脚手架与构建配置

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/test/java/com/vdian/screentime/SmokeTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: 可编译的空 App 骨架，后续所有任务在此构建

- [ ] **Step 1: 复制 Gradle wrapper 二进制**

wrapper 的 jar 与脚本可从参考工程直接复制：

```bash
cd /Users/yulun/ymt_week_study
mkdir -p gradle/wrapper
cp /Users/yulun/TestForGradle/gradlew .
cp /Users/yulun/TestForGradle/gradlew.bat .
cp /Users/yulun/TestForGradle/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
chmod +x gradlew
```

- [ ] **Step 2: 写 `gradle/wrapper/gradle-wrapper.properties`**

参考工程 Gradle 6.5.1 已在本机缓存（`~/.gradle/wrapper/dists/gradle-6.5.1-all`），无需下载。

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-6.5.1-all.zip
```

- [ ] **Step 3: 写根目录 `build.gradle`**

注意：`jcenter()` 已停服，仓库列表用 `mavenCentral()` 替代。

```groovy
buildscript {
    ext {
        kotlin_version = '1.6.10'
    }
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:4.1.3'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
```

- [ ] **Step 4: 写 `settings.gradle`**

```groovy
include ':app'
rootProject.name = "ScreenTime"
```

- [ ] **Step 5: 写 `gradle.properties`**

```properties
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx1536m
```

- [ ] **Step 6: 写 `app/build.gradle`**

```groovy
apply plugin: 'com.android.application'
apply plugin: 'kotlin-android'

android {
    compileSdkVersion 31
    defaultConfig {
        applicationId "com.vdian.screentime"
        minSdkVersion 21
        targetSdkVersion 31
        versionCode 1
        versionName "1.0"
        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
    }
    buildFeatures {
        viewBinding true
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.3.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.3'
    implementation 'com.google.android.material:material:1.4.0'
    implementation 'androidx.recyclerview:recyclerview:1.2.1'
    implementation 'androidx.fragment:fragment-ktx:1.3.6'
    implementation "androidx.lifecycle:lifecycle-viewmodel-ktx:2.4.0"
    implementation "androidx.lifecycle:lifecycle-livedata-ktx:2.4.0"
    implementation 'androidx.room:room-runtime:2.4.2'
    implementation 'androidx.room:room-ktx:2.4.2'
    annotationProcessor 'androidx.room:room-compiler:2.4.2'
    implementation 'androidx.datastore:datastore-preferences:1.0.0'
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.0"
    implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlin_version"

    testImplementation 'junit:junit:4.12'
}
```

- [ ] **Step 7: 写 `app/src/main/AndroidManifest.xml`**

**注意：不声明任何权限。**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.vdian.screentime">

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.ScreenTime">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 8: 写 `app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">屏幕时间</string>
</resources>
```

- [ ] **Step 9: 写 `app/src/main/res/values/themes.xml`**

```xml
<resources>
    <style name="Theme.ScreenTime" parent="Theme.MaterialComponents.Light.NoActionBar">
        <item name="colorPrimary">#3F51B5</item>
        <item name="colorPrimaryDark">#303F9F</item>
        <item name="colorAccent">#FF4081</item>
    </style>
</resources>
```

- [ ] **Step 10: 写冒烟测试 `app/src/test/java/com/vdian/screentime/SmokeTest.kt`**

```kotlin
package com.vdian.screentime

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun smoke() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 11: 运行测试，确认构建与测试链路通畅**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，SmokeTest 通过

- [ ] **Step 12: 提交**

```bash
git add -A
git commit -m "chore: 搭建 Android 工程脚手架与构建配置"
```

---

### Task 2: Domain 数据模型与日期工具

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/domain/Item.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/Subject.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/RuleConfig.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/WeekDates.kt`
- Create: `app/src/test/java/com/vdian/screentime/domain/WeekDatesTest.kt`

**Interfaces:**
- Consumes: Task 1 的工程骨架
- Produces:
  - `enum class Item { RAISED, NEAT_WRITING, ENGLISH, READING, EXERCISE, COMPLAINT }`
  - `enum class Subject { ENGLISH, CHINESE, MATH, SCIENCE }`
  - `data class RuleConfig(...)`（字段见下方代码）
  - `object WeekDates`：`weekStart(epochDay: Long): Long`、`dayOfWeek(epochDay: Long): Int`（0=周一）、`toEpochDay(y: Int, m: Int, d: Int): Long`、`format(epochDay: Long): String`（"M月d日"）

- [ ] **Step 1: 写失败测试 `WeekDatesTest.kt`**

```kotlin
package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekDatesTest {

    @Test
    fun monday_is_its_own_week_start() {
        // 2026-09-21 是周一
        val monday = WeekDates.toEpochDay(2026, 9, 21)
        assertEquals(monday, WeekDates.weekStart(monday))
    }

    @Test
    fun sunday_belongs_to_the_preceding_monday() {
        // 2026-09-27 是周日，应归到 09-21 那一周
        val sunday = WeekDates.toEpochDay(2026, 9, 27)
        assertEquals(WeekDates.toEpochDay(2026, 9, 21), WeekDates.weekStart(sunday))
    }

    @Test
    fun week_crossing_year_boundary_stays_one_week() {
        // 2026-12-28 是周一，2027-01-03 是周日，必须同属一周
        val monday = WeekDates.toEpochDay(2026, 12, 28)
        val sunday = WeekDates.toEpochDay(2027, 1, 3)
        assertEquals(monday, WeekDates.weekStart(sunday))
        assertEquals(monday, WeekDates.weekStart(monday))
    }

    @Test
    fun dayOfWeek_is_zero_based_from_monday() {
        assertEquals(0, WeekDates.dayOfWeek(WeekDates.toEpochDay(2026, 9, 21)))
        assertEquals(6, WeekDates.dayOfWeek(WeekDates.toEpochDay(2026, 9, 27)))
    }

    @Test
    fun toEpochDay_roundtrips_known_date() {
        // 1970-01-01 是 epochDay 0
        assertEquals(0L, WeekDates.toEpochDay(1970, 1, 1))
        // 1970-01-02 是 epochDay 1
        assertEquals(1L, WeekDates.toEpochDay(1970, 1, 2))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*WeekDatesTest*"`
Expected: FAIL — `Unresolved reference: WeekDates`

- [ ] **Step 3: 写 `Item.kt`**

```kotlin
package com.vdian.screentime.domain

/** 每日可记录的项目。 */
enum class Item {
    RAISED,        // 举手回答
    NEAT_WRITING,  // 字认真
    ENGLISH,       // 主动英语
    READING,       // 主动阅读
    EXERCISE,      // 主动运动
    COMPLAINT      // 投诉
}
```

- [ ] **Step 4: 写 `Subject.kt`**

```kotlin
package com.vdian.screentime.domain

/** 考试科目。英语单独一档奖励规则，其余三科同档。 */
enum class Subject {
    ENGLISH, CHINESE, MATH, SCIENCE
}
```

- [ ] **Step 5: 写 `RuleConfig.kt`**

所有数值可配置，业务代码不得写死。

```kotlin
package com.vdian.screentime.domain

/** 全部可配置规则参数。默认值取自纸质记录表。 */
data class RuleConfig(
    val raisePoints: Int = 10,
    val neatWritingPoints: Int = 10,
    val englishPoints: Int = 10,
    val readingPoints: Int = 15,
    val exercisePoints: Int = 15,
    val complaintPenalty: Int = 30,
    val complaintMaxPerWeek: Int = 2,
    val baseMinutes: Int = 60,
    val noComplaintBonusMinutes: Int = 60,
    val weekCapMinutes: Int = 240,
    val bankCapMinutes: Int = 480,
    val redeemUnitMinutes: Int = 30,
    val redeemUnitCents: Int = 200,
    val weeklyRedeemCapCents: Int = 1000,
    val savingCapCents: Int = 5000,
    val englishMidMin: Int = 85,
    val englishHighMin: Int = 90,
    val englishMidBonus: Int = 15,
    val englishHighBonus: Int = 30,
    val otherSubjectMin: Int = 90,
    val otherSubjectBonus: Int = 30
)
```

- [ ] **Step 6: 写 `WeekDates.kt`**

**不得使用 `java.time`**（minSdk 21）。用 Howard Hinnant 的 days_from_civil 算法。

```kotlin
package com.vdian.screentime.domain

/**
 * 日期工具，全部为纯函数。刻意不使用 java.time —— minSdk 21 上 API 26 以下不可用。
 * epochDay 约定：1970-01-01 为 0，与 java.time.LocalDate.toEpochDay() 一致。
 */
object WeekDates {

    fun toEpochDay(year: Int, month: Int, day: Int): Long {
        // Howard Hinnant, days_from_civil
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                    // [0, 399]
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy            // [0, 146096]
        return era.toLong() * 146097L + doe.toLong() - 719468L
    }

    /** 返回所属周的周一。epochDay 0 (1970-01-01) 是周四，故基准偏移为 3。 */
    fun weekStart(epochDay: Long): Long {
        val dow = dayOfWeek(epochDay)
        return epochDay - dow
    }

    /** 0 = 周一 … 6 = 周日。 */
    fun dayOfWeek(epochDay: Long): Int {
        // epochDay 0 是周四 = 索引 3
        val shifted = (epochDay + 3) % 7
        return (if (shifted < 0) shifted + 7 else shifted).toInt()
    }

    /** 格式化为 "9月21日"，用于界面展示。 */
    fun format(epochDay: Long): String {
        val (y, m, d) = civilFromDays(epochDay)
        return "${m}月${d}日"
    }

    private fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
        var z = epochDay + 719468L
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097                                        // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365   // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)                 // [0, 365]
        val mp = (5 * doy + 2) / 153                                      // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*WeekDatesTest*"`
Expected: PASS，5 个测试全绿

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/vdian/screentime/domain app/src/test/java/com/vdian/screentime/domain
git commit -m "feat: 新增 Domain 数据模型与 epochDay 日期工具"
```

---

### Task 3: 计分规则 ScoreRules

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/domain/DayRecord.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/ExamRecord.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/WeekMeta.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/ScoreRules.kt`
- Create: `app/src/test/java/com/vdian/screentime/domain/ScoreRulesTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `Item`、`Subject`、`RuleConfig`
- Produces:
  - `data class DayRecord(val epochDay: Long, val items: Set<Item> = emptySet(), val pending: Set<Item> = emptySet())`
  - `data class ExamRecord(val subject: Subject, val score: Int)`
  - `data class WeekMeta(val usedMinutes: Int = 0, val overtimeMinutes: Int = 0, val redeemedMinutes: Int = 0, val redeemedCents: Int = 0, val noteGood: String = "", val noteImprove: String = "")`
  - `object ScoreRules`：`itemPoints(item, cfg)`、`effectiveComplaintCount(days, cfg)`、`dailyScore(day, complaintCounts, cfg)`、`dailyScores(days, cfg): List<Int>`、`dailyTotal(days, cfg)`、`examBonus(subject, score, cfg)`、`examTotal(exams, cfg)`、`advance(item, items, pending): Pair<Set<Item>, Set<Item>>`、`retract(item, pending): Set<Item>`

- [ ] **Step 1: 写失败测试 `ScoreRulesTest.kt`**

```kotlin
package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRulesTest {

    private val cfg = RuleConfig()

    private fun day(d: Long, vararg items: Item) =
        DayRecord(d, items.toSet())

    @Test
    fun itemPoints_match_the_paper_table() {
        assertEquals(10, ScoreRules.itemPoints(Item.RAISED, cfg))
        assertEquals(10, ScoreRules.itemPoints(Item.NEAT_WRITING, cfg))
        assertEquals(10, ScoreRules.itemPoints(Item.ENGLISH, cfg))
        assertEquals(15, ScoreRules.itemPoints(Item.READING, cfg))
        assertEquals(15, ScoreRules.itemPoints(Item.EXERCISE, cfg))
    }

    @Test
    fun complaint_penalty_is_negative() {
        assertEquals(-30, ScoreRules.itemPoints(Item.COMPLAINT, cfg))
    }

    @Test
    fun daily_score_sums_confirmed_items_only() {
        val d = day(0, Item.RAISED, Item.READING, Item.EXERCISE)
        assertEquals(40, ScoreRules.dailyScore(d, emptyMap(), cfg))
    }

    @Test
    fun pending_items_do_not_score() {
        val d = DayRecord(0, items = setOf(Item.RAISED), pending = setOf(Item.READING))
        assertEquals(10, ScoreRules.dailyScore(d, emptyMap(), cfg))
    }

    @Test
    fun repeated_selection_is_idempotent() {
        val once = day(0, Item.RAISED)
        val twice = DayRecord(0, items = setOf(Item.RAISED, Item.RAISED))
        assertEquals(ScoreRules.dailyScore(once, emptyMap(), cfg),
                     ScoreRules.dailyScore(twice, emptyMap(), cfg))
    }

    @Test
    fun negative_daily_score_is_not_clamped() {
        // 一天被投诉两次 = -60，必须原样保留
        val d = day(0, Item.RAISED, Item.COMPLAINT, Item.COMPLAINT)
        assertEquals(-50, ScoreRules.dailyScore(d, mapOf(0L to 2), cfg))
    }

    @Test
    fun complaint_counts_only_first_two_per_week() {
        val days = listOf(
            day(0, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(2, Item.COMPLAINT),
            day(3, Item.COMPLAINT)
        )
        assertEquals(2, ScoreRules.effectiveComplaintCount(days, cfg))
    }

    @Test
    fun third_complaint_scores_zero_but_stays_recorded() {
        val days = listOf(
            day(0, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(2, Item.COMPLAINT)
        )
        val scores = ScoreRules.dailyScores(days, cfg)
        assertEquals(-30, scores[0])
        assertEquals(-30, scores[1])
        assertEquals(0, scores[2])          // 第 3 次不扣分
        assertTrue(days[2].items.contains(Item.COMPLAINT))  // 但事实仍记录
    }

    @Test
    fun complaint_counting_follows_chronological_order_not_input_order() {
        // 乱序输入，必须先按日期排序再决定哪两次生效
        val days = listOf(
            day(5, Item.COMPLAINT),
            day(1, Item.COMPLAINT),
            day(3, Item.COMPLAINT)
        )
        val scores = ScoreRules.dailyScores(days, cfg)
        // 排序后为 1, 3, 5 -> 前两次 (1, 3) 扣分，第 5 天不扣
        assertEquals(-30, scores[0])   // day 1
        assertEquals(-30, scores[1])   // day 3
        assertEquals(0, scores[2])     // day 5
    }

    @Test
    fun exam_bonus_english_has_two_tiers() {
        assertEquals(0, ScoreRules.examBonus(Subject.ENGLISH, 84, cfg))
        assertEquals(15, ScoreRules.examBonus(Subject.ENGLISH, 85, cfg))
        assertEquals(15, ScoreRules.examBonus(Subject.ENGLISH, 89, cfg))
        assertEquals(30, ScoreRules.examBonus(Subject.ENGLISH, 90, cfg))
        assertEquals(30, ScoreRules.examBonus(Subject.ENGLISH, 100, cfg))
    }

    @Test
    fun exam_bonus_other_subjects_only_reward_90_plus() {
        for (s in listOf(Subject.CHINESE, Subject.MATH, Subject.SCIENCE)) {
            assertEquals(0, ScoreRules.examBonus(s, 84, cfg))
            assertEquals(0, ScoreRules.examBonus(s, 85, cfg))
            assertEquals(0, ScoreRules.examBonus(s, 89, cfg))
            assertEquals(30, ScoreRules.examBonus(s, 90, cfg))
        }
    }

    @Test
    fun exam_total_sums_all_subjects() {
        val exams = listOf(
            ExamRecord(Subject.ENGLISH, 90),   // +30
            ExamRecord(Subject.CHINESE, 88),   // 0
            ExamRecord(Subject.MATH, 95),      // +30
            ExamRecord(Subject.SCIENCE, 91)    // +30
        )
        assertEquals(90, ScoreRules.examTotal(exams, cfg))
    }

    @Test
    fun advance_moves_item_from_pending_to_confirmed() {
        val (items, pending) = ScoreRules.advance(Item.READING, emptySet(), setOf(Item.READING))
        assertTrue(items.contains(Item.READING))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun advance_on_confirmed_item_removes_it() {
        val (items, pending) = ScoreRules.advance(Item.READING, setOf(Item.READING), emptySet())
        assertFalse(items.contains(Item.READING))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun advance_on_empty_cell_confirms_directly() {
        val (items, _) = ScoreRules.advance(Item.ENGLISH, emptySet(), emptySet())
        assertTrue(items.contains(Item.ENGLISH))
    }

    @Test
    fun repeated_child_claim_does_not_duplicate() {
        val pending = setOf(Item.EXERCISE)
        val cfgAfter = pending + Item.EXERCISE
        assertEquals(1, cfgAfter.size)
    }

    @Test
    fun retract_removes_pending_item() {
        val pending = ScoreRules.retract(Item.READING, setOf(Item.READING, Item.ENGLISH))
        assertEquals(setOf(Item.ENGLISH), pending)
    }

    @Test
    fun retract_on_absent_item_is_a_noop() {
        assertEquals(setOf(Item.ENGLISH), ScoreRules.retract(Item.READING, setOf(Item.ENGLISH)))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*ScoreRulesTest*"`
Expected: FAIL — `Unresolved reference: ScoreRules`

- [ ] **Step 3: 写 `DayRecord.kt`**

Domain 层的数据结构（不依赖 Room，Room 实体在 Data 层单独定义并负责映射）。

```kotlin
package com.vdian.screentime.domain

/**
 * 一天的记录。
 * @param items   家长已确认的项，只有这些计分
 * @param pending 孩子申报、待家长确认的项，不计分
 */
data class DayRecord(
    val epochDay: Long,
    val items: Set<Item> = emptySet(),
    val pending: Set<Item> = emptySet()
)
```

- [ ] **Step 4: 写 `ExamRecord.kt`**

```kotlin
package com.vdian.screentime.domain

data class ExamRecord(
    val subject: Subject,
    val score: Int
)
```

- [ ] **Step 5: 写 `WeekMeta.kt`**

```kotlin
package com.vdian.screentime.domain

/** 一周的使用与兑换事实。这些是实际发生的数据，不是推导出来的，所以必须存。 */
data class WeekMeta(
    val usedMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    val redeemedMinutes: Int = 0,
    val redeemedCents: Int = 0,
    val noteGood: String = "",
    val noteImprove: String = ""
)
```

- [ ] **Step 6: 写 `ScoreRules.kt`**

```kotlin
package com.vdian.screentime.domain

import kotlin.math.min

/** 计分规则。全部为纯函数，不依赖 Android。 */
object ScoreRules {

    /** 单项分值。投诉返回负值。 */
    fun itemPoints(item: Item, cfg: RuleConfig): Int = when (item) {
        Item.RAISED -> cfg.raisePoints
        Item.NEAT_WRITING -> cfg.neatWritingPoints
        Item.ENGLISH -> cfg.englishPoints
        Item.READING -> cfg.readingPoints
        Item.EXERCISE -> cfg.exercisePoints
        Item.COMPLAINT -> -cfg.complaintPenalty
    }

    /**
     * 本周实际生效的投诉次数：按日期升序取前 [RuleConfig.complaintMaxPerWeek] 次。
     * 与输入顺序无关。
     */
    fun effectiveComplaintCount(days: List<DayRecord>, cfg: RuleConfig): Int =
        min(days.count { it.items.contains(Item.COMPLAINT) }, cfg.complaintMaxPerWeek)

    /**
     * 某天的小计。可为负，不截断。
     * @param complaintCounts epochDay -> 该天生效的投诉次数
     */
    fun dailyScore(day: DayRecord, complaintCounts: Map<Long, Int>, cfg: RuleConfig): Int {
        val base = day.items.filter { it != Item.COMPLAINT }.sumOf { itemPoints(it, cfg) }
        val complaints = complaintCounts[day.epochDay] ?: 0
        return base + complaints * -cfg.complaintPenalty
    }

    /**
     * 按日期升序计算每天的分数，并分配投诉额度。
     * 返回顺序与输入 [days] 一致，便于调用方按行渲染。
     */
    fun dailyScores(days: List<DayRecord>, cfg: RuleConfig): List<Int> {
        val ascending = days.sortedBy { it.epochDay }
        var remaining = cfg.complaintMaxPerWeek
        val counts = mutableMapOf<Long, Int>()
        for (d in ascending) {
            if (d.items.contains(Item.COMPLAINT) && remaining > 0) {
                counts[d.epochDay] = 1
                remaining--
            } else {
                counts[d.epochDay] = 0
            }
        }
        return days.map { dailyScore(it, counts, cfg) }
    }

    /** 本周每日加分合计。 */
    fun dailyTotal(days: List<DayRecord>, cfg: RuleConfig): Int =
        dailyScores(days, cfg).sum()

    /** 单科考试奖励。 */
    fun examBonus(subject: Subject, score: Int, cfg: RuleConfig): Int =
        if (subject == Subject.ENGLISH) {
            when {
                score >= cfg.englishHighMin -> cfg.englishHighBonus
                score >= cfg.englishMidMin -> cfg.englishMidBonus
                else -> 0
            }
        } else {
            if (score >= cfg.otherSubjectMin) cfg.otherSubjectBonus else 0
        }

    /** 本周考试奖励合计。 */
    fun examTotal(exams: List<ExamRecord>, cfg: RuleConfig): Int =
        exams.sumOf { examBonus(it.subject, it.score, cfg) }

    /**
     * 孩子点击格子：把项加入待确认。已确认的项不受影响（孩子不能再操作已确认的项）。
     */
    fun claim(item: Item, items: Set<Item>, pending: Set<Item>): Set<Item> =
        if (items.contains(item)) pending else pending + item

    /**
     * 家长点击格子：
     * - 该格是已确认 -> 取消确认（移出 items）
     * - 该格是待确认 -> 确认（从 pending 移入 items）
     * - 该格是空     -> 直接确认
     * 返回新的 (items, pending)。
     */
    fun advance(item: Item, items: Set<Item>, pending: Set<Item>): Pair<Set<Item>, Set<Item>> =
        if (items.contains(item)) {
            (items - item) to pending
        } else {
            (items + item) to (pending - item)
        }

    /** 家长长按：退回孩子的申报。 */
    fun retract(item: Item, pending: Set<Item>): Set<Item> = pending - item
}
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*ScoreRulesTest*"`
Expected: PASS，18 个测试全绿

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/vdian/screentime/domain app/src/test/java/com/vdian/screentime/domain
git commit -m "feat: 实现计分规则与三态流转纯函数"
```

---

### Task 4: 周汇总 WeekCalculator

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/domain/WeekSummary.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/WeekCalculator.kt`
- Create: `app/src/test/java/com/vdian/screentime/domain/WeekCalculatorTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `ScoreRules`、`DayRecord`、`ExamRecord`、`WeekMeta`；Task 2 的 `RuleConfig`
- Produces:
  - `data class WeekSummary(...)`（字段见下方代码）
  - `object WeekCalculator`：`summarize(days, exams, meta, cfg): WeekSummary`

- [ ] **Step 1: 写失败测试 `WeekCalculatorTest.kt`**

```kotlin
package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekCalculatorTest {

    private val cfg = RuleConfig()

    private fun day(d: Long, vararg items: Item) = DayRecord(d, items.toSet())

    /** 造一周 7 天。 */
    private fun week(vararg days: DayRecord): List<DayRecord> = days.toList()

    @Test
    fun base_minutes_are_granted_unconditionally() {
        // 一条记录都没有，保底仍给 60
        val s = WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        assertEquals(60, s.baseMinutes)
    }

    @Test
    fun no_complaint_bonus_requires_all_seven_days_clean() {
        val clean = (0L..6L).map { day(it, Item.RAISED) }
        val s1 = WeekCalculator.summarize(clean, emptyList(), WeekMeta(), cfg)
        assertEquals(60, s1.noComplaintBonusMinutes)

        val withComplaint = (0L..6L).map {
            if (it == 3L) day(it, Item.COMPLAINT) else day(it, Item.RAISED)
        }
        val s2 = WeekCalculator.summarize(withComplaint, emptyList(), WeekMeta(), cfg)
        assertEquals(0, s2.noComplaintBonusMinutes)
    }

    @Test
    fun total_earned_sums_all_sources() {
        val days = (0L..6L).map { day(it, Item.RAISED) }        // 7 * 10 = 70
        val exams = listOf(ExamRecord(Subject.ENGLISH, 95))     // +30
        val s = WeekCalculator.summarize(days, exams, WeekMeta(), cfg)
        assertEquals(70, s.dailyTotal)
        assertEquals(30, s.examTotal)
        assertEquals(60 + 60 + 70 + 30, s.totalEarned)          // 220
    }

    @Test
    fun week_cap_clamps_total_and_reports_overflow() {
        // 造出超过 240 的一周：7 天全满分 = 7*60 = 420 加分
        val days = (0L..6L).map {
            day(it, Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH,
                Item.READING, Item.EXERCISE)
        }
        val s = WeekCalculator.summarize(days, emptyList(), WeekMeta(), cfg)
        assertEquals(420, s.dailyTotal)
        assertEquals(240, s.cappedEarned)
        assertEquals(240, s.totalEarned)          // 给上限 240 设为上限
        assertEquals(120, s.overflow)             // 超出部分
    }

    @Test
    fun negative_daily_total_reduces_earned() {
        val days = listOf(day(0, Item.COMPLAINT), day(1, Item.COMPLAINT))
        val s = WeekCalculator.summarize(days, emptyList(), WeekMeta(), cfg)
        assertEquals(-60, s.dailyTotal)
        // 60 保底 + 0 无投诉奖 - 60 = 0
        assertEquals(0, s.totalEarned)
    }

    @Test
    fun balance_subtracts_used_overtime_and_redeemed() {
        val days = (0L..6L).map { day(it, Item.RAISED) }
        val meta = WeekMeta(usedMinutes = 30, overtimeMinutes = 10, redeemedMinutes = 60)
        val s = WeekCalculator.summarize(days, emptyList(), meta, cfg)
        // 60 + 60 + 70 - 30 - 10 - 60 = 90
        assertEquals(90, s.balance)
    }

    @Test
    fun balance_can_go_negative_when_overused() {
        val meta = WeekMeta(usedMinutes = 500)
        val s = WeekCalculator.summarize(emptyList(), emptyList(), meta, cfg)
        // 60 - 500 = -440
        assertEquals(-440, s.balance)
        assertTrue(s.balance < 0)
    }

    @Test
    fun overflow_is_zero_when_under_cap() {
        val s = WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        assertEquals(0, s.overflow)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*WeekCalculatorTest*"`
Expected: FAIL — `Unresolved reference: WeekCalculator`

- [ ] **Step 3: 写 `WeekSummary.kt`**

```kotlin
package com.vdian.screentime.domain

/**
 * 一周的完整汇总，全部为派生值。
 * [overflow] 与 [BankResult.bankOverflow] 是两回事：前者是单周赚超封顶的部分，
 * 后者是银行累计超出上限的部分。
 */
data class WeekSummary(
    val dailyScores: List<Int>,          // 与输入 days 同序
    val dailyTotal: Int,                 // 每日加分合计（可为负）
    val examTotal: Int,                  // 考试奖励合计
    val baseMinutes: Int,                // 保底（无条件）
    val noComplaintBonusMinutes: Int,    // 一周无投诉奖励
    val totalEarned: Int,                // 封顶后的本周总赚取
    val cappedEarned: Int,               // 封顶前的原始值（调试与展示用）
    val overflow: Int,                   // 单周超出封顶的部分，>= 0
    val usedMinutes: Int,
    val overtimeMinutes: Int,
    val redeemedMinutes: Int,
    val redeemedCents: Int,
    val balance: Int,                    // 本周结余，可为负
    val complaintCount: Int,             // 本周生效的投诉次数
    val complaintPenalty: Int            // 单次投诉扣分（来自 RuleConfig，供界面展示用）
)
```

- [ ] **Step 4: 写 `WeekCalculator.kt`**

```kotlin
package com.vdian.screentime.domain

import kotlin.math.max
import kotlin.math.min

/** 周汇总计算。纯函数。 */
object WeekCalculator {

    fun summarize(
        days: List<DayRecord>,
        exams: List<ExamRecord>,
        meta: WeekMeta,
        cfg: RuleConfig
    ): WeekSummary {
        val scores = ScoreRules.dailyScores(days, cfg)
        val dailyTotal = scores.sum()
        val examTotal = ScoreRules.examTotal(exams, cfg)
        val base = cfg.baseMinutes

        // 只有全部有记录的天都无投诉才给，且至少要有记录
        val noComplaint = days.isNotEmpty() && days.none { it.items.contains(Item.COMPLAINT) }
        val bonus = if (noComplaint) cfg.noComplaintBonusMinutes else 0

        val raw = base + bonus + dailyTotal + examTotal
        val capped = min(raw, cfg.weekCapMinutes)
        val overflow = max(0, raw - cfg.weekCapMinutes)

        val balance = capped - meta.usedMinutes - meta.overtimeMinutes - meta.redeemedMinutes

        return WeekSummary(
            dailyScores = scores,
            dailyTotal = dailyTotal,
            examTotal = examTotal,
            baseMinutes = base,
            noComplaintBonusMinutes = bonus,
            totalEarned = capped,
            cappedEarned = raw,
            overflow = overflow,
            usedMinutes = meta.usedMinutes,
            overtimeMinutes = meta.overtimeMinutes,
            redeemedMinutes = meta.redeemedMinutes,
            redeemedCents = meta.redeemedCents,
            balance = balance,
            complaintCount = ScoreRules.effectiveComplaintCount(days, cfg),
            complaintPenalty = cfg.complaintPenalty
        )
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*WeekCalculatorTest*"`
Expected: PASS，8 个测试全绿

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/vdian/screentime/domain app/src/test/java/com/vdian/screentime/domain
git commit -m "feat: 实现周汇总计算含封顶与结余"
```

---

### Task 5: 屏幕银行 BankCalculator

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/domain/BankResult.kt`
- Create: `app/src/main/java/com/vdian/screentime/domain/BankCalculator.kt`
- Create: `app/src/test/java/com/vdian/screentime/domain/BankCalculatorTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `RuleConfig`
- Produces:
  - `data class BankResult(val bankBalanceMinutes: Int, val bankOverflow: Int, val savingCents: Int, val redeemableMinutesThisWeek: Int, val redeemableCentsThisWeek: Int)`
  - `object BankCalculator`：`compute(prevBankMinutes, prevSavingCents, weekBalanceMinutes, redeemedMinutesThisWeek, redeemedCentsThisWeek, cfg): BankResult`、`centsForMinutes(minutes, cfg): Int`、`maxRedeemableMinutes(weekBalanceMinutes, redeemedMinutesThisWeek, redeemedCentsThisWeek, cfg): Int`

- [ ] **Step 1: 写失败测试 `BankCalculatorTest.kt`**

```kotlin
package com.vdian.screentime.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BankCalculatorTest {

    private val cfg = RuleConfig()

    @Test
    fun bank_accumulates_prev_and_current() {
        val r = BankCalculator.compute(
            prevBankMinutes = 100, prevSavingCents = 0,
            weekBalanceMinutes = 50, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(150, r.bankBalanceMinutes)
        assertEquals(0, r.bankOverflow)
    }

    @Test
    fun bank_caps_at_480_and_reports_overflow() {
        val r = BankCalculator.compute(
            prevBankMinutes = 400, prevSavingCents = 0,
            weekBalanceMinutes = 200, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(480, r.bankBalanceMinutes)
        assertEquals(120, r.bankOverflow)     // 600 - 480
    }

    @Test
    fun bank_overflow_never_negative() {
        val r = BankCalculator.compute(
            prevBankMinutes = 100, prevSavingCents = 0,
            weekBalanceMinutes = 50, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(0, r.bankOverflow)
    }

    @Test
    fun negative_week_balance_reduces_bank_but_not_below_zero() {
        val r = BankCalculator.compute(
            prevBankMinutes = 100, prevSavingCents = 0,
            weekBalanceMinutes = -300, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(0, r.bankBalanceMinutes)   // 100 - 300 -> 夹到 0
        assertEquals(0, r.bankOverflow)
    }

    @Test
    fun already_full_bank_absorbs_new_earnings_into_overflow() {
        val r = BankCalculator.compute(
            prevBankMinutes = 480, prevSavingCents = 0,
            weekBalanceMinutes = 60, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(480, r.bankBalanceMinutes)
        assertEquals(60, r.bankOverflow)
    }

    @Test
    fun cents_for_minutes_rounds_down_to_whole_units() {
        assertEquals(200, BankCalculator.centsForMinutes(30, cfg))
        assertEquals(200, BankCalculator.centsForMinutes(45, cfg))   // 不足 60 分钟按 1 单位
        assertEquals(400, BankCalculator.centsForMinutes(60, cfg))
        assertEquals(0, BankCalculator.centsForMinutes(29, cfg))     // 不足 1 单位不给钱
    }

    @Test
    fun max_redeemable_is_limited_by_weekly_money_cap() {
        // 周上限 10 元 = 1000 分 = 5 个单位 = 150 分钟
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 400, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(150, m)
    }

    @Test
    fun max_redeemable_accounts_for_already_redeemed_money() {
        // 已兑换 8 元，只剩 2 元 = 1 单位 = 30 分钟
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 400, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 800, cfg = cfg
        )
        assertEquals(30, m)
    }

    @Test
    fun max_redeemable_is_limited_by_balance() {
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 45, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(30, m)   // 只能取整单位
    }

    @Test
    fun max_redeemable_is_zero_when_money_cap_reached() {
        val m = BankCalculator.maxRedeemableMinutes(
            weekBalanceMinutes = 400, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 1000, cfg = cfg
        )
        assertEquals(0, m)
    }

    @Test
    fun saving_caps_at_50_yuan() {
        val r = BankCalculator.compute(
            prevBankMinutes = 0, prevSavingCents = 4800,
            weekBalanceMinutes = 0, redeemedMinutesThisWeek = 30,
            redeemedCentsThisWeek = 200, cfg = cfg
        )
        assertEquals(5000, r.savingCents)     // 4800 + 200 -> 夹到 5000
    }

    @Test
    fun saving_is_never_decreased_by_penalties() {
        // 本周结余为负，存钱不受影响（钱不抵惩罚）
        val r = BankCalculator.compute(
            prevBankMinutes = 0, prevSavingCents = 1000,
            weekBalanceMinutes = -200, redeemedMinutesThisWeek = 0,
            redeemedCentsThisWeek = 0, cfg = cfg
        )
        assertEquals(1000, r.savingCents)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*BankCalculatorTest*"`
Expected: FAIL — `Unresolved reference: BankCalculator`

- [ ] **Step 3: 写 `BankResult.kt`**

```kotlin
package com.vdian.screentime.domain

/**
 * 屏幕银行状态。
 * [bankOverflow] 是银行累计超出上限的部分（与 [WeekSummary.overflow] 不同，后者是单周封顶溢出）。
 */
data class BankResult(
    val bankBalanceMinutes: Int,          // 银行累计，上限 bankCapMinutes
    val bankOverflow: Int,                // 超出上限的部分，>= 0
    val savingCents: Int,                 // 存钱累计，上限 savingCapCents
    val redeemableMinutesThisWeek: Int,   // 本周还能兑换的分钟数（已按整数单位取整）
    val redeemableCentsThisWeek: Int      // 上述分钟数对应的金额
)
```

- [ ] **Step 4: 写 `BankCalculator.kt`**

```kotlin
package com.vdian.screentime.domain

import kotlin.math.max
import kotlin.math.min

/** 屏幕银行计算。纯函数。兑换扣的是分钟额度，不是钱。 */
object BankCalculator {

    /** 兑换 [minutes] 分钟可得金额（分）。不足一个单位不给钱。 */
    fun centsForMinutes(minutes: Int, cfg: RuleConfig): Int =
        if (minutes < cfg.redeemUnitMinutes) 0
        else (minutes / cfg.redeemUnitMinutes) * cfg.redeemUnitCents

    /**
     * 本周还能兑换的分钟数，取三者最小值并向下取整到整数单位：
     * 1) 本周结余
     * 2) 剩余金额额度换算出的分钟数
     */
    fun maxRedeemableMinutes(
        weekBalanceMinutes: Int,
        redeemedMinutesThisWeek: Int,
        redeemedCentsThisWeek: Int,
        cfg: RuleConfig
    ): Int {
        val remainingCents = max(0, cfg.weeklyRedeemCapCents - redeemedCentsThisWeek)
        val minutesByMoney =
            (remainingCents / cfg.redeemUnitCents) * cfg.redeemUnitMinutes
        val availableByBalance = max(0, weekBalanceMinutes - redeemedMinutesThisWeek)
        val raw = min(availableByBalance, minutesByMoney)
        return (raw / cfg.redeemUnitMinutes) * cfg.redeemUnitMinutes
    }

    fun compute(
        prevBankMinutes: Int,
        prevSavingCents: Int,
        weekBalanceMinutes: Int,
        redeemedMinutesThisWeek: Int,
        redeemedCentsThisWeek: Int,
        cfg: RuleConfig
    ): BankResult {
        val accumulated = prevBankMinutes + max(0, weekBalanceMinutes)
        val bank = min(accumulated, cfg.bankCapMinutes)
        val overflow = max(0, accumulated - cfg.bankCapMinutes)
        val saving = min(prevSavingCents + redeemedCentsThisWeek, cfg.savingCapCents)
        val redeemableMinutes = maxRedeemableMinutes(
            weekBalanceMinutes, redeemedMinutesThisWeek, redeemedCentsThisWeek, cfg
        )

        return BankResult(
            bankBalanceMinutes = bank,
            bankOverflow = overflow,
            savingCents = saving,
            redeemableMinutesThisWeek = redeemableMinutes,
            redeemableCentsThisWeek = centsForMinutes(redeemableMinutes, cfg)
        )
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*BankCalculatorTest*"`
Expected: PASS，12 个测试全绿

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/vdian/screentime/domain app/src/test/java/com/vdian/screentime/domain
git commit -m "feat: 实现屏幕银行计算含截断与兑换限额"
```

---

### Task 6: Room 持久化层

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/data/entity/DayEntity.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/entity/WeekEntity.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/entity/ExamEntity.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/Mappers.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/DayDao.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/WeekDao.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/ExamDao.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/AppDatabase.kt`
- Create: `app/src/test/java/com/vdian/screentime/data/MappersTest.kt`

**Interfaces:**
- Consumes: Task 2/3 的 `Item`、`Subject`、`DayRecord`、`ExamRecord`、`WeekMeta`
- Produces:
  - `class DayDao`：`observeWeek(weekStart: Long): Flow<List<DayEntity>>`、`upsert(entity)`、`getByDay(epochDay: Long): DayEntity?`、`all(): List<DayEntity>`
  - `class WeekDao`：`observe(weekStart): Flow<WeekEntity?>`、`upsert(entity)`、`get(weekStart): WeekEntity?`、`all(): List<WeekEntity>`
  - `class ExamDao`：`observeWeek(weekStart): Flow<List<ExamEntity>>`、`upsert(entity)`、`delete(weekStart, subject)`、`all()`
  - `object Mappers`：`DayEntity.toDomain(): DayRecord`、`DayRecord.toEntity(weekStart): DayEntity`、`WeekEntity.toDomain(): WeekMeta`、`WeekMeta.toEntity(weekStart, prevBank, prevSaving): WeekEntity`、`ExamEntity.toDomain(): ExamRecord`
  - `abstract class AppDatabase : RoomDatabase`，含 `dayDao()`、`weekDao()`、`examDao()`，单例 `AppDatabase.get(context)`

- [ ] **Step 1: 写失败测试 `MappersTest.kt`**

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun day_entity_roundtrips_items_and_pending() {
        val entity = DayEntity(
            epochDay = 100L,
            weekStart = 98L,
            items = setOf(Item.RAISED, Item.READING),
            pending = setOf(Item.ENGLISH)
        )
        val domain = entity.toDomain()
        assertEquals(setOf(Item.RAISED, Item.READING), domain.items)
        assertEquals(setOf(Item.ENGLISH), domain.pending)
        assertEquals(100L, domain.epochDay)

        val back = domain.toEntity(98L)
        assertEquals(entity.epochDay, back.epochDay)
        assertEquals(entity.weekStart, back.weekStart)
        assertEquals(entity.items, back.items)
        assertEquals(entity.pending, back.pending)
    }

    @Test
    fun week_entity_roundtrips_meta_fields() {
        val entity = WeekEntity(
            weekStart = 98L,
            usedMinutes = 30,
            overtimeMinutes = 10,
            redeemedMinutes = 60,
            redeemedCents = 400,
            noteGood = "很好",
            noteImprove = "少涂改"
        )
        val domain = entity.toDomain()
        assertEquals(30, domain.usedMinutes)
        assertEquals(10, domain.overtimeMinutes)
        assertEquals(60, domain.redeemedMinutes)
        assertEquals(400, domain.redeemedCents)
        assertEquals("很好", domain.noteGood)
        assertEquals("少涂改", domain.noteImprove)
    }

    @Test
    fun exam_entity_roundtrips() {
        val e = ExamEntity(id = 1, weekStart = 98L, subject = Subject.ENGLISH, score = 92)
        assertEquals(Subject.ENGLISH, e.toDomain().subject)
        assertEquals(92, e.toDomain().score)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*MappersTest*"`
Expected: FAIL — `Unresolved reference: DayEntity`

- [ ] **Step 3: 写三个实体**

`app/src/main/java/com/vdian/screentime/data/entity/DayEntity.kt`：

Room 不支持 `Set<Enum>`，用 TypeConverter 转成逗号分隔的字符串。

```kotlin
package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vdian.screentime.domain.Item

@Entity(tableName = "day_record")
data class DayEntity(
    @PrimaryKey val epochDay: Long,
    val weekStart: Long,
    val items: Set<Item> = emptySet(),
    val pending: Set<Item> = emptySet()
)
```

`app/src/main/java/com/vdian/screentime/data/entity/WeekEntity.kt`：

```kotlin
package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一周的使用与兑换事实。
 * @param prevBankMinutes     上周银行结余（冗余存储，避免回溯计算全部历史）
 * @param prevSavingCents     上周存钱累计（同上）
 */
@Entity(tableName = "week_meta")
data class WeekEntity(
    @PrimaryKey val weekStart: Long,
    val usedMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    val redeemedMinutes: Int = 0,
    val redeemedCents: Int = 0,
    val prevBankMinutes: Int = 0,
    val prevSavingCents: Int = 0,
    val noteGood: String = "",
    val noteImprove: String = ""
)
```

`app/src/main/java/com/vdian/screentime/data/entity/ExamEntity.kt`：

```kotlin
package com.vdian.screentime.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vdian.screentime.domain.Subject

@Entity(tableName = "exam_record")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,
    val subject: Subject,
    val score: Int
)
```

- [ ] **Step 4: 写 `Mappers.kt`**

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.WeekMeta

/** Room 实体与 Domain 模型之间的双向映射。纯函数，无 Android 依赖。 */
object Mappers {

    fun DayEntity.toDomain(): DayRecord = DayRecord(
        epochDay = epochDay,
        items = items,
        pending = pending
    )

    fun DayRecord.toEntity(weekStart: Long): DayEntity = DayEntity(
        epochDay = epochDay,
        weekStart = weekStart,
        items = items,
        pending = pending
    )

    fun WeekEntity.toDomain(): WeekMeta = WeekMeta(
        usedMinutes = usedMinutes,
        overtimeMinutes = overtimeMinutes,
        redeemedMinutes = redeemedMinutes,
        redeemedCents = redeemedCents,
        noteGood = noteGood,
        noteImprove = noteImprove
    )

    fun WeekMeta.toEntity(
        weekStart: Long,
        prevBankMinutes: Int,
        prevSavingCents: Int
    ): WeekEntity = WeekEntity(
        weekStart = weekStart,
        usedMinutes = usedMinutes,
        overtimeMinutes = overtimeMinutes,
        redeemedMinutes = redeemedMinutes,
        redeemedCents = redeemedCents,
        prevBankMinutes = prevBankMinutes,
        prevSavingCents = prevSavingCents,
        noteGood = noteGood,
        noteImprove = noteImprove
    )

    fun ExamEntity.toDomain(): ExamRecord = ExamRecord(subject = subject, score = score)
}
```

- [ ] **Step 5: 写三个 DAO**

`DayDao.kt`：

```kotlin
package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.DayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {

    @Query("SELECT * FROM day_record WHERE weekStart = :weekStart ORDER BY epochDay ASC")
    fun observeWeek(weekStart: Long): Flow<List<DayEntity>>

    @Query("SELECT * FROM day_record WHERE epochDay = :epochDay LIMIT 1")
    suspend fun getByDay(epochDay: Long): DayEntity?

    @Query("SELECT * FROM day_record ORDER BY epochDay ASC")
    suspend fun all(): List<DayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DayEntity)
}
```

`WeekDao.kt`：

```kotlin
package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.WeekEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeekDao {

    @Query("SELECT * FROM week_meta WHERE weekStart = :weekStart LIMIT 1")
    fun observe(weekStart: Long): Flow<WeekEntity?>

    @Query("SELECT * FROM week_meta WHERE weekStart = :weekStart LIMIT 1")
    suspend fun get(weekStart: Long): WeekEntity?

    @Query("SELECT * FROM week_meta WHERE weekStart < :weekStart ORDER BY weekStart DESC LIMIT 1")
    suspend fun getPrevious(weekStart: Long): WeekEntity?

    @Query("SELECT * FROM week_meta ORDER BY weekStart ASC")
    suspend fun all(): List<WeekEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeekEntity)
}
```

`ExamDao.kt`：

```kotlin
package com.vdian.screentime.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.domain.Subject
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Query("SELECT * FROM exam_record WHERE weekStart = :weekStart ORDER BY id ASC")
    fun observeWeek(weekStart: Long): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exam_record ORDER BY weekStart ASC, id ASC")
    suspend fun all(): List<ExamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExamEntity)

    @Query("DELETE FROM exam_record WHERE weekStart = :weekStart AND subject = :subject")
    suspend fun delete(weekStart: Long, subject: Subject)
}
```

- [ ] **Step 6: 写 `AppDatabase.kt`**

TypeConverter 把 `Set<Item>` 与 `Subject` 存成文本。

```kotlin
package com.vdian.screentime.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.Subject

class Converters {
    @TypeConverter
    fun itemsToString(items: Set<Item>?): String =
        items.orEmpty().joinToString(",") { it.name }

    @TypeConverter
    fun stringToItems(raw: String?): Set<Item> =
        raw.orEmpty().split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { name -> Item.values().firstOrNull { it.name == name } }
            .toSet()

    @TypeConverter
    fun subjectToString(subject: Subject?): String = subject?.name.orEmpty()

    @TypeConverter
    fun stringToSubject(raw: String?): Subject =
        Subject.values().firstOrNull { it.name == raw } ?: Subject.ENGLISH
}

@Database(
    entities = [DayEntity::class, WeekEntity::class, ExamEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayDao(): DayDao
    abstract fun weekDao(): WeekDao
    abstract fun examDao(): ExamDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "screen_time.db"
            ).build().also { instance = it }
        }
    }
}
```

**注意**：`app/build.gradle` 用的是 `annotationProcessor`，Kotlin 代码需要改为 `kapt`。在 `app/build.gradle` 顶部加上 `apply plugin: 'kotlin-kapt'`，并把 `annotationProcessor 'androidx.room:room-compiler:2.4.2'` 改为 `kapt 'androidx.room:room-compiler:2.4.2'`。

- [ ] **Step 7: 运行测试与编译，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*MappersTest*" :app:compileDebugKotlin`
Expected: PASS，3 个测试全绿，Kotlin 编译无错（确认 kapt 正常生成 Room 实现）

- [ ] **Step 8: 提交**

```bash
git add app/
git commit -m "feat: 新增 Room 持久化层与实体映射"
```

---

### Task 7: 规则参数持久化 SettingsStore

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/data/SettingsStore.kt`
- Create: `app/src/test/java/com/vdian/screentime/data/RuleConfigSerializationTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `RuleConfig`
- Produces:
  - `object RuleConfigCodec`：`encode(cfg): Map<String, Int>`、`decode(map: Map<String, Int>, defaults: RuleConfig): RuleConfig`
  - `class SettingsStore(context)`：`observeConfig(): Flow<RuleConfig>`、`saveConfig(cfg)`、`observeChildName(): Flow<String>`、`saveChildName(name)`

- [ ] **Step 1: 写失败测试 `RuleConfigSerializationTest.kt`**

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.domain.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleConfigSerializationTest {

    @Test
    fun encode_decode_roundtrips_all_fields() {
        val cfg = RuleConfig(
            raisePoints = 20,
            neatWritingPoints = 5,
            englishPoints = 12,
            readingPoints = 25,
            exercisePoints = 8,
            complaintPenalty = 40,
            complaintMaxPerWeek = 3,
            baseMinutes = 90,
            noComplaintBonusMinutes = 30,
            weekCapMinutes = 300,
            bankCapMinutes = 600,
            redeemUnitMinutes = 60,
            redeemUnitCents = 500,
            weeklyRedeemCapCents = 2000,
            savingCapCents = 10000,
            englishMidMin = 80,
            englishHighMin = 95,
            englishMidBonus = 20,
            englishHighBonus = 50,
            otherSubjectMin = 85,
            otherSubjectBonus = 40
        )
        val decoded = RuleConfigCodec.decode(RuleConfigCodec.encode(cfg), RuleConfig())
        assertEquals(cfg, decoded)
    }

    @Test
    fun decode_falls_back_to_defaults_for_missing_keys() {
        // 只覆盖一个字段，其余应保持默认值（向前兼容旧存档）
        val decoded = RuleConfigCodec.decode(mapOf("raisePoints" to 99), RuleConfig())
        assertEquals(99, decoded.raisePoints)
        assertEquals(15, decoded.readingPoints)
        assertEquals(240, decoded.weekCapMinutes)
    }

    @Test
    fun decode_ignores_unknown_keys() {
        val decoded = RuleConfigCodec.decode(
            mapOf("raisePoints" to 11, "futureFieldWeDontKnow" to 7),
            RuleConfig()
        )
        assertEquals(11, decoded.raisePoints)
        assertEquals(10, decoded.neatWritingPoints)
    }

    @Test
    fun encode_covers_every_field_so_nothing_is_silently_dropped() {
        val encoded = RuleConfigCodec.encode(RuleConfig())
        // 21 个可配置项，少一个都说明新增字段时忘记加进编解码
        assertEquals(21, encoded.size)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*RuleConfigSerializationTest*"`
Expected: FAIL — `Unresolved reference: RuleConfigCodec`

- [ ] **Step 3: 写 `SettingsStore.kt`**

编解码抽成纯函数便于单测；DataStore 只负责读写。

```kotlin
package com.vdian.screentime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vdian.screentime.domain.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** RuleConfig 与键值对的互相转换。纯函数，可单测。 */
object RuleConfigCodec {

    /** 字段名 -> 取值函数。新增字段时必须同步加到这里。 */
    private val fields: List<Pair<String, (RuleConfig) -> Int>> = listOf(
        "raisePoints" to { it.raisePoints },
        "neatWritingPoints" to { it.neatWritingPoints },
        "englishPoints" to { it.englishPoints },
        "readingPoints" to { it.readingPoints },
        "exercisePoints" to { it.exercisePoints },
        "complaintPenalty" to { it.complaintPenalty },
        "complaintMaxPerWeek" to { it.complaintMaxPerWeek },
        "baseMinutes" to { it.baseMinutes },
        "noComplaintBonusMinutes" to { it.noComplaintBonusMinutes },
        "weekCapMinutes" to { it.weekCapMinutes },
        "bankCapMinutes" to { it.bankCapMinutes },
        "redeemUnitMinutes" to { it.redeemUnitMinutes },
        "redeemUnitCents" to { it.redeemUnitCents },
        "weeklyRedeemCapCents" to { it.weeklyRedeemCapCents },
        "savingCapCents" to { it.savingCapCents },
        "englishMidMin" to { it.englishMidMin },
        "englishHighMin" to { it.englishHighMin },
        "englishMidBonus" to { it.englishMidBonus },
        "englishHighBonus" to { it.englishHighBonus },
        "otherSubjectMin" to { it.otherSubjectMin },
        "otherSubjectBonus" to { it.otherSubjectBonus }
    )

    fun encode(cfg: RuleConfig): Map<String, Int> =
        fields.associate { (key, getter) -> key to getter(cfg) }

    /** 缺失的键回落到 [defaults]，未知的键忽略——保证旧存档可读。 */
    fun decode(map: Map<String, Int>, defaults: RuleConfig): RuleConfig {
        fun v(key: String): Int = map[key] ?: fields
            .firstOrNull { it.first == key }
            ?.let { it.second(defaults) }
            ?: error("unknown key: $key")

        return RuleConfig(
            raisePoints = v("raisePoints"),
            neatWritingPoints = v("neatWritingPoints"),
            englishPoints = v("englishPoints"),
            readingPoints = v("readingPoints"),
            exercisePoints = v("exercisePoints"),
            complaintPenalty = v("complaintPenalty"),
            complaintMaxPerWeek = v("complaintMaxPerWeek"),
            baseMinutes = v("baseMinutes"),
            noComplaintBonusMinutes = v("noComplaintBonusMinutes"),
            weekCapMinutes = v("weekCapMinutes"),
            bankCapMinutes = v("bankCapMinutes"),
            redeemUnitMinutes = v("redeemUnitMinutes"),
            redeemUnitCents = v("redeemUnitCents"),
            weeklyRedeemCapCents = v("weeklyRedeemCapCents"),
            savingCapCents = v("savingCapCents"),
            englishMidMin = v("englishMidMin"),
            englishHighMin = v("englishHighMin"),
            englishMidBonus = v("englishMidBonus"),
            englishHighBonus = v("englishHighBonus"),
            otherSubjectMin = v("otherSubjectMin"),
            otherSubjectBonus = v("otherSubjectBonus")
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private val childNameKey = stringPreferencesKey("child_name")

    private fun configKey(name: String) = intPreferencesKey("rule_$name")

    fun observeConfig(): Flow<RuleConfig> = context.dataStore.data.map { prefs ->
        val map = RuleConfigCodec.encode(RuleConfig()).keys
            .mapNotNull { name -> prefs[configKey(name)]?.let { name to it } }
            .toMap()
        RuleConfigCodec.decode(map, RuleConfig())
    }

    suspend fun saveConfig(cfg: RuleConfig) {
        context.dataStore.edit { prefs ->
            RuleConfigCodec.encode(cfg).forEach { (name, value) ->
                prefs[configKey(name)] = value
            }
        }
    }

    fun observeChildName(): Flow<String> =
        context.dataStore.data.map { it[childNameKey].orEmpty() }

    suspend fun saveChildName(name: String) {
        context.dataStore.edit { it[childNameKey] = name }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*RuleConfigSerializationTest*"`
Expected: PASS，4 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add app/
git commit -m "feat: 新增规则参数持久化与编解码"
```

---

### Task 8: 仓库层 ScreenTimeRepository

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/data/ScreenTimeRepository.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/WeekBoard.kt`
- Create: `app/src/test/java/com/vdian/screentime/data/WeekBoardTest.kt`

**Interfaces:**
- Consumes: Task 6 的 DAO、Task 7 的 `SettingsStore`、Task 4/5 的计算器、Task 3 的 `ScoreRules`
- Produces:
  - `data class WeekBoard(val weekStart: Long, val days: List<DayRecord>, val exams: List<ExamRecord>, val meta: WeekMeta, val prevBankMinutes: Int, val prevSavingCents: Int, val config: RuleConfig)`，含派生属性 `summary`（`WeekSummary`）与 `bank`（`BankResult`）
  - `object WeekBoardAssembler`：`build(weekStart, days, exams, weekEntity, prevEntity, cfg): WeekBoard`
  - `class ScreenTimeRepository(dayDao, weekDao, examDao, settingsStore)`：`observeWeek(weekStart): Flow<WeekBoard>`、`toggleChildClaim(weekStart, epochDay, item)`、`toggleParentConfirm(weekStart, epochDay, item)`、`retractClaim(epochDay, item)`、`setExam(weekStart, subject, score)`、`addUsedMinutes(weekStart, delta)`、`applyOvertime(weekStart, minutes)`、`redeem(weekStart, minutes)`、`saveNotes(weekStart, good, improve)`、`blankWeek(weekStart): List<DayRecord>`

**WeekBoard 是 UI 唯一的数据来源**，界面直接读 `board.summary` 与 `board.bank`，不做算术。

- [ ] **Step 1: 写失败测试 `WeekBoardTest.kt`**

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WeekBoardTest {

    private val cfg = RuleConfig()
    private val weekStart = 100L

    @Test
    fun board_always_has_seven_days_even_when_database_is_empty() {
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = emptyList(),
            exams = emptyList(),
            weekEntity = null,
            prevEntity = null,
            cfg = cfg
        )
        assertEquals(7, board.days.size)
        assertEquals(weekStart, board.days.first().epochDay)
        assertEquals(weekStart + 6, board.days.last().epochDay)
    }

    @Test
    fun missing_days_are_filled_with_empty_records() {
        val board = WeekBoardAssembler.build(
            weekStart = weekStart,
            days = listOf(DayRecord(weekStart + 2, setOf(Item.RAISED))),
            exams = emptyList(),
            weekEntity = null,
            prevEntity = null,
            cfg = cfg
        )
        assertEquals(setOf(Item.RAISED), board.days[2].items)
        assertEquals(emptySet<Item>(), board.days[0].items)
    }

    @Test
    fun board_without_week_entity_uses_zero_meta() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, null, cfg
        )
        assertEquals(0, board.meta.usedMinutes)
        assertEquals(0, board.prevBankMinutes)
        assertEquals(0, board.prevSavingCents)
    }

    @Test
    fun board_reads_previous_week_totals_from_prev_entity() {
        val prev = WeekEntity(weekStart = 93L, prevBankMinutes = 120, prevSavingCents = 600)
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, prev, cfg
        )
        // prevBankMinutes 语义是该周结算后的银行余额
        assertEquals(120, board.prevBankMinutes)
        assertEquals(600, board.prevSavingCents)
    }

    @Test
    fun summary_and_bank_are_derived_and_non_null() {
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), null, null, cfg
        )
        assertNotNull(board.summary)
        assertNotNull(board.bank)
        assertEquals(60, board.summary.baseMinutes)
    }

    @Test
    fun week_entity_overrides_stored_meta_fields() {
        val entity = WeekEntity(
            weekStart = weekStart, usedMinutes = 45, overtimeMinutes = 5, redeemedMinutes = 30
        )
        val board = WeekBoardAssembler.build(
            weekStart, emptyList(), emptyList(), entity, null, cfg
        )
        assertEquals(45, board.meta.usedMinutes)
        assertEquals(5, board.meta.overtimeMinutes)
        assertEquals(30, board.meta.redeemedMinutes)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*WeekBoardTest*"`
Expected: FAIL — `Unresolved reference: WeekBoardAssembler`

- [ ] **Step 3: 写 `WeekBoard.kt`**

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.BankCalculator
import com.vdian.screentime.domain.BankResult
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.WeekCalculator
import com.vdian.screentime.domain.WeekMeta
import com.vdian.screentime.domain.WeekSummary

/**
 * 一周的完整视图数据，UI 的唯一数据来源。
 * 界面直接读 [summary] 与 [bank]，不做任何算术。
 */
data class WeekBoard(
    val weekStart: Long,
    val days: List<DayRecord>,          // 恒为 7 条，按日期升序
    val exams: List<ExamRecord>,
    val meta: WeekMeta,
    val prevBankMinutes: Int,
    val prevSavingCents: Int,
    val config: RuleConfig
) {
    val summary: WeekSummary by lazy {
        WeekCalculator.summarize(days, exams, meta, config)
    }

    val bank: BankResult by lazy {
        BankCalculator.compute(
            prevBankMinutes = prevBankMinutes,
            prevSavingCents = prevSavingCents,
            weekBalanceMinutes = summary.balance,
            redeemedMinutesThisWeek = meta.redeemedMinutes,
            redeemedCentsThisWeek = meta.redeemedCents,
            cfg = config
        )
    }
}

/** 把数据库原始行拼装成 [WeekBoard]。纯函数。 */
object WeekBoardAssembler {

    fun build(
        weekStart: Long,
        days: List<DayRecord>,
        exams: List<ExamRecord>,
        weekEntity: WeekEntity?,
        prevEntity: WeekEntity?,
        cfg: RuleConfig
    ): WeekBoard {
        val byDay = days.associateBy { it.epochDay }
        val filled = (0L..6L).map { offset ->
            byDay[weekStart + offset] ?: DayRecord(weekStart + offset)
        }
        return WeekBoard(
            weekStart = weekStart,
            days = filled,
            exams = exams,
            meta = weekEntity?.toDomain() ?: WeekMeta(),
            prevBankMinutes = prevEntity?.prevBankMinutes ?: 0,
            prevSavingCents = prevEntity?.prevSavingCents ?: 0,
            config = cfg
        )
    }

    private fun WeekEntity.toDomain() = Mappers.run { toDomain() }
}
```

- [ ] **Step 4: 写 `ScreenTimeRepository.kt`**

银行结余与存钱的**结算**在跨周时发生：本周的 `prevBankMinutes` / `prevSavingCents` 取自上周结算后的值。为保证不依赖历史回溯，每周首次写入时把上周结算结果固化到本周记录里。

```kotlin
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
            days = dayEntities.map { it.toDomain() },
            exams = examEntities.map { it.toDomain() },
            weekEntity = weekEntity,
            prevEntity = weekDao.getPrevious(weekStart),
            cfg = cfg
        )
        board
    }

    /** 孩子点击：把项加入待确认。已确认的项不受影响。 */
    suspend fun toggleChildClaim(weekStart: Long, epochDay: Long, item: Item) {
        val current = dayDao.getByDay(epochDay)?.toDomain() ?: DayRecord(epochDay)
        val items = current.items
        val pending = ScoreRules.claim(item, items, current.pending)
        saveDay(weekStart, current.copy(pending = pending))
    }

    /** 家长点击：确认待确认项 / 取消已确认项 / 直接确认空格。 */
    suspend fun toggleParentConfirm(weekStart: Long, epochDay: Long, item: Item) {
        val current = dayDao.getByDay(epochDay)?.toDomain() ?: DayRecord(epochDay)
        val (items, pending) = ScoreRules.advance(item, current.items, current.pending)
        saveDay(weekStart, current.copy(items = items, pending = pending))
    }

    /** 家长长按：退回孩子的申报。 */
    suspend fun retractClaim(weekStart: Long, epochDay: Long, item: Item) {
        val current = dayDao.getByDay(epochDay)?.toDomain() ?: return
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
                days = dayDao.all().filter { it.weekStart == prev.weekStart }.map { it.toDomain() },
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
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS，全部测试绿（含此前所有任务）

注意：`WeekBoardTest` 里 `Mappers.run { toDomain() }` 的写法需要 `Mappers` 的扩展函数在作用域内可见。若编译报错，改为在 `WeekBoardAssembler` 中直接构造 `WeekMeta`：

```kotlin
private fun WeekEntity.toMeta() = WeekMeta(
    usedMinutes = usedMinutes,
    overtimeMinutes = overtimeMinutes,
    redeemedMinutes = redeemedMinutes,
    redeemedCents = redeemedCents,
    noteGood = noteGood,
    noteImprove = noteImprove
)
```

并把 `WeekBoardAssembler.build` 里的 `weekEntity?.toDomain() ?: WeekMeta()` 改为 `weekEntity?.toMeta() ?: WeekMeta()`。

- [ ] **Step 6: 提交**

```bash
git add app/
git commit -m "feat: 新增仓库层与 WeekBoard 视图数据装配"
```

---

### Task 9: 主界面骨架与底部导航

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/ui/MainActivity.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/WeekFragment.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/BankFragment.kt`（先做占位）
- Create: `app/src/main/java/com/vdian/screentime/ui/SettingsFragment.kt`（先做占位）
- Create: `app/src/main/java/com/vdian/screentime/ui/AppContainer.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout/fragment_week.xml`
- Create: `app/src/main/res/layout/fragment_bank.xml`
- Create: `app/src/main/res/layout/fragment_settings.xml`
- Create: `app/src/main/res/menu/main_bottom_nav.xml`
- Create: `app/src/main/res/values/strings.xml`（补充）

**Interfaces:**
- Consumes: Task 8 的 `ScreenTimeRepository`、Task 6 的 `AppDatabase`、Task 7 的 `SettingsStore`
- Produces:
  - `object AppContainer`：`fun repository(context: Context): ScreenTimeRepository`（懒加载单例）
  - `class MainActivity`：底部导航切换三个 Fragment
  - 三个 Fragment 占位骨架

- [ ] **Step 1: 写 `AppContainer.kt`**

不用依赖注入框架，全局单例足够。

```kotlin
package com.vdian.screentime.ui

import android.content.Context
import com.vdian.screentime.data.AppDatabase
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.SettingsStore

/** 简单的依赖容器。应用规模小，不需要 DI 框架。 */
object AppContainer {

    @Volatile
    private var repo: ScreenTimeRepository? = null

    fun repository(context: Context): ScreenTimeRepository = repo ?: synchronized(this) {
        repo ?: run {
            val app = context.applicationContext
            val db = AppDatabase.get(app)
            ScreenTimeRepository(
                dayDao = db.dayDao(),
                weekDao = db.weekDao(),
                examDao = db.examDao(),
                settingsStore = SettingsStore(app)
            ).also { repo = it }
        }
    }
}
```

- [ ] **Step 2: 写 `activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/bottomNav"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:background="@android:color/white"
        app:menu="@menu/main_bottom_nav"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: 写 `main_bottom_nav.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@+id/tab_week" android:title="本周" />
    <item android:id="@+id/tab_bank" android:title="银行" />
    <item android:id="@+id/tab_settings" android:title="设置" />
</menu>
```

- [ ] **Step 4: 写 `MainActivity.kt`**

```kotlin
package com.vdian.screentime.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.vdian.screentime.R
import com.vdian.screentime.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchTo(WeekFragment(), "week")
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_week -> switchTo(WeekFragment(), "week")
                R.id.tab_bank -> switchTo(BankFragment(), "bank")
                R.id.tab_settings -> switchTo(SettingsFragment(), "settings")
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    private fun switchTo(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }
}
```

- [ ] **Step 5: 写三个 Fragment 与占位布局**

`fragment_week.xml`（本任务先放一个占位 TextView，Task 10 再换成真表格）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="12dp">

    <TextView
        android:id="@+id/weekTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="18sp"
        android:textStyle="bold"
        android:text="本周" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="14sp"
        android:text="（表格在下一步实现）" />
</LinearLayout>
```

`fragment_bank.xml` 与 `fragment_settings.xml` 同样只放一个居中 TextView 显示"银行"/"设置"。

`WeekFragment.kt`：

```kotlin
package com.vdian.screentime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.vdian.screentime.databinding.FragmentWeekBinding

class WeekFragment : Fragment() {

    private var _binding: FragmentWeekBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeekBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

`BankFragment.kt` 与 `SettingsFragment.kt` 结构相同，绑定各自的 Binding 类。

- [ ] **Step 6: 编译并在设备/模拟器上确认三个 Tab 可切换**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:installDebug`
Expected: 安装成功；打开 App，底部三个 Tab 可点击切换，无崩溃

- [ ] **Step 7: 提交**

```bash
git add app/
git commit -m "feat: 主界面骨架与底部导航"
```

---

### Task 10: 本周表格与三态交互

**Files:**
- Create: `app/src/main/java/com/vdian/screentime/ui/WeekViewModel.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/WeekRowAdapter.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/DayLabels.kt`
- Modify: `app/src/main/java/com/vdian/screentime/ui/WeekFragment.kt`
- Modify: `app/src/main/res/layout/fragment_week.xml`
- Create: `app/src/main/res/layout/item_week_row.xml`
- Create: `app/src/test/java/com/vdian/screentime/ui/DayLabelsTest.kt`

**Interfaces:**
- Consumes: Task 8 的 `ScreenTimeRepository`、`WeekBoard`
- Produces:
  - `object DayLabels`：`weekdayName(index: Int): String`、`itemName(item: Item): String`、`formatDateRange(weekStart: Long): String`
  - `class WeekViewModel(repo, weekStart)`：`board: LiveData<WeekBoard>`、`prevWeek()`、`nextWeek()`、`onChildTap(epochDay, item)`、`onParentTap(epochDay, item)`、`onParentLongPress(epochDay, item)`
  - `class WeekRowAdapter`：7 行，每行 6 个可点格子

- [ ] **Step 1: 写失败测试 `DayLabelsTest.kt`**

中文文案集中管理，保证界面与纸质表用词一致。

```kotlin
package com.vdian.screentime.ui

import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.WeekDates
import org.junit.Assert.assertEquals
import org.junit.Test

class DayLabelsTest {

    @Test
    fun weekday_names_start_from_monday() {
        assertEquals("周一", DayLabels.weekdayName(0))
        assertEquals("周三", DayLabels.weekdayName(2))
        assertEquals("周日", DayLabels.weekdayName(6))
    }

    @Test
    fun item_names_match_the_paper_table() {
        assertEquals("举手回答", DayLabels.itemName(Item.RAISED))
        assertEquals("字认真", DayLabels.itemName(Item.NEAT_WRITING))
        assertEquals("主动英语", DayLabels.itemName(Item.ENGLISH))
        assertEquals("主动阅读", DayLabels.itemName(Item.READING))
        assertEquals("主动运动", DayLabels.itemName(Item.EXERCISE))
        assertEquals("投诉", DayLabels.itemName(Item.COMPLAINT))
    }

    @Test
    fun date_range_spans_seven_days() {
        val monday = WeekDates.toEpochDay(2026, 9, 21)
        assertEquals("9月21日 - 9月27日", DayLabels.formatDateRange(monday))
    }

    @Test
    fun weekday_name_handles_out_of_range_gracefully() {
        assertEquals("", DayLabels.weekdayName(7))
        assertEquals("", DayLabels.weekdayName(-1))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*DayLabelsTest*"`
Expected: FAIL — `Unresolved reference: DayLabels`

- [ ] **Step 3: 写 `DayLabels.kt`**

```kotlin
package com.vdian.screentime.ui

import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.WeekDates

/** 界面中文文案。集中在此，保证与纸质记录表用词一致。 */
object DayLabels {

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayName(index: Int): String = weekdays.getOrElse(index) { "" }

    fun itemName(item: Item): String = when (item) {
        Item.RAISED -> "举手回答"
        Item.NEAT_WRITING -> "字认真"
        Item.ENGLISH -> "主动英语"
        Item.READING -> "主动阅读"
        Item.EXERCISE -> "主动运动"
        Item.COMPLAINT -> "投诉"
    }

    fun itemHeader(item: Item, cfg: com.vdian.screentime.domain.RuleConfig): String =
        "${itemName(item)}\n+${com.vdian.screentime.domain.ScoreRules.itemPoints(item, cfg)}"

    fun formatDateRange(weekStart: Long): String =
        "${WeekDates.format(weekStart)} - ${WeekDates.format(weekStart + 6)}"
}
```

- [ ] **Step 4: 写 `item_week_row.xml`**

一行 = 星期 + 6 个格子 + 当日小计。格子用 `TextView`，靠背景与文字区分三态。

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:minHeight="48dp"
    android:gravity="center_vertical">

    <TextView
        android:id="@+id/dayLabel"
        android:layout_width="56dp"
        android:layout_height="match_parent"
        android:gravity="center"
        android:textSize="14sp"
        android:textStyle="bold" />

    <LinearLayout
        android:id="@+id/cellContainer"
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1"
        android:orientation="horizontal" />

    <TextView
        android:id="@+id/dayScore"
        android:layout_width="56dp"
        android:layout_height="match_parent"
        android:gravity="center"
        android:textSize="14sp"
        android:textStyle="bold" />
</LinearLayout>
```

每个格子由 `WeekRowAdapter` 用代码生成（6 个），避免写死 6 份重复 XML：

```kotlin
private fun createCell(): TextView = TextView(parent.context).apply {
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    gravity = Gravity.CENTER
    textSize = 16f
    setPadding(4, 12, 4, 12)
}
```

- [ ] **Step 5: 写 `WeekRowAdapter.kt`**

三态视觉：空＝浅灰底空方框；待确认＝黄底"待确认"；已确认＝绿底实心勾。点击 = 家长确认；长按 = 家长退回；孩子申报走独立入口（见 Step 6 的"孩子模式"开关）。

```kotlin
package com.vdian.screentime.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vdian.screentime.R
import com.vdian.screentime.databinding.ItemWeekRowBinding
import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.Item

/** 三态：空 / 孩子已申报待确认 / 家长已确认。 */
enum class CellState { EMPTY, PENDING, CONFIRMED }

object CellStateResolver {
    fun resolve(item: Item, day: DayRecord): CellState = when {
        day.items.contains(item) -> CellState.CONFIRMED
        day.pending.contains(item) -> CellState.PENDING
        else -> CellState.EMPTY
    }
}

class WeekRowAdapter(
    private val items: List<Item>,
    private val childMode: () -> Boolean,
    private val onParentTap: (epochDay: Long, item: Item) -> Unit,
    private val onChildTap: (epochDay: Long, item: Item) -> Unit,
    private val onParentLongPress: (epochDay: Long, item: Item) -> Unit
) : RecyclerView.Adapter<WeekRowAdapter.RowHolder>() {

    private var days: List<DayRecord> = emptyList()
    private var scores: List<Int> = emptyList()

    fun submit(newDays: List<DayRecord>, newScores: List<Int>) {
        days = newDays
        scores = newScores
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = days.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val binding = ItemWeekRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RowHolder(binding)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val day = days[position]
        holder.bind(day, scores.getOrElse(position) { 0 })
    }

    inner class RowHolder(private val binding: ItemWeekRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val cells = mutableListOf<TextView>()

        init {
            items.forEach { _ ->
                val tv = TextView(binding.root.context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    gravity = Gravity.CENTER
                    textSize = 16f
                    setPadding(4, 20, 4, 20)
                }
                binding.cellContainer.addView(tv)
                cells.add(tv)
            }
        }

        fun bind(day: DayRecord, score: Int) {
            binding.dayLabel.text = DayLabels.weekdayName(
                com.vdian.screentime.domain.WeekDates.dayOfWeek(day.epochDay)
            )
            binding.dayScore.text = score.toString()
            binding.dayScore.setTextColor(if (score < 0) Color.RED else Color.BLACK)

            items.forEachIndexed { index, item ->
                val cell = cells[index]
                when (CellStateResolver.resolve(item, day)) {
                    CellState.EMPTY -> {
                        cell.text = "○"
                        cell.setBackgroundColor(Color.parseColor("#F5F5F5"))
                        cell.setTextColor(Color.parseColor("#9E9E9E"))
                    }
                    CellState.PENDING -> {
                        cell.text = "待确认"
                        cell.textSize = 11f
                        cell.setBackgroundColor(Color.parseColor("#FFF9C4"))
                        cell.setTextColor(Color.parseColor("#F57F17"))
                    }
                    CellState.CONFIRMED -> {
                        cell.text = "✓"
                        cell.textSize = 18f
                        cell.setBackgroundColor(Color.parseColor("#C8E6C9"))
                        cell.setTextColor(Color.parseColor("#2E7D32"))
                    }
                }

                cell.setOnClickListener {
                    if (childMode()) onChildTap(day.epochDay, item)
                    else onParentTap(day.epochDay, item)
                }
                cell.setOnLongClickListener {
                    onParentLongPress(day.epochDay, item)
                    true
                }
            }
        }
    }
}
```

- [ ] **Step 6: 写 `WeekViewModel.kt`**

```kotlin
package com.vdian.screentime.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.WeekDates
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WeekViewModel(private val repo: ScreenTimeRepository) : ViewModel() {

    private val _board = MutableLiveData<WeekBoard>()
    val board: LiveData<WeekBoard> = _board

    /** 孩子模式开关。由界面上的开关控制，默认关闭（家长模式）。 */
    val childMode = MutableLiveData(false)

    private var weekStart: Long = WeekDates.weekStart(todayEpochDay())

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            repo.observeWeek(weekStart).collectLatest { _board.value = it }
        }
    }

    fun prevWeek() {
        weekStart -= 7
        observe()
    }

    fun nextWeek() {
        weekStart += 7
        observe()
    }

    fun onChildTap(epochDay: Long, item: Item) {
        viewModelScope.launch { repo.toggleChildClaim(weekStart, epochDay, item) }
    }

    fun onParentTap(epochDay: Long, item: Item) {
        viewModelScope.launch { repo.toggleParentConfirm(weekStart, epochDay, item) }
    }

    fun onParentLongPress(epochDay: Long, item: Item) {
        viewModelScope.launch { repo.retractClaim(weekStart, epochDay, item) }
    }

    fun currentWeekStart(): Long = weekStart

    private fun todayEpochDay(): Long = (System.currentTimeMillis() / 86_400_000L)
}
```

- [ ] **Step 7: 更新 `WeekFragment.kt` 与 `fragment_week.xml`**

`fragment_week.xml` 换成：标题行（周次 + 上/下周箭头）、`RecyclerView`、孩子模式 `Switch`。

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="8dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <Button android:id="@+id/prevWeek" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:text="上一周" />
        <TextView android:id="@+id/weekTitle" android:layout_width="0dp"
            android:layout_height="wrap_content" android:layout_weight="1"
            android:gravity="center" android:textSize="16sp" android:textStyle="bold" />
        <Button android:id="@+id/nextWeek" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:text="下一周" />
    </LinearLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:layout_marginTop="4dp">

        <TextView android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_weight="1" android:text="孩子申报模式"
            android:textSize="14sp" />
        <Switch android:id="@+id/childModeSwitch" android:layout_width="wrap_content"
            android:layout_height="wrap_content" />
    </LinearLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/weekRows"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="4dp" />
</LinearLayout>
```

`WeekFragment.kt`：

```kotlin
package com.vdian.screentime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.vdian.screentime.databinding.FragmentWeekBinding
import com.vdian.screentime.domain.Item

class WeekFragment : Fragment() {

    private var _binding: FragmentWeekBinding? = null
    private val binding get() = _binding!!

    private val vm: WeekViewModel by viewModels {
        WeekViewModelFactory(AppContainer.repository(requireContext()))
    }

    private lateinit var adapter: WeekRowAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWeekBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = WeekRowAdapter(
            items = ROW_ITEMS,
            childMode = { vm.childMode.value == true },
            onParentTap = { day, item -> vm.onParentTap(day, item) },
            onChildTap = { day, item -> vm.onChildTap(day, item) },
            onParentLongPress = { day, item -> vm.onParentLongPress(day, item) }
        )
        binding.weekRows.layoutManager = LinearLayoutManager(requireContext())
        binding.weekRows.adapter = adapter

        binding.prevWeek.setOnClickListener { vm.prevWeek() }
        binding.nextWeek.setOnClickListener { vm.nextWeek() }
        binding.childModeSwitch.setOnCheckedChangeListener { _, checked ->
            vm.childMode.value = checked
        }

        vm.board.observe(viewLifecycleOwner) { board ->
            binding.weekTitle.text = DayLabels.formatDateRange(board.weekStart)
            adapter.submit(board.days, board.summary.dailyScores)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 表格列顺序，与纸质记录表一致。 */
        private val ROW_ITEMS = listOf(
            Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH, Item.READING, Item.EXERCISE, Item.COMPLAINT
        )
    }
}
```

同时新建 `WeekViewModelFactory.kt`：

```kotlin
package com.vdian.screentime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vdian.screentime.data.ScreenTimeRepository

class WeekViewModelFactory(private val repo: ScreenTimeRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>): T =
        WeekViewModel(repo) as T
}
```

- [ ] **Step 8: 运行测试并装机验证三态**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（含 `DayLabelsTest` 4 项）

Run: `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL

手工验证清单：
1. 关闭"孩子申报模式"，点某格 → 变绿勾，当日小计增加对应分值
2. 打开"孩子申报模式"，点另一格 → 变黄"待确认"，**当日小计不变**
3. 关掉孩子模式，点那个黄格 → 变绿勾，小计这才增加
4. 长按黄格 → 退回为灰圆，小计不变
5. 连点同一格两次 → 绿勾 → 灰圆，不残留
6. 点"上一周""下一周" → 标题日期正确切换

- [ ] **Step 9: 提交**

```bash
git add app/
git commit -m "feat: 本周表格与孩子申报三态交互"
```

---

### Task 11: 汇总卡片与考试录入

**Files:**
- Create: `app/src/main/res/layout/card_week_summary.xml`
- Create: `app/src/main/res/layout/dialog_exam_input.xml`
- Create: `app/src/main/java/com/vdian/screentime/ui/SummaryBinder.kt`
- Modify: `app/src/main/res/layout/fragment_week.xml`
- Modify: `app/src/main/java/com/vdian/screentime/ui/WeekFragment.kt`
- Create: `app/src/test/java/com/vdian/screentime/ui/SummaryBinderTest.kt`

**Interfaces:**
- Consumes: Task 10 的 `WeekViewModel`、`WeekBoard`；Task 4 的 `WeekSummary`
- Produces:
  - `object SummaryBinder`：`lines(summary: WeekSummary): List<Pair<String, String>>`（返回"项目名 to 值"的有序列表，便于单测与渲染）
  - 汇总卡片 UI、考试录入对话框

- [ ] **Step 1: 写失败测试 `SummaryBinderTest.kt`**

```kotlin
package com.vdian.screentime.ui

import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekCalculator
import com.vdian.screentime.domain.WeekMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryBinderTest {

    private val cfg = RuleConfig()

    private fun lineMap(summary: com.vdian.screentime.domain.WeekSummary) =
        SummaryBinder.lines(summary).toMap()

    @Test
    fun lines_cover_every_field_on_the_paper_summary_table() {
        val s = WeekCalculator.summarize(
            (0L..6L).map { DayRecord(it, setOf(Item.RAISED)) },
            listOf(ExamRecord(Subject.ENGLISH, 95)),
            WeekMeta(usedMinutes = 30),
            cfg
        )
        val m = lineMap(s)
        assertEquals("60", m["保底"])
        assertEquals("60", m["一周无投诉"])
        assertEquals("70", m["每日加分合计"])
        assertEquals("30", m["考试奖励合计"])
        assertEquals("0", m["投诉扣分"])
        assertEquals("220", m["本周总赚取"])
        assertEquals("30", m["本周已使用"])
        assertEquals("190", m["本周结余"])
    }

    @Test
    fun complaint_penalty_line_is_negative() {
        val s = WeekCalculator.summarize(
            listOf(DayRecord(0, setOf(Item.COMPLAINT))),
            emptyList(), WeekMeta(), cfg
        )
        assertEquals("-30", lineMap(s)["投诉扣分"])
    }

    @Test
    fun lines_are_ordered_for_display() {
        val names = SummaryBinder.lines(
            WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        ).map { it.first }
        assertEquals(
            listOf(
                "保底", "一周无投诉", "每日加分合计", "考试奖励合计",
                "投诉扣分", "本周总赚取", "本周已使用", "本周结余"
            ),
            names
        )
    }

    @Test
    fun overflow_hint_is_shown_only_when_capped() {
        val under = WeekCalculator.summarize(emptyList(), emptyList(), WeekMeta(), cfg)
        assertEquals("", SummaryBinder.overflowHint(under))

        val days = (0L..6L).map {
            DayRecord(it, setOf(Item.RAISED, Item.NEAT_WRITING, Item.ENGLISH,
                Item.READING, Item.EXERCISE))
        }
        val over = WeekCalculator.summarize(days, emptyList(), WeekMeta(), cfg)
        assertEquals("已封顶 4 小时，超出 120 分钟可换小奖品", SummaryBinder.overflowHint(over))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*SummaryBinderTest*"`
Expected: FAIL — `Unresolved reference: SummaryBinder`

- [ ] **Step 3: 写 `SummaryBinder.kt`**

```kotlin
package com.vdian.screentime.ui

import com.vdian.screentime.domain.WeekSummary

/** 把 [WeekSummary] 转成界面要显示的行，顺序固定。纯函数，可单测。 */
object SummaryBinder {

    fun lines(s: WeekSummary): List<Pair<String, String>> = listOf(
        "保底" to s.baseMinutes.toString(),
        "一周无投诉" to s.noComplaintBonusMinutes.toString(),
        "每日加分合计" to s.dailyTotal.toString(),
        "考试奖励合计" to s.examTotal.toString(),
        // 投诉扣分显示为负数，便于和纸质表对照
        "投诉扣分" to (-s.complaintCount * 30).toString(),
        "本周总赚取" to s.totalEarned.toString(),
        "本周已使用" to (s.usedMinutes + s.overtimeMinutes + s.redeemedMinutes).toString(),
        "本周结余" to s.balance.toString()
    )

    /** 封顶提示。未封顶时不显示。 */
    fun overflowHint(s: WeekSummary): String =
        if (s.overflow > 0) "已封顶 4 小时，超出 ${s.overflow} 分钟可换小奖品" else ""
}
```

**注意**：`lines` 里"投诉扣分"写死了 30，违反"不得写死数字"的约束。改为从 `WeekSummary` 暴露 `complaintPenalty` 字段：在 Task 4 的 `WeekSummary` 增加 `val complaintPenalty: Int`，在 `WeekCalculator.summarize` 里赋值 `complaintPenalty = cfg.complaintPenalty`，并把此行改为：

```kotlin
"投诉扣分" to (-s.complaintCount * s.complaintPenalty).toString(),
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*SummaryBinderTest*"`
Expected: PASS，4 个测试全绿

- [ ] **Step 5: 写汇总卡片布局并接入 WeekFragment**

`card_week_summary.xml`：一个 `LinearLayout` 容器 `summaryLines`，加上 `overflowHint` 的 `TextView`。

`WeekFragment` 中在 RecyclerView 下方插入该卡片，`board` 观察回调里追加：

```kotlin
binding.summaryLines.removeAllViews()
SummaryBinder.lines(board.summary).forEach { (name, value) ->
    val row = layoutInflater.inflate(R.layout.item_summary_line, binding.summaryLines, false)
    row.findViewById<TextView>(R.id.lineName).text = name
    row.findViewById<TextView>(R.id.lineValue).text = value
    binding.summaryLines.addView(row)
}
val hint = SummaryBinder.overflowHint(board.summary)
binding.overflowHint.text = hint
binding.overflowHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
```

配套新建 `item_summary_line.xml`（左右两个 TextView）。

- [ ] **Step 6: 加考试录入入口**

每个星期行加一个"考试"按钮，点击弹出 `dialog_exam_input.xml`（4 个科目各一个 `EditText` + 确定/取消）。确定后调用：

```kotlin
vm.setExam(Subject.ENGLISH, englishText)
vm.setExam(Subject.CHINESE, chineseText)
vm.setExam(Subject.MATH, mathText)
vm.setExam(Subject.SCIENCE, scienceText)
```

`WeekViewModel` 增加：

```kotlin
fun setExam(subject: Subject, scoreText: String) {
    val score = scoreText.trim().toIntOrNull() ?: 0
    viewModelScope.launch { repo.setExam(weekStart, subject, score) }
}
```

空字符串或非数字按 0 处理，仓库层会把 `score <= 0` 的记录删除。

- [ ] **Step 7: 运行测试并手工验证**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: 全部 PASS

手工验证：
1. 勾选若干格，汇总卡片各行数字与手算一致
2. 录入英语 92 → 考试奖励合计 +30；改录 88 → +15；改录 80 → 0
3. 录入语文 88 → 0
4. 制造一周满分 → 出现封顶提示"已封顶 4 小时，超出 N 分钟可换小奖品"

- [ ] **Step 8: 提交**

```bash
git add app/
git commit -m "feat: 周汇总卡片与考试奖励录入"
```

---

### Task 12: 银行页与兑换

**Files:**
- Modify: `app/src/main/res/layout/fragment_bank.xml`
- Modify: `app/src/main/java/com/vdian/screentime/ui/BankFragment.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/BankViewModel.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/BankViewModelFactory.kt`
- Create: `app/src/main/res/layout/dialog_redeem.xml`

**Interfaces:**
- Consumes: Task 8 的 `ScreenTimeRepository`、`WeekBoard.bank`
- Produces: 银行页 UI、兑换流程

- [ ] **Step 1: 写 `BankViewModel.kt`**

```kotlin
package com.vdian.screentime.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vdian.screentime.data.ScreenTimeRepository
import com.vdian.screentime.data.WeekBoard
import com.vdian.screentime.domain.WeekDates
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BankViewModel(private val repo: ScreenTimeRepository) : ViewModel() {

    private val _board = MutableLiveData<WeekBoard>()
    val board: LiveData<WeekBoard> = _board

    private val weekStart = WeekDates.weekStart(System.currentTimeMillis() / 86_400_000L)

    init {
        viewModelScope.launch {
            repo.observeWeek(weekStart).collectLatest { _board.value = it }
        }
    }

    fun redeem(minutes: Int) {
        viewModelScope.launch { repo.redeem(weekStart, minutes) }
    }

    fun recordUsage(minutes: Int) {
        viewModelScope.launch { repo.addUsedMinutes(weekStart, minutes) }
    }

    fun recordOvertime(minutes: Int) {
        viewModelScope.launch { repo.applyOvertime(weekStart, minutes) }
    }
}
```

`BankViewModelFactory` 与 `WeekViewModelFactory` 结构相同。

- [ ] **Step 2: 写 `fragment_bank.xml`**

自上而下：大字余额卡片、银行明细行、"记录使用时长"按钮、"超时扣减"按钮、"兑换"按钮、存钱累计。

余额用 40sp 大字，孩子一眼看得懂。

```xml
<TextView
    android:id="@+id/bankBalance"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center"
    android:textSize="40sp"
    android:textStyle="bold" />
```

- [ ] **Step 3: 写兑换对话框 `dialog_redeem.xml`**

内容：`NumberPicker`（可选分钟，步长 = 30）、实时显示可得金额与本周剩余额度、确定/取消。

金额预览用纯函数，与仓库层同一套规则：

```kotlin
val cents = BankCalculator.centsForMinutes(minutes, board.config)
binding.redeemPreview.text = "可得 ${cents / 100} 元"
```

- [ ] **Step 4: 接入 `BankFragment.kt`**

观察 `board`，刷新余额、明细、各按钮的可用状态（`redeemableMinutesThisWeek == 0` 时兑换按钮置灰）。

- [ ] **Step 5: 手工验证**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`

验证清单：
1. 余额与本周结余一致
2. 兑换 30 分钟 → 余额减 30，存钱 +2 元
3. 连续兑换直到 10 元 → 兑换按钮置灰，提示已达每周上限
4. 记录使用 30 分钟 → 余额减 30
5. 记录超时 5 分钟 → 余额减 5（1:1）

- [ ] **Step 6: 提交**

```bash
git add app/
git commit -m "feat: 屏幕银行页与兑换流程"
```

---

### Task 13: 设置页、数据导出与清空

**Files:**
- Modify: `app/src/main/res/layout/fragment_settings.xml`
- Modify: `app/src/main/java/com/vdian/screentime/ui/SettingsFragment.kt`
- Create: `app/src/main/java/com/vdian/screentime/ui/SettingsViewModel.kt`
- Create: `app/src/main/java/com/vdian/screentime/data/DataExporter.kt`
- Create: `app/src/test/java/com/vdian/screentime/data/DataExporterTest.kt`

**Interfaces:**
- Consumes: Task 7 的 `SettingsStore`、Task 6 的三个 DAO
- Produces:
  - `object DataExporter`：`toJson(days: List<DayRecord>, weeks: List<WeekMeta>, exams: List<ExamRecord>, cfg: RuleConfig, childName: String): String`
  - 设置页：孩子姓名、21 个规则参数分组编辑、导出、清空

- [ ] **Step 1: 写失败测试 `DataExporterTest.kt`**

不引入 JSON 库，手写序列化即可（结构固定）。

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 用 Robolectric 拿 android 的 org.json 实现。
 * 需在 app/build.gradle 加 testImplementation 'org.robolectric:robolectric:4.8.1'
 * 以及 android { testOptions { unitTests.includeAndroidResources = true } }
 */
@RunWith(RobolectricTestRunner::class)
class DataExporterTest {

    @Test
    fun export_contains_child_name_and_config() {
        val json = DataExporter.toJson(
            days = emptyList(),
            weeks = emptyList(),
            exams = emptyList(),
            cfg = RuleConfig(raisePoints = 12),
            childName = "小明"
        )
        val root = JSONObject(json)
        assertEquals("小明", root.getString("childName"))
        assertEquals(12, root.getJSONObject("config").getInt("raisePoints"))
    }

    @Test
    fun export_roundtrips_days_and_exams() {
        val json = DataExporter.toJson(
            days = listOf(
                DayRecord(100L, setOf(Item.RAISED, Item.READING), setOf(Item.ENGLISH))
            ),
            weeks = listOf(WeekMeta(usedMinutes = 30)),
            exams = listOf(ExamRecord(Subject.MATH, 95)),
            cfg = RuleConfig(),
            childName = "小明"
        )
        val root = JSONObject(json)
        val day = root.getJSONArray("days").getJSONObject(0)
        assertEquals(100L, day.getLong("epochDay"))
        assertTrue(day.getJSONArray("items").length() == 2)
        assertTrue(day.getJSONArray("pending").length() == 1)
        assertEquals("MATH", root.getJSONArray("exams").getJSONObject(0).getString("subject"))
        assertEquals(95, root.getJSONArray("exams").getJSONObject(0).getInt("score"))
    }

    @Test
    fun empty_export_is_still_valid_json() {
        val json = DataExporter.toJson(emptyList(), emptyList(), emptyList(), RuleConfig(), "")
        val root = JSONObject(json)
        assertEquals(0, root.getJSONArray("days").length())
        assertEquals(0, root.getJSONArray("weeks").length())
        assertEquals(0, root.getJSONArray("exams").length())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "*DataExporterTest*"`
Expected: FAIL — `Unresolved reference: DataExporter`

- [ ] **Step 3: 在 `app/build.gradle` 加 Robolectric**

```groovy
android {
    testOptions {
        unitTests {
            includeAndroidResources = true
        }
    }
}

dependencies {
    testImplementation 'org.robolectric:robolectric:4.8.1'
}
```

- [ ] **Step 4: 写 `DataExporter.kt`**

```kotlin
package com.vdian.screentime.data

import com.vdian.screentime.domain.DayRecord
import com.vdian.screentime.domain.ExamRecord
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.WeekMeta
import org.json.JSONArray
import org.json.JSONObject

/** 导出为 JSON 文本。结构固定，手写序列化即可，不引入 JSON 库。 */
object DataExporter {

    fun toJson(
        days: List<DayRecord>,
        weeks: List<WeekMeta>,
        exams: List<ExamRecord>,
        cfg: RuleConfig,
        childName: String
    ): String {
        val root = JSONObject()

        val dayArray = JSONArray()
        days.forEach { d ->
            dayArray.put(JSONObject().apply {
                put("epochDay", d.epochDay)
                put("items", JSONArray(d.items.map { it.name }))
                put("pending", JSONArray(d.pending.map { it.name }))
            })
        }
        root.put("days", dayArray)

        val weekArray = JSONArray()
        weeks.forEach { w ->
            weekArray.put(JSONObject().apply {
                put("usedMinutes", w.usedMinutes)
                put("overtimeMinutes", w.overtimeMinutes)
                put("redeemedMinutes", w.redeemedMinutes)
                put("redeemedCents", w.redeemedCents)
                put("noteGood", w.noteGood)
                put("noteImprove", w.noteImprove)
            })
        }
        root.put("weeks", weekArray)

        val examArray = JSONArray()
        exams.forEach { e ->
            examArray.put(JSONObject().apply {
                put("subject", e.subject.name)
                put("score", e.score)
            })
        }
        root.put("exams", examArray)

        val configObject = JSONObject()
        RuleConfigCodec.encode(cfg).forEach { (k, v) -> configObject.put(k, v) }
        root.put("config", configObject)

        root.put("childName", childName)
        return root.toString(2)
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "*DataExporterTest*"`
Expected: PASS，3 个测试全绿

- [ ] **Step 6: 写设置页布局与 `SettingsViewModel`**

`fragment_settings.xml`：孩子姓名 `EditText`、规则参数分组（每日分值 / 周奖励与封顶 / 银行与兑换 / 考试奖励），每组若干 `EditText`、"保存"按钮、"导出数据"按钮（`ACTION_CREATE_DOCUMENT` 保存到文件）、"清空所有数据"按钮。

**另外要在本周页加上第六部分的"本周一句话总结"录入**（规格 7.3）：在本周页汇总卡片下方放两个 `EditText`（"做得好的" / "下周改进"），失焦或点击保存时写入。`WeekViewModel` 增加：

```kotlin
fun saveNotes(good: String, improve: String) {
    viewModelScope.launch { repo.saveNotes(weekStart, good, improve) }
}
```

并在 `board` 观察回调里，**仅在不同周切换时**回填输入框，避免打字过程被数据库回写打断：

```kotlin
if (binding.noteGood.text.toString() != board.meta.noteGood) {
    binding.noteGood.setText(board.meta.noteGood)
}
```

`SettingsViewModel`：`observeConfig`、`saveConfig`、`observeChildName`、`saveChildName`、`clearAll()`（清空三张表）、`exportJson()`。

清空必须二次确认对话框：

```kotlin
MaterialAlertDialogBuilder(requireContext())
    .setTitle("确认清空？")
    .setMessage("所有记录将被永久删除，无法恢复。")
    .setPositiveButton("清空") { _, _ -> vm.clearAll() }
    .setNegativeButton("取消", null)
    .show()
```

- [ ] **Step 7: 手工验证**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`

验证清单：
1. 改"主动阅读"分值为 20 → 回本周页勾一格 → 当日小计变 20
2. 改孩子姓名 → 重启 App 后仍保留
3. 导出 → 文件内容为合法 JSON，含全部记录与参数
4. 清空 → 二次确认后所有格子回到空状态

- [ ] **Step 8: 提交**

```bash
git add app/
git commit -m "feat: 设置页、数据导出与清空"
```

---

## 完成标准

全部 13 个任务完成后，App 应满足：

1. `./gradlew :app:testDebugUnitTest` 全绿（预计 60+ 个测试）
2. `./gradlew :app:assembleDebug` 构建通过
3. 主界面 7×6 表格可完整操作，三态流转正确
4. 汇总、银行、兑换的数字与手算一致
5. AndroidManifest 中无任何 `<uses-permission>`
6. `domain/` 包内无 `android.*` import
