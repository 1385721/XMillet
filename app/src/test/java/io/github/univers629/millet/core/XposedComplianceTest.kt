package io.github.univers629.millet.core

import io.github.univers629.millet.ipc.PowerKeeperBinderClient
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
        assertEquals(null, ActiveImeTracker.resolveDefaultImePackage(null))
    }
}
