package io.github.univers629.millet.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot = PowerKeeperBinderClient.RuntimeStatusSnapshot(),
    val configuredRuleCount: Int = 0,
    val runningProcesses: List<RunningProcessUiModel> = emptyList(),
    val snapshotUnavailable: Boolean = false,
)

/**
 * 主页 ViewModel：将已配置应用计数等衍生计算移出组合阶段，仅在后台线程随仓库流更新。
 */
class HomeViewModel(
    private val runtimeRepo: RuntimeAndAppsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val configuredRuleCountFlow = runtimeRepo.installedAppsFlow
        .map { apps ->
            apps.asSequence()
                .filter { it.hasAnyConfig }
                .map { "${it.packageName}:${it.uid}" }
                .distinct()
                .count()
        }
        .distinctUntilChanged()
        .flowOn(computeDispatcher)

    val uiState: StateFlow<HomeUiState> = combine(
        runtimeRepo.runtimeStatusFlow,
        configuredRuleCountFlow,
        runtimeRepo.runningProcessesFlow,
        runtimeRepo.processSnapshotUnavailableFlow,
    ) { status, ruleCount, procs, unavailable ->
        HomeUiState(
            runtimeStatus = status,
            configuredRuleCount = ruleCount,
            runningProcesses = procs,
            snapshotUnavailable = unavailable,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(),
    )

    fun refresh() {
        viewModelScope.launch {
            runtimeRepo.refreshRuntimeAndProcesses(visible = true)
            runtimeRepo.refreshInstalledPackages(force = true, visible = true)
        }
    }

    fun thawAll() {
        viewModelScope.launch {
            runtimeRepo.thawAllFrozenUids()
        }
    }
}
