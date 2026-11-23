package io.github.univers629.millet.system

import io.github.univers629.millet.core.PrefGroup
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.powerkeeper.PowerKeeperCloudLists
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 覆盖云白名单独立总开关配置真值与 OS3/OS4 `ListAppsManager` 热同步（屏蔽与恢复）逻辑。
 */
class CloudWhitelistControlTest {

    /** 模拟 HyperOS 3 `ListAppsManager` 字段布局（`mCloudWhiteList` / `mUseDataWhiteList`）。 */
    @Suppress("unused")
    private class FakeOs3ListAppsManager {
        companion object {
            @JvmField
            val mDataWhiteList: MutableList<String> = arrayListOf("com.builtin.shared")

            @JvmField
            val mMiDataWhiteList: MutableList<String> = arrayListOf("com.xiaomi.builtin")

            @JvmField
            val mSleepModeList: MutableList<String> = arrayListOf("com.sleep.keep")

            @JvmField
            val mCloudWhiteList: MutableSet<String> = LinkedHashSet()

            @JvmField
            val mUseDataWhiteList: MutableSet<String> = LinkedHashSet()

            @JvmStatic
            var dealCalls: Int = 0

            @JvmStatic
            fun dealCloudWhiteList(values: String?) {
                dealCalls++
                if (values.isNullOrBlank()) return
                for (name in values.split(";")) {
                    if (name.length > 5) {
                        mCloudWhiteList.add(name)
                        mUseDataWhiteList.add(name)
                    }
                }
            }

            fun reset() {
                mCloudWhiteList.clear()
                mUseDataWhiteList.clear()
                dealCalls = 0
            }
        }
    }

    /** 模拟 HyperOS 4 `ListAppsManager` 字段布局（`USE_CLOUD_WHITE_LIST` / `USE_DATA_WHITE_LIST`）。 */
    @Suppress("unused")
    private class FakeOs4ListAppsManager {
        companion object {
            @JvmField
            val USE_CLOUD_WHITE_LIST: MutableSet<String> = LinkedHashSet()

            @JvmField
            val USE_DATA_WHITE_LIST: MutableSet<String> = LinkedHashSet()

            @JvmStatic
            fun getUseDataWhiteList(): MutableSet<String> = USE_DATA_WHITE_LIST

            @JvmStatic
            fun dealCloudWhiteList(values: String?) {
                if (values.isNullOrBlank()) return
                for (name in values.split(";")) {
                    if (name.length > 5) {
                        USE_CLOUD_WHITE_LIST.add(name)
                        USE_DATA_WHITE_LIST.add(name)
                    }
                }
            }

            fun reset() {
                USE_CLOUD_WHITE_LIST.clear()
                USE_DATA_WHITE_LIST.clear()
            }
        }
    }

    @Before
    fun setUp() {
        SystemState.resetCloudFrozenWhiteListCacheForTest()
        FakeOs3ListAppsManager.reset()
        FakeOs4ListAppsManager.reset()
    }

    @After
    fun tearDown() {
        SystemState.resetCloudFrozenWhiteListCacheForTest()
        FakeOs3ListAppsManager.reset()
        FakeOs4ListAppsManager.reset()
    }

    @Test
    fun prefsKeysAndDefaults_areConsistentAndIndependentFromPowerSaveSwitch() {
        assertTrue(PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST in PrefsKeys.ALL_KEYS)
        assertTrue(PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST in PrefsKeys.ALL_KEYS)
        assertEquals(PrefGroup.MILLET_FLAGS, PrefGroup.fromKey(PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST))
        assertEquals(PrefGroup.MILLET_FLAGS, PrefGroup.fromKey(PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST))

        assertFalse(PrefsDefaults.BLOCK_CLOUD_DOZE_WHITELIST)
        assertTrue(PrefsDefaults.BLOCK_CLOUD_FROZEN_WHITELIST)
        assertFalse(PrefsDefaults.booleanDefault(PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST))
        assertTrue(PrefsDefaults.booleanDefault(PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST))

        val snapshot = PrefsSnapshot()
        assertFalse(snapshot.blockCloudDozeWhitelist)
        assertTrue(snapshot.blockCloudFrozenWhitelist)
        assertTrue(snapshot.blockAddPowerSaveWhitelistApps)
        assertFalse(PowerKeeperCloudLists.isBypassingFilter())
    }

