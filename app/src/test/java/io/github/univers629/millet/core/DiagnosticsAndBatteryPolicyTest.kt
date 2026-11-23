package io.github.univers629.millet.core

import io.github.univers629.millet.ipc.HookCoverage
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.powerkeeper.PowerKeeperState
import io.github.univers629.millet.ui.component.HookDiagnosticLevel
import io.github.univers629.millet.ui.component.buildHookCheckReportText
import io.github.univers629.millet.ui.component.generateHookDiagnostics
import io.github.univers629.millet.ui.component.parseConsoleDetailRows
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 覆盖：
 * 1. 系统设置手动省电策略租约（P31/P32 ↔ P7/P8）放行与拦截边界；
 * 2. HyperOS 3 / 4 PowerKeeper 控制器注册判定（RomCapabilities.resolveControllerRegistered）；
 * 3. 环境检测诊断条目明细行（consoleLines）与报告文本导出（防回归 LocalTime 格式化崩溃）。
 */
class DiagnosticsAndBatteryPolicyTest {

    @Before
    fun setUp() {
        PowerKeeperState.clearUserPowerSaveLeasesForTest()
    }

    @After
    fun tearDown() {
        PowerKeeperState.clearUserPowerSaveLeasesForTest()
    }

    @Test
    fun powerSaveLease_allowsMatchingUserIntentAndBlocksBackgroundTampering() {
        val now = 100_000L
        PowerKeeperState.recordUserPowerSaveIntent("io.github.univers629.millet", expectAdd = true, nowMs = now)

        val (allowed, blocked) = PowerKeeperState.filterPowerSaveWhitelistMutation(
            packages = arrayOf("io.github.univers629.millet", "com.xiaomi.market"),
            isAdd = true,
            nowMs = now + 1_200L,
        )
        assertEquals(listOf("io.github.univers629.millet"), allowed)
        assertEquals(listOf("com.xiaomi.market"), blocked)

        // 反向移除操作不命中「无限制」添加租约
        val (removeAllowed, removeBlocked) = PowerKeeperState.filterPowerSaveWhitelistMutation(
            packages = arrayOf("io.github.univers629.millet"),
            isAdd = false,
            nowMs = now + 1_500L,
        )
        assertTrue(removeAllowed.isEmpty())
        assertEquals(listOf("io.github.univers629.millet"), removeBlocked)

        // 超过租约窗口后恢复拦截
        val (expiredAllowed, expiredBlocked) = PowerKeeperState.filterPowerSaveWhitelistMutation(
            packages = arrayOf("io.github.univers629.millet"),
            isAdd = true,
            nowMs = now + PowerKeeperState.USER_POWER_SAVE_LEASE_MS + 1L,
        )
        assertTrue(expiredAllowed.isEmpty())
        assertEquals(listOf("io.github.univers629.millet"), expiredBlocked)
    }

    @Test
    fun powerSaveLease_ignoresReentrantSelfRevertInsideGetPowerSaveAppConfigure() {
        val now = 200_000L
        PowerKeeperState.enterGetPowerSaveConfigure()
        try {
            assertTrue(PowerKeeperState.isInsideGetPowerSaveConfigure())
            PowerKeeperState.recordUserPowerSaveIntent("io.github.univers629.millet", expectAdd = false, nowMs = now)
        } finally {
            PowerKeeperState.exitGetPowerSaveConfigure()
        }
        assertFalse(PowerKeeperState.isInsideGetPowerSaveConfigure())

        val (allowed, blocked) = PowerKeeperState.filterPowerSaveWhitelistMutation(
            packages = arrayOf("io.github.univers629.millet"),
            isAdd = false,
            nowMs = now + 500L,
        )
        assertTrue(allowed.isEmpty())
        assertEquals(listOf("io.github.univers629.millet"), blocked)
    }

