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
