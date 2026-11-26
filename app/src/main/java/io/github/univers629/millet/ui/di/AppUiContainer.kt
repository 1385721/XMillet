package io.github.univers629.millet.ui.di

import android.content.Context
import android.content.SharedPreferences
import io.github.univers629.millet.MilletApplication
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.ui.data.AndroidFreezeStatsDataSource
import io.github.univers629.millet.ui.data.AndroidInstalledPackageSource
import io.github.univers629.millet.ui.data.AndroidRuntimeEnvironmentSource
import io.github.univers629.millet.ui.data.FreezeStatsDataSource
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 仅在 XMillet App UI 进程装配的轻量级依赖容器（不引入 Hilt/Koin，不触及 Hook 进程）。
 */
class AppUiContainer(context: Context) {

    val appContext: Context = context.applicationContext ?: context
    val uiPrefs: SharedPreferences = PrefReader.openUiPrefs(appContext)
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val policyRepository: PolicyRepository = PolicyRepository(
        prefs = uiPrefs,
        bindGlobalPrefReaderHook = true,
    )

    val runtimeAndAppsRepository: RuntimeAndAppsRepository = RuntimeAndAppsRepository(
        policyRepository = policyRepository,
        packageSource = AndroidInstalledPackageSource(appContext),
        environmentSource = AndroidRuntimeEnvironmentSource(appContext),
        scope = appScope,
    )

    val freezeStatsDataSource: FreezeStatsDataSource = AndroidFreezeStatsDataSource(appContext)
}

val Context.appUiContainer: AppUiContainer
    get() = (applicationContext as MilletApplication).uiContainer
