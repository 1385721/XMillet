package io.github.univers629.millet.ui.data

import android.content.SharedPreferences
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.model.AppIconSource
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeAndAppsRepositoryTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private class DirectPolicyWriter(private val prefs: SharedPreferences) : PolicyWriter {
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
        var primaryScanCount = 0
        var dualScanCount = 0
        var dualReady = false
        var scanGate: CompletableDeferred<Unit>? = null

        override suspend fun loadPrimaryPackages(): List<RawPackageMeta> {
            primaryScanCount++
            scanGate?.await()
            return listOf(
                RawPackageMeta(
                    appName = "微信",
                    packageName = "com.tencent.mm",
                    uid = 10123,
                    versionName = "8.0.50",
                    versionCode = 2800L,
                    isSystem = false,
                    iconSource = AppIconSource.forTest(10123, "com.tencent.mm"),
                ),
                RawPackageMeta(
                    appName = "淘宝",
                    packageName = "com.taobao.taobao",
                    uid = 10124,
                    versionName = "10.0.0",
                    versionCode = 1000L,
                    isSystem = false,
                    iconSource = AppIconSource.forTest(10124, "com.taobao.taobao"),
                ),
            )
        }

        override suspend fun loadDualPackages(
            primaryByPackage: Map<String, RawPackageMeta>,
        ): Pair<List<RawPackageMeta>, Boolean> {
            dualScanCount++
            if (!dualReady) return emptyList<RawPackageMeta>() to false
            return listOf(
                RawPackageMeta(
                    appName = "微信",
                    packageName = "com.tencent.mm",
                    uid = 99910123,
                    versionName = "8.0.50",
                    versionCode = 2800L,
                    isSystem = false,
                    iconSource = AppIconSource.forTest(99910123, "com.tencent.mm"),
                )
            ) to true
        }
    }

    private class FakeRuntimeSource : RuntimeEnvironmentSource {
        var status = PowerKeeperBinderClient.RuntimeStatusSnapshot(binderAlive = false)
        var processes: List<RunningProcessUiModel>? = emptyList()
        var unrestricted: Set<String> = emptySet()

        override suspend fun queryRuntimeStatus() = status
        override suspend fun queryRunningProcesses() = processes
        override suspend fun loadFallbackCloudLists(): CloudListCodec.CloudLists? = null
        override suspend fun readBatteryUnrestrictedPackages() = unrestricted
        override suspend fun thawAllFrozenUids(): Int = 0
    }

    @Test
    fun concurrentScansCoalesceAndPolicyChangesDoNotRescanPackages() = runTest(mainRule.dispatcher) {
        val prefs = InMemorySharedPreferences()
        val policyRepo = PolicyRepository(prefs, DirectPolicyWriter(prefs))
        val pkgSource = FakePackageSource().apply { scanGate = CompletableDeferred() }
        val runtimeSource = FakeRuntimeSource()
        val repoScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)

        val repo = RuntimeAndAppsRepository(
            policyRepository = policyRepo,
            packageSource = pkgSource,
            environmentSource = runtimeSource,
            scope = repoScope,
            computeDispatcher = mainRule.dispatcher,
        )

        // 模拟冷启动同时触发 3 次刷新：应当合并为 1 次 PackageManager 扫描
        val jobs = listOf(
            async { repo.refreshInstalledPackages() },
            async { repo.refreshInstalledPackages() },
            async { repo.refreshInstalledPackages() },
        )
        testScheduler.advanceUntilIdle()
        pkgSource.scanGate?.complete(Unit)
        jobs.awaitAll()
        testScheduler.advanceUntilIdle()

        assertEquals(1, pkgSource.primaryScanCount)
        assertEquals(2, repo.installedAppsFlow.value.size)

        // 切换策略开关：installedAppsFlow 纯内存即时更新，且 primaryScanCount 仍为 1（0 IPC）
        policyRepo.updateAppMemberships(
            "com.taobao.taobao",
            10124,
            PrefsKeys.FORCE_FREEZE_SET to true,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, pkgSource.primaryScanCount)
        val taobao = repo.installedAppsFlow.value.first { it.packageName == "com.taobao.taobao" }
        assertTrue(taobao.inForceFreezeSet)
        assertFalse(taobao.inWhiteSet)

        // Binder 晚到后上报云名单与双开应用：仅增量查询双开，不重扫主用户应用
        pkgSource.dualReady = true
        runtimeSource.status = PowerKeeperBinderClient.RuntimeStatusSnapshot(
            binderAlive = true,
            cloudLists = CloudListCodec.CloudLists(
                dozeWhiteList = setOf("com.tencent.mm"),
                frozenNewWhiteList = emptySet(),
                launchRestrict = emptySet(),
            ),
        )
        repo.refreshRuntimeAndProcesses()
        testScheduler.advanceUntilIdle()

        assertEquals(1, pkgSource.primaryScanCount)
        assertEquals(2, pkgSource.dualScanCount)
        assertEquals(3, repo.installedAppsFlow.value.size)
        val mm = repo.installedAppsFlow.value.first { it.packageName == "com.tencent.mm" && !it.isDualApp }
        assertTrue(mm.inCloudDozeWhite)

        repoScope.cancel()
    }
}
