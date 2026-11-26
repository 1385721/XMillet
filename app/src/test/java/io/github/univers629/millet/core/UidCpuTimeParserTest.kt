package io.github.univers629.millet.core

import io.github.univers629.millet.system.FreezeStatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class UidCpuTimeParserTest {

    @Test
    fun `parseProcUidStat parses managed UIDs and ignores system or malformed lines`() {
        val raw = """
            1000: 5000000 1000000
            10123: 2400000 600000
            99910123: 1500000 500000
            90005: 900000 100000
            badline
        """.trimIndent()
        val parsed = UidCpuTimeParser.parseProcUidStat(raw)
        assertEquals(2, parsed.size)
        assertEquals(3000L, parsed[10123])
        assertEquals(2000L, parsed[99910123])
        assertNull(parsed[1000])
        assertNull(parsed[90005])
    }

    @Test
    fun `parseCgroupCpuStat prefers usage_usec and falls back to user plus system`() {
        val withUsage = """
            usage_usec 4500000
            user_usec 3000000
            system_usec 1500000
        """.trimIndent()
        assertEquals(4500L, UidCpuTimeParser.parseCgroupCpuStat(withUsage))

        val withoutUsage = """
            user_usec 1200000
            system_usec 800000
        """.trimIndent()
        assertEquals(2000L, UidCpuTimeParser.parseCgroupCpuStat(withoutUsage))
        assertNull(UidCpuTimeParser.parseCgroupCpuStat("nr_periods 10"))
    }

    @Test
    fun `engine records jitter diagnostics on sub-10s freeze rollback and aggregates continuous Doze sessions`() {
        var nowWall = 1_760_000_000_000L
        var nowElapsed = 100_000L
        val engine = FreezeStatsEngine(
            zoneId = ZoneId.of("UTC"),
            clock = { nowWall },
            elapsedClock = { nowElapsed },
            packageResolver = { uid -> if (uid == 10100) "com.example.chat" else null },
        )

        // Short freeze (< 10s) rolls back freezeCount but increments jitterCount + topJitterReason
        engine.recordFreeze(10100, "IMMOBULUS", "com.example.chat")
        nowWall += 3_000L
        nowElapsed += 3_000L
        engine.recordThaw(10100, "PACKET", "com.example.chat")

        // Micro Doze session (< 30s) is filtered out
        engine.onDozeStateObserved(5, nowWall, nowElapsed, 90, false) { CpuStatSource.KERNEL_READER to mapOf(10100 to 1000L) }
        nowWall += 10_000L
        nowElapsed += 10_000L
        engine.onDozeStateObserved(0, nowWall, nowElapsed, 90, false) { CpuStatSource.KERNEL_READER to mapOf(10100 to 5000L) }

        // Continuous Doze session (5 -> 6 -> 5 -> 0) lasting 120s
        engine.onDozeStateObserved(5, nowWall, nowElapsed, 90, false) { CpuStatSource.KERNEL_READER to mapOf(10100 to 10_000L) }
        nowWall += 60_000L
        nowElapsed += 60_000L
        engine.onDozeStateObserved(6, nowWall, nowElapsed, 89, false) { CpuStatSource.KERNEL_READER to emptyMap() }
        nowWall += 30_000L
        nowElapsed += 30_000L
        engine.onDozeStateObserved(5, nowWall, nowElapsed, 89, false) { CpuStatSource.KERNEL_READER to emptyMap() }
        nowWall += 30_000L
        nowElapsed += 30_000L
        engine.onDozeStateObserved(0, nowWall, nowElapsed, 88, false) { CpuStatSource.KERNEL_READER to mapOf(10100 to 12_500L) }

        val snap = engine.buildSnapshot(StatsWindow.TODAY)
        assertEquals(0, snap.freezeCount)
        val app = snap.apps.first { it.uid == 10100 }
        assertEquals(1, app.jitterCount)
        assertEquals("PACKET", app.topJitterReason)

        assertEquals(1, snap.dozeSummary.sessionCount)
        assertEquals(120_000L, snap.dozeSummary.totalDurationMillis)
        assertEquals(1, snap.dozeSummary.maintenanceCount)
        assertEquals(2, snap.dozeSummary.netBatteryDropPct)
        assertEquals(CpuStatSource.KERNEL_READER, snap.dozeSummary.cpuSource)
        assertEquals(2_500L, snap.dozeSummary.apps.first().cpuMillis)

        // IPC encode/decode round-trip preserves jitter & Doze summary
        val encoded = FreezeStatsCodec.encode(snap)
        val decoded = FreezeStatsCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.apps.first().jitterCount)
        assertEquals("PACKET", decoded.apps.first().topJitterReason)
        assertEquals(1, decoded.dozeSummary.sessionCount)
        assertEquals(2_500L, decoded.dozeSummary.apps.first().cpuMillis)
    }

    @Test
    fun `v3 storage round-trip preserves jitter and doze data`() {
        val state = FreezeStatsCodec.PersistedStatsState(
            baselineSinceMillis = 1000L,
            bootSinceMillis = 1000L,
            bootWallClockMillis = 1000L,
            bootElapsedMillis = 500L,
            savedAtMillis = 5000L,
            bootId = "boot-1",
            boot = FreezeStatsCodec.PersistedBootData(
                freezeCount = 1,
                screenOffWakeCount = 0,
                screenOffMillis = 0L,
                protectedCount = 0,
                killBlockedCount = 0,
                frozenMillisTotal = 20_000L,
                wakeByCategory = emptyMap(),
                interceptByRule = emptyMap(),
                apps = listOf(
                    FreezeStatsCodec.PersistedAppData(
                        uid = 10100,
                        packageName = "com.example.chat",
                        freezeCount = 1,
                        frozenMillis = 20_000L,
                        protectedCount = 0,
                        killBlockedCount = 0,
                        wakeByCategory = emptyMap(),
                        interceptByRule = emptyMap(),
                        rawReasons = emptyMap(),
                        jitterCount = 3,
                        jitterReasons = mapOf("PACKET" to 3),
                    )
                ),
                doze = FreezeStatsCodec.PersistedDozeScopeData(
                    sessionCount = 1,
                    totalDurationMillis = 60_000L,
                    maintenanceCount = 1,
                    netBatteryDropPct = 1,
                    unpluggedDurationMillis = 60_000L,
                    cpuSource = CpuStatSource.CGROUP_CPU_STAT,
                    apps = listOf(DozeAppCpuData(10100, "com.example.chat", 1500L, 1)),
                ),
            ),
            days = emptyList(),
            activeFrozenSinceByUid = emptyMap(),
            recent = emptyList(),
            dozeSessions = listOf(
                DozeSessionData(
                    startMillis = 2000L,
                    endMillis = 62_000L,
                    durationMillis = 60_000L,
                    maintenanceCount = 1,
                    startBatteryPct = 80,
                    endBatteryPct = 79,
                    chargedDuringSession = false,
                    topApps = listOf(DozeAppCpuData(10100, "com.example.chat", 1500L, 1)),
                )
            ),
        )
        val raw = FreezeStatsCodec.encodeStorage(state)
        assertTrue(raw.startsWith("v3\n"))
        val decoded = FreezeStatsCodec.decodeStorage(raw)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.boot!!.apps.first().jitterCount)
        assertEquals(CpuStatSource.CGROUP_CPU_STAT, decoded.boot!!.doze.cpuSource)
        assertEquals(1500L, decoded.boot!!.doze.apps.first().cpuMillis)
        assertEquals(1, decoded.dozeSessions.size)
    }
}
