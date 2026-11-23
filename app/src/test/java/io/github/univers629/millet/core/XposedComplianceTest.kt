package io.github.univers629.millet.core

import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ipc.HookCoverage
import io.github.univers629.millet.ui.component.HookDiagnosticLevel
import io.github.univers629.millet.ui.component.generateHookDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedComplianceTest {

    @Test
    fun frameworkState_detectsScopesAndStaleTargets() {
        val stateMissingPk = XposedPrefsBridge.XposedFrameworkState(
            isServiceConnected = true,
            apiVersion = 102,
            frameworkName = "LSPosed",
            frameworkVersion = "1.10.2",
            supportsSystemHook = true,
            supportsRemotePrefs = true,
            scope = listOf("android"),
            runningTargets = listOf(
                XposedPrefsBridge.HookTargetSnapshot("android", 1000, 1234, XposedPrefsBridge.HookTargetState.STALE, 1099),
                XposedPrefsBridge.HookTargetSnapshot("com.miui.powerkeeper", 1000, 5678, XposedPrefsBridge.HookTargetState.UP_TO_DATE, 1100),
            )
        )
        assertTrue(stateMissingPk.hasSystemScope)
        assertFalse(stateMissingPk.hasPowerKeeperScope)
        assertEquals(listOf("com.miui.powerkeeper"), stateMissingPk.missingScopes)
        assertEquals(1, stateMissingPk.staleTargets.size)
        assertFalse(LsposedHelper.hasExpectedScope(stateMissingPk))

        val completeState = stateMissingPk.copy(scope = listOf("system", "com.miui.powerkeeper"))
        assertTrue(completeState.hasSystemScope)
        assertTrue(completeState.hasPowerKeeperScope)
        assertTrue(completeState.missingScopes.isEmpty())
        assertTrue(LsposedHelper.hasExpectedScope(completeState))
    }

    @Test
    fun diagnostics_reportRemoteCapabilityMissingAndStaleTargets() {
        val runtime = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true,
            greezeReady = true,
            powerKeeperConnected = false
        )
        val noRemoteState = XposedPrefsBridge.XposedFrameworkState(
            isServiceConnected = true,
            apiVersion = 102,
            frameworkName = "TestFW",
            frameworkVersion = "1.0",
            supportsSystemHook = true,
            supportsRemotePrefs = false,
            scope = listOf("system")
        )
        val entries = generateHookDiagnostics(runtime, frameworkState = noRemoteState)
        val fwEntry = entries.first { it.id == "libxposed_service" }
        assertEquals(HookDiagnosticLevel.FAILED, fwEntry.level)
        assertFalse(fwEntry.optional)
        val pkEntry = entries.first { it.id == "powerkeeper" }
        assertFalse(pkEntry.optional)
    }

    @Test
    fun activeImeTracker_onHotReloadingIsIdempotentWithoutContext() {
        ActiveImeTracker.onHotReloading()
        ActiveImeTracker.onHotReloading()
        val stopped = ActiveImeTracker::class.java.getDeclaredField("hotReloading").apply {
            isAccessible = true
        }
        assertTrue(stopped.getBoolean(ActiveImeTracker))
        assertEquals(null, ActiveImeTracker.resolveDefaultImePackage(null))
    }

    @Test
    fun kernelEvidence_requiresKnownSourceAndMatchingMode() {
        assertTrue(PowerKeeperBinderClient.KernelFreezerReport(RootHelper.KernelFreezer.CGROUP_V2, "CGROUP_V2_FROZEN").confirmed)
        assertTrue(PowerKeeperBinderClient.KernelFreezerReport(RootHelper.KernelFreezer.CGROUP_V1, "WCHAN_V1").confirmed)
        assertFalse(PowerKeeperBinderClient.KernelFreezerReport(RootHelper.KernelFreezer.CGROUP_V2, "WCHAN_V1").confirmed)
        assertFalse(PowerKeeperBinderClient.KernelFreezerReport(RootHelper.KernelFreezer.CGROUP_V2).confirmed)
        assertFalse(PowerKeeperBinderClient.KernelFreezerReport(RootHelper.KernelFreezer.UNKNOWN, "CGROUP_V2_FROZEN").confirmed)
    }

    @Test
    fun diagnostics_doNotAcceptClassPresenceAsOfficialRegistration() {
        val wire = HookInstallRegistry.Wire(installed = 1, total = 1)
        val runtime = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true,
            greezeReady = true,
            powerKeeperConnected = true,
            hookCoverage = HookCoverage(sys = wire, pk = wire, capabilities = mapOf(HookCoverage.CAP_CONTROLLER to true))
        )
        val entries = generateHookDiagnostics(runtime)
        assertEquals(HookDiagnosticLevel.WARNING, entries.first { it.id == "powerkeeper" }.level)
        assertEquals(HookDiagnosticLevel.WARNING, entries.first { it.id == "kernel_freezer" }.level)
    }

    @Test
    fun diagnostics_requireBothPositiveLedgers() {
        val wire = HookInstallRegistry.Wire(installed = 1, total = 1)
        val caps = mapOf("greeze_registered" to true, "controller_registered" to true)
        val base = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true, greezeReady = true, powerKeeperConnected = true,
            hookCoverage = HookCoverage(sys = wire, pk = wire, capabilities = caps)
        )
        assertEquals(HookDiagnosticLevel.PASSED, generateHookDiagnostics(base).first { it.id == "powerkeeper" }.level)
        for (invalid in listOf(HookInstallRegistry.Wire(), HookInstallRegistry.Wire(installed = 0, total = 0),
            HookInstallRegistry.Wire(installed = 1, total = 3),
            HookInstallRegistry.Wire(installed = 1, total = 3, unsupported = listOf("same"), gated = listOf("same")))) {
            val runtime = base.copy(hookCoverage = base.hookCoverage.copy(pk = invalid))
            assertEquals(HookDiagnosticLevel.WARNING, generateHookDiagnostics(runtime).first { it.id == "powerkeeper" }.level)
            assertEquals(HookDiagnosticLevel.WARNING, generateHookDiagnostics(runtime).first { it.id == "hook_coverage" }.level)
        }
    }
    @Test
    fun backendReadiness_requiresEnabledFlagsAndAllMonitors() {
        val classify = io.github.univers629.millet.system.KernelFreezerDetector::classifyBackend
        assertEquals("READY", classify(true, true, true, 7, 7))
        assertEquals("MONITORS_UNREADY", classify(true, true, true, 3, 7))
        assertEquals("DISABLED", classify(false, true, true, 7, 7))
        assertEquals("READY", classify(true, false, true, 7, 7))
        assertEquals("UNCONFIRMED", classify(true, true, false, 7, 7))
        assertEquals("DISABLED", classify(true, false, false, 7, 7))
        assertEquals("UNCONFIRMED", classify(null, true, true, 7, 7))
        assertEquals("UNCONFIRMED", classify(true, true, true, null, 7))
        assertEquals("UNCONFIRMED", classify(true, true, true, 7, 0))
    }

    @Test
    fun diagnostics_doNotSubstituteLaunchFreezeForBackendReadiness() {
        val wire = HookInstallRegistry.Wire(installed = 1, total = 1)
        val runtime = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true, greezeReady = true, powerKeeperConnected = true,
            hookCoverage = HookCoverage(sys = wire, pk = wire,
                capabilities = mapOf("greeze_registered" to true, "controller_registered" to true))
        )
        for (backend in listOf("UNCONFIRMED", "MONITORS_UNREADY", "DISABLED")) {
            val entries = generateHookDiagnostics(runtime, RootHelper.KernelFreezer.CGROUP_V2,
                kernelEvidenceConfirmed = true, kernelEvidenceSource = "CGROUP_V2_FROZEN", milletBackendState = backend)
            assertEquals(HookDiagnosticLevel.PASSED, entries.first { it.id == "kernel_freezer" }.level)
            val backendEntry = entries.first { it.id == "millet_backend" }
            assertFalse(backendEntry.optional)
            assertFalse(backendEntry.level == HookDiagnosticLevel.PASSED)
            assertFalse(entries.filterNot { it.optional }.all { it.level == HookDiagnosticLevel.PASSED })
        }
    }
}
