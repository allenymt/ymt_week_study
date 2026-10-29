package com.vdian.screentime.data

import com.vdian.screentime.data.entity.DayEntity
import com.vdian.screentime.data.entity.ExamEntity
import com.vdian.screentime.data.entity.WeekEntity
import com.vdian.screentime.domain.Item
import com.vdian.screentime.domain.ItemCatalog
import com.vdian.screentime.domain.ItemDef
import com.vdian.screentime.domain.RuleConfig
import com.vdian.screentime.domain.Subject
import com.vdian.screentime.domain.WeekDates
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 导入文件的解析与校验（方案第 5 节）。只读文本、不碰数据库，便于单测。
 *
 * 任何一处不合法都整体放弃（返回 [ImportResult.Error]），绝不返回"半份"数据：
 * 宁可让用户看到原因，也不能让一家人的历史被一份坏文件改掉一半。
 */
object DataImporter {

    /** 能读的最高格式版本。 */
    const val MAX_SCHEMA_VERSION = DataExporter.SCHEMA_VERSION

    /** 校验失败。消息直接展示给用户。 */
    private class Invalid(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw Invalid(message)

    fun parse(json: String): ImportResult {
        val root = try {
            JSONObject(json.removePrefix("\uFEFF"))
        } catch (e: JSONException) {
            return ImportResult.Error("不是有效的 JSON 文件")
        }
        return try {
            val version = if (root.has("schemaVersion")) {
                intOf(root.opt("schemaVersion"), "schemaVersion")
            } else {
                1
            }
            when {
                version > MAX_SCHEMA_VERSION ->
                    fail("文件格式版本 $version 高于本应用支持的 $MAX_SCHEMA_VERSION，请先升级应用")
                version < 1 -> fail("文件格式版本 $version 无效")
                version == 1 -> ImportResult.Ok(parseV1(root))
                else -> ImportResult.Ok(parseV2(root))
            }
        } catch (e: Invalid) {
            ImportResult.Error(e.message ?: "文件内容不合法")
        } catch (e: JSONException) {
            ImportResult.Error("文件内容不合法：${e.message}")
        }
    }

    // ---- v1 -------------------------------------------------------------------

    private fun parseV1(root: JSONObject): ImportPlan {
        val rawConfig = configMap(root)
        val legacy = RuleConfigCodec.LEGACY_POINT_KEYS
            .mapNotNull { k -> rawConfig[k]?.let { k to it } }
            .toMap()
        val config = RuleConfigCodec.decode(rawConfig, RuleConfig())
        val itemDefs = ItemCatalog.builtin(legacy).defs

        val days = parseDays(root)
        val examArray = optArray(root, "exams")
        if (examArray != null && examArray.length() > 0) {
            fail("旧版（v1）文件中的考试记录缺少所属周，无法还原（共 ${examArray.length()} 条）")
        }

        // v1 的周记录没有 weekStart：按升序对应日记录覆盖的各周，条数必须严格相等，不去猜。
        val coveredWeeks = days.map { it.weekStart }.distinct().sorted()
        val weekArray = optArray(root, "weeks") ?: JSONArray()
        if (weekArray.length() != coveredWeeks.size) {
            fail(
                "旧版（v1）文件中周记录有 ${weekArray.length()} 条，" +
                    "日记录覆盖 ${coveredWeeks.size} 周，无法对应"
            )
        }
        val weeks = coveredWeeks.mapIndexed { i, weekStart ->
            val o = objectAt(weekArray, i, "weeks")
            val where = "weeks[$i]"
            WeekEntity(
                weekStart = weekStart,
                usedMinutes = optInt(o, "usedMinutes", where),
                overtimeMinutes = optInt(o, "overtimeMinutes", where),
                redeemedMinutes = optInt(o, "redeemedMinutes", where),
                redeemedCents = optInt(o, "redeemedCents", where),
                savingAdjustCents = 0,
                prevBankMinutes = 0,
                prevSavingCents = 0,
                noteGood = optString(o, "noteGood", where),
                noteImprove = optString(o, "noteImprove", where)
            )
        }

        val replayed = replay(weeks, days, emptyList(), config, itemDefs)
        val warnings = mutableListOf(
            "旧版（v1）文件：考核项按内置 11 项还原，人工存取金额置 0，银行余额按历史重算"
        )
        unknownItemWarning(days, itemDefs)?.let { warnings += it }
        return ImportPlan(
            schemaVersion = 1,
            childName = childName(root),
            config = config,
            itemDefs = itemDefs,
            days = days,
            weeks = replayed,
            exams = emptyList(),
            warnings = warnings
        )
    }

    // ---- v2 -------------------------------------------------------------------

    private fun parseV2(root: JSONObject): ImportPlan {
        val config = RuleConfigCodec.decode(configMap(root), RuleConfig())
        val itemDefs = parseItemDefs(root)
        val days = parseDays(root)
        val parsedExams = parseExams(root)
        val exams = dedupeExams(parsedExams)
        val fileWeeks = parseWeeks(root)

        // 应用里写日记录或考试时一定先建周行；文件里缺周行的周按空白周补上，银行链才完整。
        val known = fileWeeks.map { it.weekStart }.toSet()
        val missing = (days.map { it.weekStart } + exams.map { it.weekStart })
            .filter { it !in known }
            .distinct()
        val weeks = (fileWeeks + missing.map { WeekEntity(weekStart = it) }).sortedBy { it.weekStart }

        val replayed = replay(weeks, days, exams, config, itemDefs)
        val warnings = mutableListOf<String>()
        if (missing.isNotEmpty()) {
            warnings += "有 ${missing.size} 周有日记录或考试但缺少周记录，已按空白周补上"
        }
        val droppedExams = parsedExams.size - exams.size
        if (droppedExams > 0) {
            warnings += "有 $droppedExams 条重复的考试成绩，每周每科只保留最后一条"
        }
        // 只比较文件里原有的周行；补上的空白周期初本就是 0，重算后不同不算"不一致"。
        val original = fileWeeks.associateBy { it.weekStart }
        val drifted = replayed.count { w -> original[w.weekStart]?.let { it != w } ?: false }
        if (drifted > 0) {
            warnings += "有 $drifted 周的银行期初与历史不一致，已按历史重算"
        }
        unknownItemWarning(days, itemDefs)?.let { warnings += it }
        return ImportPlan(
            schemaVersion = 2,
            childName = childName(root),
            config = config,
            itemDefs = itemDefs,
            days = days,
            weeks = replayed,
            exams = exams,
            warnings = warnings
        )
    }

    private fun parseItemDefs(root: JSONObject): List<ItemDef> {
        val arr = optArray(root, "itemDefs") ?: fail("缺少考核项定义（itemDefs）")
        if (arr.length() == 0) fail("考核项定义（itemDefs）为空")
        val seen = mutableSetOf<String>()
        return (0 until arr.length()).map { i ->
            val o = objectAt(arr, i, "itemDefs")
            val where = "itemDefs[$i]"
            val key = itemKey(o.opt("key"), "$where.key")
            if (!seen.add(key)) fail("考核项 key 重复：$key")
            val name = o.opt("name") as? String ?: fail("$where.name 缺失或不是字符串")
            if (name.isBlank()) fail("$where.name 为空")
            val group = when (val g = o.opt("exclusiveGroup")) {
                null, JSONObject.NULL -> null
                is String -> g.trim().ifEmpty { null }
                else -> fail("$where.exclusiveGroup 不是字符串")
            }
            ItemDef(
                item = Item(key),
                name = name,
                points = reqInt(o, "points", where),
                sortOrder = reqInt(o, "sortOrder", where),
                enabled = optBool(o, "enabled", true, where),
                childClaimable = reqBool(o, "childClaimable", where),
                exclusiveGroup = group,
                isComplaint = optBool(o, "isComplaint", false, where)
            )
        }
    }

    private fun parseWeeks(root: JSONObject): List<WeekEntity> {
        val arr = optArray(root, "weeks") ?: return emptyList()
        val seen = mutableSetOf<Long>()
        return (0 until arr.length()).map { i ->
            val o = objectAt(arr, i, "weeks")
            val where = "weeks[$i]"
            val weekStart = weekStartOf(o, where)
            if (!seen.add(weekStart)) fail("周记录重复：$where.weekStart = $weekStart")
            WeekEntity(
                weekStart = weekStart,
                usedMinutes = optInt(o, "usedMinutes", where),
                overtimeMinutes = optInt(o, "overtimeMinutes", where),
                redeemedMinutes = optInt(o, "redeemedMinutes", where),
                redeemedCents = optInt(o, "redeemedCents", where),
                prevBankMinutes = optInt(o, "prevBankMinutes", where),
                prevSavingCents = optInt(o, "prevSavingCents", where),
                savingAdjustCents = optInt(o, "savingAdjustCents", where),
                noteGood = optString(o, "noteGood", where),
                noteImprove = optString(o, "noteImprove", where)
            )
        }
    }

    private fun parseExams(root: JSONObject): List<ExamEntity> {
        val arr = optArray(root, "exams") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = objectAt(arr, i, "exams")
            val where = "exams[$i]"
            val raw = o.opt("subject") as? String ?: fail("$where.subject 缺失或不是字符串")
            val subject = Subject.values().firstOrNull { it.name == raw }
                ?: fail("$where.subject 不是已知科目：$raw")
            ExamEntity(
                id = 0,
                weekStart = weekStartOf(o, where),
                subject = subject,
                score = reqInt(o, "score", where)
            )
        }
    }