    @Test
    fun os3ListAppsManager_blockRemovesCloudOnlyAndPreservesBuiltInVoiceAssistAndUserWhite() {
        FakeOs3ListAppsManager.mUseDataWhiteList.addAll(
            listOf(
                "com.builtin.shared",
                "com.xiaomi.builtin",
                "com.sleep.keep",
                "com.miui.voiceassist",
                "com.user.white",
                "com.cloud.only.a",
                "com.cloud.only.b"
            )
        )
        FakeOs3ListAppsManager.mCloudWhiteList.addAll(
            listOf(
                "com.builtin.shared",
                "com.xiaomi.builtin",
                "com.sleep.keep",
                "com.miui.voiceassist",
                "com.user.white",
                "com.cloud.only.a",
                "com.cloud.only.b"
            )
        )

        val removed = SystemState.syncListAppsManagerClass(
            clazz = FakeOs3ListAppsManager::class.java,
            block = true,
            userWhitePackages = setOf("com.user.white")
        )

        assertEquals(7, removed)
        assertTrue(FakeOs3ListAppsManager.mCloudWhiteList.isEmpty())
        assertFalse("com.cloud.only.a" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertFalse("com.cloud.only.b" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertTrue("com.builtin.shared" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertTrue("com.xiaomi.builtin" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertTrue("com.sleep.keep" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertTrue("com.miui.voiceassist" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertTrue("com.user.white" in FakeOs3ListAppsManager.mUseDataWhiteList)

        // 未捕获原始串时（例如热重载后），恢复应回填此前快照缓存的云名单条目。
        val restored = SystemState.syncListAppsManagerClass(
            clazz = FakeOs3ListAppsManager::class.java,
            block = false,
            userWhitePackages = setOf("com.user.white")
        )
        assertEquals(7, restored)
        assertEquals(0, FakeOs3ListAppsManager.dealCalls)
        assertTrue("com.cloud.only.a" in FakeOs3ListAppsManager.mCloudWhiteList)
        assertTrue("com.cloud.only.a" in FakeOs3ListAppsManager.mUseDataWhiteList)
        assertTrue("com.cloud.only.b" in FakeOs3ListAppsManager.mUseDataWhiteList)
    }

    @Test
    fun os3ListAppsManager_unblockReplaysCapturedRawStringViaDealCloudWhiteList() {
        SystemState.recordCloudFrozenWhiteListRaw(
            FakeOs3ListAppsManager::class.java,
            "com.cloud.replayed.one;com.cloud.replayed.two;short"
        )
        // 空串不会覆盖已捕获的有效原始串
        SystemState.recordCloudFrozenWhiteListRaw(FakeOs3ListAppsManager::class.java, "")

        val restored = SystemState.syncListAppsManagerClass(
            clazz = FakeOs3ListAppsManager::class.java,
            block = false
        )
        assertEquals(2, restored)
        assertEquals(1, FakeOs3ListAppsManager.dealCalls)
        assertEquals(
            setOf("com.cloud.replayed.one", "com.cloud.replayed.two"),
            FakeOs3ListAppsManager.mCloudWhiteList
        )
        assertEquals(
            setOf("com.cloud.replayed.one", "com.cloud.replayed.two"),
            FakeOs3ListAppsManager.mUseDataWhiteList
        )
    }

    @Test
    fun os4ListAppsManager_blockAndRestoreUseUppercaseStaticSets() {
        FakeOs4ListAppsManager.USE_DATA_WHITE_LIST.addAll(
            listOf("com.miui.voiceassist", "com.os4.cloud.app", "com.os4.user.white")
        )
        FakeOs4ListAppsManager.USE_CLOUD_WHITE_LIST.addAll(
            listOf("com.miui.voiceassist", "com.os4.cloud.app", "com.os4.user.white")
        )

        val removed = SystemState.syncListAppsManagerClass(
            clazz = FakeOs4ListAppsManager::class.java,
            block = true,
            userWhitePackages = setOf("com.os4.user.white")
        )
        assertEquals(3, removed)
        assertTrue(FakeOs4ListAppsManager.USE_CLOUD_WHITE_LIST.isEmpty())
        assertFalse("com.os4.cloud.app" in FakeOs4ListAppsManager.USE_DATA_WHITE_LIST)
        assertTrue("com.miui.voiceassist" in FakeOs4ListAppsManager.USE_DATA_WHITE_LIST)
        assertTrue("com.os4.user.white" in FakeOs4ListAppsManager.USE_DATA_WHITE_LIST)

        val restored = SystemState.syncListAppsManagerClass(
            clazz = FakeOs4ListAppsManager::class.java,
            block = false,
            userWhitePackages = setOf("com.os4.user.white")
        )
        assertEquals(3, restored)
        assertTrue("com.os4.cloud.app" in FakeOs4ListAppsManager.USE_CLOUD_WHITE_LIST)
        assertTrue("com.os4.cloud.app" in FakeOs4ListAppsManager.USE_DATA_WHITE_LIST)
    }
}
