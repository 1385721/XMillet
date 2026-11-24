package io.github.univers629.millet.ui.data

import android.content.SharedPreferences
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ui.util.PolicyTemplateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRepositoryTest {

    private class FakePolicyWriter(private val prefs: SharedPreferences) : PolicyWriter {
        var lastNotifyKey: String? = null

        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
            lastNotifyKey = key
        }

        override fun putDetailedLogging(enabled: Boolean) {
            prefs.edit()
                .putBoolean(PrefsKeys.IS_LOG, enabled)
                .putBoolean(PrefsKeys.LOG_TO_LOCAL, enabled)
                .putBoolean(PrefsKeys.BATTERY_CHANGE_OBSERVE, enabled)
                .putBoolean(PrefsKeys.DOZE_LOG, enabled)
                .putString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL)
                .putString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE)
                .apply()
            lastNotifyKey = "*"
        }

        override fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String) {
            val editor = prefs.edit()
            for ((k, v) in updates) {
                editor.putStringSet(k, LinkedHashSet(v))
            }
            editor.apply()
            lastNotifyKey = notifyKey
        }

        override fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate? =
            PolicyTemplateStore.saveCustom(prefs, id, name, flags)

        override fun deleteCustomTemplate(id: String) {
            PolicyTemplateStore.deleteCustom(prefs, id)
        }
    }

    @Test
    fun mutationsUpdatePrefsFlowSynchronouslyAndSupportDualAppTargets() {
        val prefs = InMemorySharedPreferences()
        val writer = FakePolicyWriter(prefs)
        val repo = PolicyRepository(prefs, writer)

        assertEquals(PrefsDefaults.DISABLE_KILL, repo.prefsFlow.value.disableKill)
        repo.setBoolean(PrefsKeys.DISABLE_KILL, false)
        assertFalse(repo.prefsFlow.value.disableKill)

        repo.setDetailedLogging(true)
        assertTrue(repo.prefsFlow.value.isLog)
        assertTrue(repo.prefsFlow.value.logToLocal)
        assertTrue(repo.prefsFlow.value.dozeLog)

        // 主用户与双开实例独立读写，互不覆盖
        repo.updateAppMemberships(
            "com.tencent.mm",
            10123,
            PrefsKeys.WHITE_APP_SET to true,
            PrefsKeys.MESSAGE_APP_SET to false,
        )
        repo.updateAppMemberships(
            "com.tencent.mm",
            99910123,
            PrefsKeys.FORCE_FREEZE_SET to true,
        )
        assertTrue("com.tencent.mm" in repo.prefsFlow.value.whiteAppSet)
        assertFalse("com.tencent.mm" in repo.prefsFlow.value.messageAppSet)
        assertTrue("com.tencent.mm#999" in repo.prefsFlow.value.forceFreezeSet)
        assertFalse("com.tencent.mm" in repo.prefsFlow.value.forceFreezeSet)
    }

    @Test
    fun templatesFlowReflectsCreateAndDelete() {
        val prefs = InMemorySharedPreferences()
        val repo = PolicyRepository(prefs, FakePolicyWriter(prefs))
        val initialCount = repo.templatesFlow.value.size

        val created = repo.saveCustomTemplate(
            id = null,
            name = "夜间极严",
            flags = setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW),
        )
        assertNotNull(created)
        assertEquals(initialCount + 1, repo.templatesFlow.value.size)

        repo.deleteCustomTemplate(created!!.id)
        assertEquals(initialCount, repo.templatesFlow.value.size)
    }
}