    @Test
    fun resolveControllerRegistered_supportsBothOs3FrozenCtrlAndOs4KillActiveStack() {
        // OS3: 存在 FrozenAppController 时必须注册 P23_FROZEN_DECISION
        assertFalse(
            RomCapabilities.resolveControllerRegistered(
                installed = setOf("P22_KILL_POLICY", "P28_NO_RESTRICT"),
                hasFrozenController = true,
                hasKillController = true,
                hasActiveStateController = true,
            )
        )
        assertTrue(
            RomCapabilities.resolveControllerRegistered(
                installed = setOf("P23_FROZEN_DECISION"),
                hasFrozenController = true,
                hasKillController = true,
                hasActiveStateController = true,
            )
        )

        // OS4: FrozenAppController 已移除，要求 P22_KILL_POLICY + P28_NO_RESTRICT
        assertTrue(
            RomCapabilities.resolveControllerRegistered(
                installed = setOf("P22_KILL_POLICY", "P28_NO_RESTRICT", "P24_NO_CHARGE_THAW"),
                hasFrozenController = false,
                hasKillController = true,
                hasActiveStateController = true,
            )
        )
        assertFalse(
            RomCapabilities.resolveControllerRegistered(
                installed = setOf("P28_NO_RESTRICT"),
                hasFrozenController = false,
                hasKillController = true,
                hasActiveStateController = true,
            )
        )
        assertFalse(
            RomCapabilities.resolveControllerRegistered(
                installed = setOf("P22_KILL_POLICY"),
                hasFrozenController = false,
                hasKillController = true,
                hasActiveStateController = true,
            )
        )
    }

    @Test
    fun diagnostics_os4ControllerChainPassesAndExposesDetailedConsoleLines() {
        val sysWire = HookInstallRegistry.Wire(installed = 29, total = 35, gated = listOf("S16", "S17", "S18", "S30", "S31", "S10"))
        val pkWire = HookInstallRegistry.Wire(
            installed = 15,
            total = 28,
            unsupported = listOf("P6", "P2", "P3", "P9", "P10", "P5", "P11", "P23_FROZEN_DECISION", "P25_AUTO_KILL", "P20", "P17"),
            gated = listOf("P4", "P29"),
        )
        val caps = mapOf(
            "millet" to false,
            "controller" to true,
            "frozen_ctrl" to false,
            "migard" to false,
            "greeze_registered" to true,
            "millet_registered" to false,
            "controller_registered" to true,
        )
        val runtime = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true,
            greezeReady = true,
            aurogonReady = true,
            dozeReady = true,
            powerKeeperConnected = true,
            hookCoverage = HookCoverage(sys = sysWire, pk = pkWire, capabilities = caps),
        )
        val entries = generateHookDiagnostics(runtime, kernelFreezer = RootHelper.KernelFreezer.CGROUP_V2)

        val pkEntry = entries.first { it.id == "powerkeeper" }
        assertEquals(HookDiagnosticLevel.PASSED, pkEntry.level)
        assertTrue(pkEntry.summaryText.contains("OS4"))
        assertTrue(pkEntry.consoleLines.any { it.contains("frozenCtrl=0") && it.contains("ctrlReg=1") })

        val covEntry = entries.first { it.id == "hook_coverage" }
        assertEquals(HookDiagnosticLevel.PASSED, covEntry.level)
        assertTrue(covEntry.consoleLines.any { it.startsWith("系统侧: 29/35") })
        assertTrue(covEntry.consoleLines.any { it.startsWith("电量侧: 15/28") && it.contains("P23_FROZEN_DECISION") })

