package io.github.univers629.millet.ui.vm

import android.content.SharedPreferences
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.component.BottomBarDestination
import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.data.InstalledPackageSource
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.PolicyWriter
import io.github.univers629.millet.ui.data.RawPackageMeta
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.data.RuntimeEnvironmentSource
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import io.github.univers629.millet.ui.theme.UiThemeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainShellViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private class DirectWriter(private val prefs: SharedPreferences) : PolicyWriter {
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
        override fun putDetailedLogging(enabled: Boolean) = Unit
        override fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String) = Unit
        override fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate? = null
        override fun deleteCustomTemplate(id: String) = Unit
    }

    private class FakePackageSource : InstalledPackageSource {
        override suspend fun loadPrimaryPackages(): List<RawPackageMeta> = emptyList()
        override suspend fun loadDualPackages(primaryByPackage: Map<String, RawPackageMeta>) =
            emptyList<RawPackageMeta>() to false
    }

    private class FakeRuntimeSource : RuntimeEnvironmentSource {
        override suspend fun queryRuntimeStatus() = PowerKeeperBinderClient.RuntimeStatusSnapshot()
        override suspend fun queryRunningProcesses(): List<RunningProcessUiModel> = emptyList()
        override suspend fun loadFallbackCloudLists(): CloudListCodec.CloudLists? = null
        override suspend fun readBatteryUnrestrictedPackages(): Set<String> = emptySet()
        override suspend fun thawAllFrozenUids(): Int = 0
    }

    @Test
    fun adaptivePollIntervalProvisioningAndThemeSyncWork() = runTest(mainRule.dispatcher) {
        val prefs = InMemorySharedPreferences()
        val policyRepo = PolicyRepository(prefs, DirectWriter(prefs))
        val repoScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        val runtimeRepo = RuntimeAndAppsRepository(
            policyRepository = policyRepo,
            packageSource = FakePackageSource(),
            environmentSource = FakeRuntimeSource(),
            scope = repoScope,
            computeDispatcher = mainRule.dispatcher,
        )
        val themeState = UiThemeState(prefs)
        val vm = MainShellViewModel(
            themeState = themeState,
            initialProvisioned = false,
            policyRepo = policyRepo,
            runtimeRepo = runtimeRepo,
        )
        testScheduler.advanceUntilIdle()

        assertFalse(vm.isProvisioned.value)
        vm.markProvisioned()
        assertTrue(vm.isProvisioned.value)

        // 主页默认 15 秒；切到进程列表页变为 5 秒；进入子页后回到 15 秒
        assertEquals(15_000L, vm.currentPollIntervalMs())
        vm.onDestinationSettled(BottomBarDestination.Processes)
        assertEquals(5_000L, vm.currentPollIntervalMs())
        vm.onRouteChanged(onMain = false)
        assertEquals(15_000L, vm.currentPollIntervalMs())

        // 仓库更新 freezeStats 后，底栏 TombStats 可见性自动同步
        assertTrue(vm.themeState.showTombStatsTab)
        policyRepo.setBoolean(PrefsKeys.FREEZE_STATS, false)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.themeState.showTombStatsTab)

        repoScope.cancel()
    }
}
