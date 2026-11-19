package io.github.univers629.millet.core

import io.github.univers629.millet.system.ProcessSnapshotProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveImeAndMemoryTest {

    @Test
    fun `parseImePackage extracts package name from component id`() {
        assertEquals(
            "com.iflytek.inputmethod.miui",
            ActiveImeTracker.parseImePackage("com.iflytek.inputmethod.miui/.FlyIME")
        )
        assertEquals(
            "com.sohu.inputmethod.sogou.xiaomi",
            ActiveImeTracker.parseImePackage(" com.sohu.inputmethod.sogou.xiaomi/.SogouIME ")
        )
        assertNull(ActiveImeTracker.parseImePackage(null))
        assertNull(ActiveImeTracker.parseImePackage(""))
        assertNull(ActiveImeTracker.parseImePackage("invalid"))
    }

    @Test
    fun `parseAccessibilityPackages parses enabled services only when accessibility is enabled`() {
        val raw = "com.example.skip/.SkipService : com.google.android.marvin.talkback/.TalkBackService:invalid"
        assertTrue(ActiveImeTracker.parseAccessibilityPackages(0, raw).isEmpty())
        assertEquals(
            setOf("com.example.skip", "com.google.android.marvin.talkback"),
            ActiveImeTracker.parseAccessibilityPackages(1, raw)
        )
    }

    @Test
    fun `isToFrontReason recognizes activity start alongside resume and front`() {
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Start"))
        assertTrue(FreezeReasonClassifier.isToFrontReason(" Activity Start!"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Front"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("Activity Resume"))
        assertTrue(FreezeReasonClassifier.isToFrontReason("toFrontActivity"))
    }

    @Test
    fun `parsePssKb reads kernel Pss field from smaps_rollup`() {
        val sample = """
            00000000-ffffffff ---p 00000000 00:00 0 [rollup]
            Rss:              263776 kB
            Pss:               47557 kB
            Pss_Dirty:         30680 kB
            SwapPss:          323894 kB
        """.trimIndent()
        assertEquals(47557L, ProcessSnapshotProvider.parsePssKb(sample))
        assertNull(ProcessSnapshotProvider.parsePssKb(""))
        assertNull(ProcessSnapshotProvider.parsePssKb("Rss: 100 kB\n"))
    }
}