    /**
     * 每周每科只保留一条考试成绩：同一 (weekStart, subject) 取数组里最后出现的一条，
     * 结果按 weekStart 升序（同周内保持原顺序）。与应用内 setExam"先删同科再插"的语义一致。
     */
    internal fun dedupeExams(exams: List<ExamEntity>): List<ExamEntity> =
        exams.associateBy { it.weekStart to it.subject }.values.sortedBy { it.weekStart }

    // ---- 共用部分 ---------------------------------------------------------------

    private fun parseDays(root: JSONObject): List<DayEntity> {
        val arr = optArray(root, "days") ?: return emptyList()
        val seen = mutableSetOf<Long>()
        return (0 until arr.length()).map { i ->
            val o = objectAt(arr, i, "days")
            val where = "days[$i]"
            val epochDay = longOf(o.opt("epochDay"), "$where.epochDay")
            if (!seen.add(epochDay)) fail("日记录重复：$where.epochDay = $epochDay")
            DayEntity(
                epochDay = epochDay,
                weekStart = WeekDates.weekStart(epochDay),
                items = itemSet(o, "items", where),
                pending = itemSet(o, "pending", where)
            )
        }.sortedBy { it.epochDay }
    }

    private fun itemSet(o: JSONObject, key: String, where: String): Set<Item> {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) return emptySet()
        val arr = v as? JSONArray ?: fail("$where.$key 不是数组")
        return (0 until arr.length()).map { Item(itemKey(arr.opt(it), "$where.$key[$it]")) }.toSet()
    }

    /** 数据库里日记录的 items 以逗号拼接存储，key 不得含逗号、不得为空白。 */
    private fun itemKey(v: Any?, where: String): String {
        val s = v as? String ?: fail("$where 缺失或不是字符串")
        if (s.isBlank()) fail("$where 为空")
        if (s.contains(',')) fail("$where 含有逗号：$s")
        return s
    }

    private fun weekStartOf(o: JSONObject, where: String): Long {
        val weekStart = longOf(o.opt("weekStart"), "$where.weekStart")
        if (WeekDates.weekStart(weekStart) != weekStart) fail("$where.weekStart = $weekStart 不是周一")
        return weekStart
    }

    /**
     * config 对象里的全部整数键。已知参数键与旧分值键取值必须是整数；其余未知键忽略。
     * 缺少 config 时按空表处理，参数全部取默认值。
     */
    private fun configMap(root: JSONObject): Map<String, Int> {
        val v = root.opt("config")
        if (v == null || v == JSONObject.NULL) return emptyMap()
        val o = v as? JSONObject ?: fail("config 不是对象")
        val checked = RuleConfigCodec.encode(RuleConfig()).keys + RuleConfigCodec.LEGACY_POINT_KEYS
        val result = mutableMapOf<String, Int>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in checked) result[k] = intOf(o.opt(k), "config.$k")
        }
        return result
    }

    private fun childName(root: JSONObject): String = when (val v = root.opt("childName")) {
        null, JSONObject.NULL -> ""
        is String -> v.trim()
        else -> fail("childName 不是字符串")
    }

    private fun optArray(root: JSONObject, key: String): JSONArray? = when (val v = root.opt(key)) {
        null, JSONObject.NULL -> null
        is JSONArray -> v
        else -> fail("$key 不是数组")
    }

    private fun objectAt(arr: JSONArray, i: Int, name: String): JSONObject =
        arr.opt(i) as? JSONObject ?: fail("$name[$i] 不是对象")

    private fun reqInt(o: JSONObject, key: String, where: String): Int =
        intOf(o.opt(key), "$where.$key")

    /** 缺失按 0；存在则必须是整数。 */
    private fun optInt(o: JSONObject, key: String, where: String): Int {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) return 0
        return intOf(v, "$where.$key")
    }

    private fun optString(o: JSONObject, key: String, where: String): String =
        when (val v = o.opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> v
            else -> fail("$where.$key 不是字符串")
        }

    private fun reqBool(o: JSONObject, key: String, where: String): Boolean =
        o.opt(key) as? Boolean ?: fail("$where.$key 缺失或不是布尔值")

    private fun optBool(o: JSONObject, key: String, default: Boolean, where: String): Boolean =
        when (val v = o.opt(key)) {
            null, JSONObject.NULL -> default
            is Boolean -> v
            else -> fail("$where.$key 不是布尔值")
        }

    private fun intOf(v: Any?, where: String): Int {
        val l = longOf(v, where)
        if (l < Int.MIN_VALUE || l > Int.MAX_VALUE) fail("$where 超出范围：$l")
        return l.toInt()
    }

    /** 只接受整数值（含 `3.0` 这类整值小数），拒绝字符串、布尔与非整数。 */
    private fun longOf(v: Any?, where: String): Long = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Number -> {
            val d = v.toDouble()
            if (d.isNaN() || d.isInfinite() || d != Math.rint(d) || d > Long.MAX_VALUE || d < Long.MIN_VALUE) {
                fail("$where 不是整数：$v")
            }
            d.toLong()
        }
        null, JSONObject.NULL -> fail("$where 缺失")
        else -> fail("$where 不是整数：$v")
    }

    private fun replay(
        weeks: List<WeekEntity>,
        days: List<DayEntity>,
        exams: List<ExamEntity>,
        config: RuleConfig,
        itemDefs: List<ItemDef>
    ): List<WeekEntity> = BankCarry.replay(
        weeks = weeks,
        daysByWeek = days.groupBy({ it.weekStart }, { Mappers.run { it.toDomain() } }),
        examsByWeek = exams.groupBy({ it.weekStart }, { Mappers.run { it.toDomain() } }),
        cfg = config,
        catalog = ItemCatalog(itemDefs)
    )

    /** 日记录里出现、但目录里没有的 key：保留（计 0 分），只提示。 */
    private fun unknownItemWarning(days: List<DayEntity>, itemDefs: List<ItemDef>): String? {
        val known = itemDefs.map { it.item }.toSet()
        val unknown = days.flatMap { it.items + it.pending }.filter { it !in known }.map { it.key }.distinct()
        if (unknown.isEmpty()) return null
        return "日记录中有 ${unknown.size} 个考核项不在目录里，将计 0 分：${unknown.joinToString("、")}"
    }

    // ---- 确认框文案 --------------------------------------------------------------

    /** 与当前设置相比值得提醒的差异。目前只提示存钱上限变化。 */
    fun diffWarnings(plan: ImportPlan, current: RuleConfig): List<String> {
        val warnings = mutableListOf<String>()
        if (plan.config.savingCapCents != current.savingCapCents) {
            warnings += "存钱上限将改为 ${yuan(plan.config.savingCapCents)} 元" +
                "（当前 ${yuan(current.savingCapCents)} 元）"
        }
        return warnings
    }

    /** 确认框正文。 */
    fun confirmMessage(plan: ImportPlan, current: RuleConfig): String {
        val lines = mutableListOf(
            "文件版本：v${plan.schemaVersion}",
            "孩子姓名：${plan.childName.ifEmpty { "（未填写）" }}",
            "日记录：${plan.days.size} 天",
            "周记录：${plan.weeks.size} 周",
            "考试记录：${plan.exams.size} 条",
            "考核项：${plan.itemDefs.size} 项"
        )
        val warnings = plan.warnings + diffWarnings(plan, current)
        if (warnings.isNotEmpty()) {
            lines += ""
            lines += warnings.map { "· $it" }
        }
        lines += ""
        lines += "将覆盖全部现有数据和规则参数，无法撤销。"
        return lines.joinToString("\n")
    }

    /** 分转元：5000 → "50"，5050 → "50.50"。 */
    internal fun yuan(cents: Int): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents.toLong())
        val whole = abs / 100
        val frac = abs % 100
        return if (frac == 0L) "$sign$whole" else "$sign$whole.${frac.toString().padStart(2, '0')}"
    }
}