        val report = buildHookCheckReportText(
            envLines = listOf("XMillet 1.2.4(1240) | 2410DPN6CC | OS4.0.0.10.XPACNXM"),
            diagnostics = entries,
            rawCoverageWire = runtime.hookCoverage.encode(),
            timestamp = LocalDateTime.of(2026, 11, 23, 9, 30, 0),
        )
        assertTrue(report.contains("2026-11-23 09:30:00"))
        assertTrue(report.contains("Wire: sys="))
        assertTrue(report.contains("电量侧: 15/28"))
    }

    @Test
    fun diagnostics_missingHooksExposeConcreteGapAndTargetHint() {
        val sysWire = HookInstallRegistry.Wire(installed = 28, total = 35, gated = listOf("S16", "S17", "S18", "S30", "S31", "S10"), failed = listOf("S32_CLOUD_FROZEN_WL"))
        val pkWire = HookInstallRegistry.Wire(
            installed = 12,
            total = 28,
            unsupported = listOf("P6", "P2", "P3", "P9", "P10", "P5", "P11", "P23_FROZEN_DECISION", "P25_AUTO_KILL", "P20", "P17", "P22_KILL_POLICY"),
            gated = listOf("P4", "P29"),
            failed = listOf("P28_NO_RESTRICT", "P27"),
        )
        val caps = mapOf(
            "millet" to false,
            "controller" to true,
            "frozen_ctrl" to false,
            "greeze_registered" to true,
            "millet_registered" to false,
            "controller_registered" to false,
        )
        val runtime = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true,
            greezeReady = true,
            powerKeeperConnected = true,
            hookCoverage = HookCoverage(sys = sysWire, pk = pkWire, capabilities = caps),
        )
        val entries = generateHookDiagnostics(runtime)
        val pkEntry = entries.first { it.id == "powerkeeper" }
        assertEquals(HookDiagnosticLevel.WARNING, pkEntry.level)
        assertTrue(pkEntry.consoleLines.any { it.contains("缺口:") && it.contains("P28_NO_RESTRICT") })

        val covEntry = entries.first { it.id == "hook_coverage" }
        assertEquals(HookDiagnosticLevel.WARNING, covEntry.level)
        assertTrue(covEntry.consoleLines.any { it.contains("失败目标:") && it.contains("ListAppsManager#dealCloudWhiteList") })
    }

    @Test
    fun parseConsoleDetailRows_formatsCoverageCapabilitiesAndAlertsCleanly() {
        val rows = parseConsoleDetailRows(
            listOf(
                "系统侧: 29/35 | 门控=S16,S17,S18",
                "电量侧: 15/28 | 未支持=P6,P23_FROZEN_DECISION | 门控=P4,P29",
                "握手=已连接 | 能力: millet=0 ctrl=1 frozenCtrl=0 migard=0 | 注册: milletReg=0 ctrlReg=1",
                "缺口: PowerKeeper 关键策略点位未注册 | 失败=P28_NO_RESTRICT(ActiveStateController#dealNoRestrictApp),P27(GlobalFeatureConfigureHelper#updateGlobalConfigure) | 宿主缺失=P22_KILL_POLICY",
                "目标进程: android=UP_TO_DATE(v1240), com.miui.powerkeeper=UP_TO_DATE(v1240)",
            )
        )

        assertEquals("系统侧", rows[0].label)
        assertEquals("29/35", rows[0].value)
        assertFalse(rows[0].isSubRow)

        assertEquals("门控", rows[1].label)
        assertEquals("S16, S17, S18", rows[1].value)
        assertTrue(rows[1].isSubRow)

        assertEquals("电量侧", rows[2].label)
        assertEquals("15/28", rows[2].value)

        assertEquals("未支持", rows[3].label)
        assertEquals("P6, P23_FROZEN_DECISION", rows[3].value)
        assertTrue(rows[3].isSubRow)

        assertEquals("门控", rows[4].label)
        assertEquals("P4, P29", rows[4].value)

        assertEquals("握手", rows[5].label)
        assertEquals("已连接", rows[5].value)
        assertEquals("能力", rows[6].label)
        assertTrue(rows[6].value.contains("frozenCtrl") && rows[6].value.contains("ctrl"))
        assertEquals("注册", rows[7].label)
        assertTrue(rows[7].value.contains("ctrlReg"))

        val gapRow = rows.first { it.label == "缺口" }
        assertTrue(gapRow.isAlert)
        val failSubRow = rows.first { it.label == "失败" }
        assertTrue(failSubRow.isAlert && failSubRow.isSubRow)
        assertTrue(failSubRow.value.contains("\n"))
        val missingSubRow = rows.first { it.label == "宿主缺失" }
        assertTrue(missingSubRow.isAlert && missingSubRow.isSubRow)
        assertEquals("P22_KILL_POLICY", missingSubRow.value)

        val procRow = rows.first { it.label == "进程" }
        assertEquals(
            "android=UP_TO_DATE(v1240)\ncom.miui.powerkeeper=UP_TO_DATE(v1240)",
            procRow.value,
        )
    }
}
