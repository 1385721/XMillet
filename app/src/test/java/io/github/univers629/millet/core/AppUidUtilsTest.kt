package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUidUtilsTest {

    @Test
    fun `userId and appId extract primary and dual uids accurately`() {
        assertEquals(0, AppUidUtils.userIdOf(10277))
        assertEquals(10277, AppUidUtils.appIdOf(10277))
        assertEquals(999, AppUidUtils.userIdOf(99910277))
        assertEquals(10277, AppUidUtils.appIdOf(99910277))
        assertEquals(-1, AppUidUtils.userIdOf(-1))
    }

    @Test
    fun `isDualAppUid and isManagedAppUid enforce appId and userId boundaries`() {
        assertTrue(AppUidUtils.isManagedAppUid(10000))
        assertTrue(AppUidUtils.isManagedAppUid(10277))
        assertTrue(AppUidUtils.isManagedAppUid(19999))
        assertTrue(AppUidUtils.isManagedAppUid(99910277, includeDualApp = true))
        assertFalse(AppUidUtils.isManagedAppUid(99910277, includeDualApp = false))

        assertTrue(AppUidUtils.isDualAppUid(99910000))
        assertTrue(AppUidUtils.isDualAppUid(99919999))
        assertFalse(AppUidUtils.isDualAppUid(10277))
        assertFalse(AppUidUtils.isDualAppUid(99901000))
        assertFalse(AppUidUtils.isDualAppUid(99990001))

        assertFalse(AppUidUtils.isManagedAppUid(1000))
        assertFalse(AppUidUtils.isManagedAppUid(99000))
        assertFalse(AppUidUtils.isManagedAppUid(1010277))
    }

    @Test
    fun `dualUidOf computes matching clone uid and rejects non-app uids`() {
        assertEquals(99910277, AppUidUtils.dualUidOf(10277))
        assertEquals(99910277, AppUidUtils.dualUidOf(99910277))
        assertEquals(-1, AppUidUtils.dualUidOf(1000))
        assertEquals(-1, AppUidUtils.dualUidOf(99000))
        assertEquals(-1, AppUidUtils.dualUidOf(0))
    }

    @Test
    fun `query target encoding round-trips dual user cleanly`() {
        assertEquals("tv.danmaku.bili", AppUidUtils.encodeQueryTarget("tv.danmaku.bili", 10277))
        assertEquals("tv.danmaku.bili#999", AppUidUtils.encodeQueryTarget("tv.danmaku.bili", 99910277))
        assertEquals("tv.danmaku.bili" to 0, AppUidUtils.decodeQueryTarget("tv.danmaku.bili"))
        assertEquals("tv.danmaku.bili" to 999, AppUidUtils.decodeQueryTarget("tv.danmaku.bili#999"))
    }

    @Test
    fun `freezeDualApp default is true across PrefsDefaults`() {
        assertTrue(PrefsDefaults.booleanDefault(PrefsKeys.FREEZE_DUAL_APP))
        assertTrue(PrefsDefaults.booleanDefault(PrefsKeys.DISABLE_SCREEN_ON_THAW))
        assertTrue(PrefsKeys.FREEZE_DUAL_APP in PrefsKeys.ALL_KEYS)
        assertTrue(PrefsKeys.FREEZE_DUAL_APP in PrefGroup.MILLET_FLAGS.keys)
    }
}
