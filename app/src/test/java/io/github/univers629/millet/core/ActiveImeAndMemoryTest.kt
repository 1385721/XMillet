package io.github.univers629.millet.core

import io.github.univers629.millet.system.ProcessSnapshotProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
