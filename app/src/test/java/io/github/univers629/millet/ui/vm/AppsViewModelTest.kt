package io.github.univers629.millet.ui.vm

import android.content.SharedPreferences
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.data.InMemorySharedPreferences
import io.github.univers629.millet.ui.data.InstalledPackageSource
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.PolicyWriter
import io.github.univers629.millet.ui.data.RawPackageMeta
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.data.RuntimeEnvironmentSource
import io.github.univers629.millet.ui.screen.AppCategoryFilter
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {

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
        override suspend fun loadPrimaryPackages(): List<RawPackageMeta> = listOf(
            RawPackageMeta("微信", "com.tencent.mm", 10123, "8.0", 1L, isSystem = false),
            RawPackageMeta("淘宝", "com.taobao.taobao", 10124, "10.0", 1L, isSystem = false),
            RawPackageMeta("系统相册", "com.miui.gallery", 10045, "1.0", 1L, isSystem = true),
        )

        override suspend fun loadDualPackages(primaryByPackage: Map<String, RawPackageMeta>) =
            emptyList<RawPackageMeta>() to false
    }

    private class FakeRuntimeSource : RuntimeEnvironmentSource {
        override suspend fun queryRuntimeStatus() = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true,
            cloudLists = CloudListCodec.CloudLists(
                dozeWhiteList = setOf("com.taobao.taobao"),
                frozenNewWhiteList = emptySet(),
                launchRestrict = emptySet(),
            ),
        )
        override suspend fun queryRunningProcesses(): List<RunningProcessUiModel> = emptyList()
        override suspend fun loadFallbackCloudLists(): CloudListCodec.CloudLists? = null
        override suspend fun readBatteryUnrestrictedPackages(): Set<String> = emptySet()
        override suspend fun thawAllFrozenUids(): Int = 0
    }

    @Test
    fun configuredSystemAppRemainsVisibleAndCountsUpdateLive() = runTest(mainRule.dispatcher) {
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
        val vm = AppsViewModel(policyRepo, runtimeRepo, computeDispatcher = mainRule.dispatcher)
        val collector = launch { vm.uiState.collect() }

        runtimeRepo.refreshRuntimeAndProcesses()
        runtimeRepo.refreshInstalledPackages()
        testScheduler.advanceUntilIdle()

        // 默认不显示未配置的系统应用：微信（默认在消息应用）+ 淘宝 = 2
        assertEquals(2, vm.uiState.value.filteredApps.size)
        assertEquals(1, vm.uiState.value.categoryCounts[AppCategoryFilter.CLOUD.ordinal])

        // 给系统应用配置强力冻结：即使 showSystemApps = false 也必须保留可见
        policyRepo.updateAppMemberships("com.miui.gallery", 10045, PrefsKeys.FORCE_FREEZE_SET to true)
        testScheduler.advanceUntilIdle()

        assertEquals(3, vm.uiState.value.filteredApps.size)
        assertEquals(1, vm.uiState.value.categoryCounts[AppCategoryFilter.FORCE_FREEZE.ordinal])

        // 切换分类到云白名单
        vm.setSelectedCategoryIndex(AppCategoryFilter.CLOUD.ordinal)
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.filteredApps.size)
        assertTrue(vm.uiState.value.filteredApps.first().packageName == "com.taobao.taobao")

        collector.cancel()
        repoScope.cancel()
    }
}
