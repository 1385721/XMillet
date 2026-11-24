package io.github.univers629.millet.ui.vm

import android.content.SharedPreferences
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.data.InstalledPackageSource
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.PolicyWriter
import io.github.univers629.millet.ui.data.RawPackageMeta
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.data.RuntimeEnvironmentSource
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessListViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private class NoopWriter(private val prefs: SharedPreferences) : PolicyWriter {
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

    private class FakeRuntimeSource(
        private val procs: List<RunningProcessUiModel>,
    ) : RuntimeEnvironmentSource {
        override suspend fun queryRuntimeStatus() = PowerKeeperBinderClient.RuntimeStatusSnapshot(binderAlive = true)
        override suspend fun queryRunningProcesses() = procs
        override suspend fun loadFallbackCloudLists(): CloudListCodec.CloudLists? = null
        override suspend fun readBatteryUnrestrictedPackages(): Set<String> = emptySet()
        override suspend fun thawAllFrozenUids(): Int = 0
    }

    @Test
    fun filtersByStatusSearchAndSystemFlag() = runTest(mainRule.dispatcher) {
        val prefs = InMemorySharedPreferences()
        val policyRepo = PolicyRepository(prefs, NoopWriter(prefs))
        val repoScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        val processes = listOf(
            RunningProcessUiModel("微信", "com.tencent.mm", 10123, listOf(1101), isFrozen = true, frozenCount = 1),
            RunningProcessUiModel("微信", "com.tencent.mm", 99910123, listOf(2202), isFrozen = false, isDualApp = true),
            RunningProcessUiModel("系统桌面", "com.miui.home", 10001, listOf(3303), isFrozen = false, unknownCount = 1, isSystem = true),
        )
        val runtimeRepo = RuntimeAndAppsRepository(
            policyRepository = policyRepo,
            packageSource = FakePackageSource(),
            environmentSource = FakeRuntimeSource(processes),
            scope = repoScope,
            computeDispatcher = mainRule.dispatcher,
        )
        val vm = ProcessListViewModel(runtimeRepo, computeDispatcher = mainRule.dispatcher)
        val collector = launch { vm.uiState.collect() }

        runtimeRepo.refreshRuntimeAndProcesses()
        testScheduler.advanceUntilIdle()

        assertEquals(3, vm.uiState.value.filteredProcesses.size)
        assertEquals(1, vm.uiState.value.frozenCount)
        assertEquals(1, vm.uiState.value.runningCount)

        // 按 FROZEN 过滤
        vm.setFilterIndex(ProcessFilterMode.FROZEN.ordinal)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.filteredProcesses.size)
        assertEquals(10123, vm.uiState.value.filteredProcesses.first().uid)

        // 搜索「双开」与 PID
        vm.setFilterIndex(ProcessFilterMode.ALL.ordinal)
        vm.setSearchQuery("双开")
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.filteredProcesses.size)
        assertEquals(99910123, vm.uiState.value.filteredProcesses.first().uid)

        vm.setSearchQuery("3303")
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.filteredProcesses.size)

        // 隐藏系统应用
        vm.toggleShowSystemApps()
        testScheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.filteredProcesses.size)

        collector.cancel()
        repoScope.cancel()
    }
}
