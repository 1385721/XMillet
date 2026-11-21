package io.github.univers629.millet.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedUidPresentationTest {
    @Test
    fun `member packages are distinct and deterministic`() {
        assertEquals(
            listOf("com.google.android.gms", "com.google.android.gsf"),
            sortedStatsMemberPackages(listOf("com.google.android.gsf", "", "com.google.android.gms", "com.google.android.gms")),
        )
    }

    @Test
    fun `missing membership stays unknown`() {
        assertTrue(sortedStatsMemberPackages(null).isEmpty())
        assertTrue(sortedStatsMemberPackages(listOf("", " ")).isEmpty())
        assertEquals("共享应用", statsGroupLabel(emptyList()))
    }

    @Test
    fun `two members show both names with package fallback`() {
        val members = listOf(
            StatsPackageMember("com.google.android.gms", "Google Play 服务"),
            StatsPackageMember("com.google.android.gsf", ""),
        )
        assertEquals("Google Play 服务 + com.google.android.gsf", statsGroupLabel(members))
        assertEquals(2, members.size)
    }

    @Test
    fun `larger groups bound the title without discarding members`() {
        val members = listOf(
            StatsPackageMember("first.app", "应用一"),
            StatsPackageMember("second.app", "应用二"),
            StatsPackageMember("third.app", "应用三"),
        )
        assertEquals("应用一 + 应用二 等3个应用", statsGroupLabel(members))
        assertEquals(3, members.size)
    }

    @Test
    fun `stack requires exactly two readable icons`() {
        assertTrue(canStackStatsMemberIcons(2, listOf(true, true)))
        assertFalse(canStackStatsMemberIcons(2, listOf(true, false)))
        assertFalse(canStackStatsMemberIcons(2, listOf(false, true)))
        assertFalse(canStackStatsMemberIcons(2, emptyList()))
        assertFalse(canStackStatsMemberIcons(1, listOf(true)))
        assertFalse(canStackStatsMemberIcons(3, listOf(true, true, true)))
    }
}
