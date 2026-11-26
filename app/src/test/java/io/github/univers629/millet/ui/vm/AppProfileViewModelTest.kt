package io.github.univers629.millet.ui.vm

import android.content.SharedPreferences
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.data.InstalledPackageSource
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.PolicyWriter
import io.github.univers629.millet.ui.data.RawPackageMeta
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.data.RuntimeEnvironmentSource
import io.github.univers629.millet.ui.screen.InstalledAppUiModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppProfileViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private class DirectWriter(private val prefs: SharedPreferences) : PolicyWriter {
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
        override fun putDetailedLogging(enabled: Boolean) = Unit
        override fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String) {
            val editor = prefs.edit()
            for ((k, v) in updates) editor.putStringSet(k, LinkedHashSet(v))
            editor.apply()
        }
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

    private class FakeBridge : AppProfileSystemBridge {
        var setBatteryCalls = 0
        var batteryWriteResult = true

        override suspend fun scanAppProcesses(uid: Int): List<RootHelper.ProcessRawEntry> = emptyList()
        override suspend fun readSystemBatteryPolicy(packageName: String) = false to "智能推荐"
        override suspend fun setIgnoringBatteryOptimization(packageName: String): Boolean {
            setBatteryCalls++
            return batteryWriteResult
        }
        override suspend fun restoreBatteryOptimization(packageName: String): Boolean = true
        override fun buildFallbackAppModel(packageName: String, uid: Int) = InstalledAppUiModel(
            appName = packageName,
            packageName = packageName,
            uid = uid,
            versionName = "1.0",
            versionCode = 1L,
            isSystem = false,
            iconSource = null,
            inWhiteSet = false,
            inMessageSet = false,
            inForceFreezeSet = false,
            inBlockThawSet = false,
            inForbidSyncSet = false,
            inNotFreezeFgsSet = false,
            inBatteryWhiteSet = false,
        )
    }

    @Test
    fun mutualExclusionAndBatteryRollbackWorkCorrectly() = runTest(mainRule.dispatcher) {
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
        val bridge = FakeBridge()
        val vm = AppProfileViewModel(
            packageName = "com.example.chat",
            uid = 10200,
            policyRepo = policyRepo,
            runtimeRepo = runtimeRepo,
            bridge = bridge,
            scanDelayMs = 0L,
        )
        val collector = launch { vm.uiState.collect() }
        val toasts = ArrayList<String>()
        val toastCollector = launch { vm.toastEvents.collect { toasts.add(it) } }
        testScheduler.advanceUntilIdle()

        // 开启强力冻结及其子项，再关闭强力冻结应连带清空子项
        vm.setForceFreeze(true)
        vm.setBlockThaw(true)
        vm.setForbidSyncBinder(true)
        vm.setIgnoreNetworkWake(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.inForceFreeze)
        assertTrue(vm.uiState.value.inBlockThaw)
        assertTrue(vm.uiState.value.inForbidSync)
        assertTrue(vm.uiState.value.inIgnoreNetworkWake)

        vm.setForceFreeze(false)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.inForceFreeze)
        assertFalse(vm.uiState.value.inBlockThaw)
        assertFalse(vm.uiState.value.inForbidSync)
        assertFalse(vm.uiState.value.inIgnoreNetworkWake)

        // 主用户开启白名单时，若系统省电写入失败则自动回滚并发送提示
        bridge.batteryWriteResult = false
        vm.setWhiteList(true)
        testScheduler.advanceUntilIdle()
        assertEquals(1, bridge.setBatteryCalls)
        assertFalse(vm.uiState.value.inWhite)
        assertEquals(1, toasts.size)

        collector.cancel()
        toastCollector.cancel()
        repoScope.cancel()
    }

    @Test
    fun dualAppSkipsSystemBatterySync() = runTest(mainRule.dispatcher) {
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
        val bridge = FakeBridge()
        val vm = AppProfileViewModel(
            packageName = "com.tencent.mm",
            uid = 99910123,
            policyRepo = policyRepo,
            runtimeRepo = runtimeRepo,
            bridge = bridge,
            scanDelayMs = 0L,
        )
        val collector = launch { vm.uiState.collect() }
        testScheduler.advanceUntilIdle()

        vm.setWhiteList(true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.inWhite)
        assertEquals(0, bridge.setBatteryCalls)

        collector.cancel()
        repoScope.cancel()
    }
}
