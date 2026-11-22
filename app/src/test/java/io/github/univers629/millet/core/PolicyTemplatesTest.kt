package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTemplatesTest {

    @Test
    fun `built-in presets are valid and distinct`() {
        val presets = PolicyTemplatePresets.ALL
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        assertEquals(presets.size, presets.map { it.flags }.toSet().size)
        for (preset in presets) {
            assertTrue("preset ${preset.id} should be marked builtIn", preset.builtIn)
            assertTrue("preset ${preset.id} should have non-empty flags", preset.flags.isNotEmpty())
            assertTrue("preset ${preset.id} should be valid", PolicyRules.isValid(preset.flags))
            assertNotNull(PolicyTemplatePresets.find(preset.id))
        }
    }

    @Test
    fun `toggle WHITE clears all conflicting flags`() {
        val initial = setOf(
            PolicyFlag.FORCE_FREEZE,
            PolicyFlag.BLOCK_THAW,
            PolicyFlag.FORBID_SYNC,
            PolicyFlag.IGNORE_NETWORK_WAKE,
        )
        assertEquals(setOf(PolicyFlag.WHITE), PolicyRules.toggle(initial, PolicyFlag.WHITE, true))
        assertEquals(emptySet<PolicyFlag>(), PolicyRules.toggle(setOf(PolicyFlag.WHITE), PolicyFlag.WHITE, false))
    }

    @Test
    fun `toggle MESSAGE clears WHITE and IGNORE_NETWORK_WAKE`() {
        assertEquals(
            setOf(PolicyFlag.MESSAGE),
            PolicyRules.toggle(setOf(PolicyFlag.WHITE), PolicyFlag.MESSAGE, true),
        )
        val offline = setOf(
            PolicyFlag.FORCE_FREEZE,
            PolicyFlag.BLOCK_THAW,
            PolicyFlag.IGNORE_NETWORK_WAKE,
        )
        assertEquals(
            setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW, PolicyFlag.MESSAGE),
            PolicyRules.toggle(offline, PolicyFlag.MESSAGE, true),
        )
    }

    @Test
    fun `toggle FORCE_FREEZE and its dependent flags enforce dependency and cascade off`() {
        // 未开强制冻结时，依附项不可单独开启
        assertEquals(emptySet<PolicyFlag>(), PolicyRules.toggle(emptySet(), PolicyFlag.BLOCK_THAW, true))
        assertEquals(emptySet<PolicyFlag>(), PolicyRules.toggle(emptySet(), PolicyFlag.FORBID_SYNC, true))
        assertEquals(emptySet<PolicyFlag>(), PolicyRules.toggle(emptySet(), PolicyFlag.IGNORE_NETWORK_WAKE, true))

        // 开强制冻结会关闭白名单与前台服务不冻结
        assertEquals(
            setOf(PolicyFlag.FORCE_FREEZE),
            PolicyRules.toggle(setOf(PolicyFlag.WHITE), PolicyFlag.FORCE_FREEZE, true),
        )
        assertEquals(
            setOf(PolicyFlag.FORCE_FREEZE),
            PolicyRules.toggle(setOf(PolicyFlag.NOT_FREEZE_FGS), PolicyFlag.FORCE_FREEZE, true),
        )

        // 开强制冻结后可开启依附项；忽略网络唤醒会关闭消息应用
        var state = setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.MESSAGE)
        state = PolicyRules.toggle(state, PolicyFlag.BLOCK_THAW, true)
        state = PolicyRules.toggle(state, PolicyFlag.FORBID_SYNC, true)
        state = PolicyRules.toggle(state, PolicyFlag.IGNORE_NETWORK_WAKE, true)
        assertEquals(
            setOf(
                PolicyFlag.FORCE_FREEZE,
                PolicyFlag.BLOCK_THAW,
                PolicyFlag.FORBID_SYNC,
                PolicyFlag.IGNORE_NETWORK_WAKE,
            ),
            state,
        )

        // 关闭强制冻结时连带清空三个依附项
        assertEquals(emptySet<PolicyFlag>(), PolicyRules.toggle(state, PolicyFlag.FORCE_FREEZE, false))
    }

    @Test
    fun `toggle NOT_FREEZE_FGS clears WHITE and FORCE_FREEZE cascade`() {
        assertEquals(
            setOf(PolicyFlag.NOT_FREEZE_FGS),
            PolicyRules.toggle(setOf(PolicyFlag.WHITE), PolicyFlag.NOT_FREEZE_FGS, true),
        )
        val offline = setOf(
            PolicyFlag.FORCE_FREEZE,
            PolicyFlag.BLOCK_THAW,
            PolicyFlag.FORBID_SYNC,
            PolicyFlag.IGNORE_NETWORK_WAKE,
        )
        assertEquals(
            setOf(PolicyFlag.NOT_FREEZE_FGS),
            PolicyRules.toggle(offline, PolicyFlag.NOT_FREEZE_FGS, true),
        )
    }

    @Test
    fun `isValid rejects illegal combinations and every toggle result stays valid`() {
        assertFalse(PolicyRules.isValid(setOf(PolicyFlag.WHITE, PolicyFlag.MESSAGE)))
        assertFalse(PolicyRules.isValid(setOf(PolicyFlag.BLOCK_THAW)))
        assertFalse(PolicyRules.isValid(setOf(PolicyFlag.FORBID_SYNC)))
        assertFalse(PolicyRules.isValid(setOf(PolicyFlag.IGNORE_NETWORK_WAKE)))
        assertFalse(PolicyRules.isValid(setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.NOT_FREEZE_FGS)))
        assertFalse(PolicyRules.isValid(setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.MESSAGE, PolicyFlag.IGNORE_NETWORK_WAKE)))

        val maxMask = 1 shl PolicyFlag.entries.size
        for (mask in 0 until maxMask) {
            val state = PolicyFlag.fromMask(mask)
            if (!PolicyRules.isValid(state)) continue
            for (flag in PolicyFlag.entries) {
                assertTrue(PolicyRules.isValid(PolicyRules.toggle(state, flag, true)))
                assertTrue(PolicyRules.isValid(PolicyRules.toggle(state, flag, false)))
            }
        }
    }

    @Test
    fun `codec round-trips custom templates and skips built-in or malformed entries`() {
        val custom1 = PolicyTemplateCodec.custom(
            id = "custom_1",
            name = "夜间;强冻",
            flags = setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW),
        )
        val custom2 = PolicyTemplateCodec.custom(
            id = "custom_2",
            name = "音乐与消息",
            flags = setOf(PolicyFlag.MESSAGE, PolicyFlag.NOT_FREEZE_FGS),
        )
        val encoded = PolicyTemplateCodec.encode(PolicyTemplatePresets.ALL + listOf(custom1, custom2))
        val decoded = PolicyTemplateCodec.decode(encoded)
        assertEquals(listOf(custom1, custom2), decoded)

        // 坏头、非法组合、空名、重复 ID 与非 custom_ 前缀都会被丢弃
        val invalidMask = PolicyFlag.toMask(setOf(PolicyFlag.WHITE, PolicyFlag.FORCE_FREEZE))
        val validMask = PolicyFlag.toMask(setOf(PolicyFlag.MESSAGE))
        val encodedName = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("测试".toByteArray(Charsets.UTF_8))
        val encodedDup = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("重复".toByteArray(Charsets.UTF_8))
        val dirty = buildString {
            appendLine("v1")
            appendLine("builtin_fake;$validMask;$encodedName")
            appendLine("custom_bad_combo;$invalidMask;$encodedName")
            appendLine("custom_empty_name;$validMask;")
            appendLine("custom_ok;$validMask;$encodedName")
            appendLine("custom_ok;$validMask;$encodedDup")
            appendLine("malformed_line")
        }
        val cleaned = PolicyTemplateCodec.decode(dirty)
        assertEquals(1, cleaned.size)
        assertEquals("custom_ok", cleaned.first().id)
        assertEquals("测试", cleaned.first().name)
        assertEquals(setOf(PolicyFlag.MESSAGE), cleaned.first().flags)

        assertTrue(PolicyTemplateCodec.decode("v2\ncustom_ok;$validMask;$encodedName").isEmpty())
        assertTrue(PolicyTemplateCodec.decode(null).isEmpty())
    }

    @Test
    fun `template matches requires exact flag equality`() {
        val battery = requireNotNull(PolicyTemplatePresets.find("builtin_battery"))
        assertTrue(battery.matches(setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW, PolicyFlag.FORBID_SYNC)))
        assertFalse(battery.matches(setOf(PolicyFlag.FORCE_FREEZE)))
        assertFalse(
            battery.matches(
                setOf(
                    PolicyFlag.FORCE_FREEZE,
                    PolicyFlag.BLOCK_THAW,
                    PolicyFlag.FORBID_SYNC,
                    PolicyFlag.IGNORE_NETWORK_WAKE,
                )
            )
        )
    }
}
