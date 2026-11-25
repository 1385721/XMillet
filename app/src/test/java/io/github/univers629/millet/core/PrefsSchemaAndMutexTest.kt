package io.github.univers629.millet.core

import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.PolicyWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsSchemaAndMutexTest {

    @Test
    fun `sanitizeFlags produces valid idempotent flag sets for all 128 bitmasks`() {
        for (mask in 0 until (1 shl PolicyFlag.entries.size)) {
            val raw = PolicyFlag.fromMask(mask)
            val sanitized = PolicyRules.sanitizeFlags(raw)
            assertTrue("mask=$mask -> $sanitized", PolicyRules.isValid(sanitized))
            assertEquals("idempotence mask=$mask", sanitized, PolicyRules.sanitizeFlags(sanitized))
        }
    }

    @Test
    fun `sanitizeFlags enforces safety-first priority`() {
        // WHITE beats everything else
        assertEquals(
            setOf(PolicyFlag.WHITE),
            PolicyRules.sanitizeFlags(
                setOf(PolicyFlag.WHITE, PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW),
            ),
        )
        // NOT_FREEZE_FGS beats FORCE_FREEZE, which strips orphan FORCE_DEPENDENTs
        assertEquals(
            setOf(PolicyFlag.NOT_FREEZE_FGS),
            PolicyRules.sanitizeFlags(
                setOf(PolicyFlag.NOT_FREEZE_FGS, PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW, PolicyFlag.FORBID_SYNC),
            ),
        )
        // MESSAGE beats IGNORE_NETWORK_WAKE while keeping FORCE_FREEZE
        assertEquals(
            setOf(PolicyFlag.MESSAGE, PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW),
            PolicyRules.sanitizeFlags(
                setOf(PolicyFlag.MESSAGE, PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW, PolicyFlag.IGNORE_NETWORK_WAKE),
            ),
        )
        // Orphan FORCE_DEPENDENT without FORCE_FREEZE is stripped
        assertEquals(
            emptySet<PolicyFlag>(),
            PolicyRules.sanitizeFlags(setOf(PolicyFlag.BLOCK_THAW, PolicyFlag.FORBID_SYNC)),
        )
    }

    @Test
    fun `PrefsSchema sanitizeSnapshot cleans dirty sets and invalid log values`() {
        val dirty = PrefsSnapshot(
            whiteAppSet = setOf("com.example.a", "bad target"),
            forceFreezeSet = setOf("com.example.a", "com.example.b"),
            blockThawSet = setOf("com.example.c"),
            messageAppSet = setOf("com.example.b"),
            ignoreNetworkWakeSet = setOf("com.example.b"),
            logType = "INVALID_TYPE",
            logLevel = "V",
        )
        val result = PrefsSchema.sanitizeSnapshot(dirty)
        assertTrue(result.modified)
        val clean = result.snapshot
        assertEquals(setOf("com.example.a"), clean.whiteAppSet)
        assertEquals(setOf("com.example.b"), clean.forceFreezeSet)
        assertTrue(clean.blockThawSet.isEmpty())
        assertEquals(setOf("com.example.b"), clean.messageAppSet)
        assertTrue(clean.ignoreNetworkWakeSet.isEmpty())
        assertEquals(PrefsDefaults.LOG_TYPE, clean.logType)
        assertEquals("info", clean.logLevel)
        assertFalse(PrefsSchema.sanitizeSnapshot(clean).modified)
    }

    @Test
    fun `PrefReader ensureUiPrefsSchema migrates dirty local prefs once`() {
        val sp = InMemorySharedPreferences()
        sp.edit()
            .putStringSet(PrefsKeys.WHITE_APP_SET, setOf("com.example.app"))
            .putStringSet(PrefsKeys.FORCE_FREEZE_SET, setOf("com.example.app"))
            .putString(PrefsKeys.LOG_LEVEL, "W")
            .apply()

        PrefReader.ensureUiPrefsSchema(sp)
        assertEquals(PrefsSchema.CURRENT_VERSION, sp.getInt(PrefsSchema.KEY_SCHEMA_VERSION, 0))
        val snap = PrefReader.readUiSnapshot(sp)
        assertEquals(setOf("com.example.app"), snap.whiteAppSet)
        assertTrue(snap.forceFreezeSet.isEmpty())
        assertEquals("warn", snap.logLevel)
    }

    @Test
    fun `PolicyRepository toggleAppPolicy delegates mutex rules cleanly`() {
        val sp = InMemorySharedPreferences()
        val writer = object : PolicyWriter {
            override fun putBoolean(key: String, value: Boolean) = Unit
            override fun putDetailedLogging(enabled: Boolean) = Unit
            override fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String) {
                val ed = sp.edit()
                for ((k, v) in updates) ed.putStringSet(k, LinkedHashSet(v))
                ed.apply()
            }
            override fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate? = null
            override fun deleteCustomTemplate(id: String) = Unit
        }
        val repo = PolicyRepository(sp, writer)
        repo.toggleAppPolicy("com.example.chat", 10100, PolicyFlag.FORCE_FREEZE, true)
        repo.toggleAppPolicy("com.example.chat", 10100, PolicyFlag.BLOCK_THAW, true)
        repo.toggleAppPolicy("com.example.chat", 10100, PolicyFlag.NOT_FREEZE_FGS, true)

        val snap = repo.prefsFlow.value
        assertTrue("com.example.chat" in snap.notFreezeWhenHasFgsSet)
        assertFalse("com.example.chat" in snap.forceFreezeSet)
        assertFalse("com.example.chat" in snap.blockThawSet)
    }
}
