package io.github.univers629.millet.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ProcessFilterMode(val label: String) {
    ALL("全部"),
    FROZEN("已冻结"),
    RUNNING("运行中"),
    UNKNOWN("待确认"),
}

@Immutable
data class ProcessListUiState(
    val filteredProcesses: List<RunningProcessUiModel> = emptyList(),
    val totalProcessCount: Int = 0,
    val frozenCount: Int = 0,
    val runningCount: Int = 0,
    val filterIndex: Int = 0,
    val searchQuery: String = "",
    val showSystemApps: Boolean = true,
    val isRefreshing: Boolean = false,
    val snapshotUnavailable: Boolean = false,
)

/**
 * 进程列表 ViewModel：在后台线程完成冻结态统计与搜索过滤，并跨配置变更保留筛选条件。
 */
class ProcessListViewModel(
    private val runtimeRepo: RuntimeAndAppsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val filterIndexFlow = MutableStateFlow(0)
    private val searchQueryFlow = MutableStateFlow("")
    private val showSystemAppsFlow = MutableStateFlow(true)

    val uiState: StateFlow<ProcessListUiState> = combine(
        runtimeRepo.runningProcessesFlow,
        runtimeRepo.isProcessesRefreshingFlow,
        runtimeRepo.processSnapshotUnavailableFlow,
        filterIndexFlow,
        searchQueryFlow,
        showSystemAppsFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val procs = values[0] as List<RunningProcessUiModel>
        val refreshing = values[1] as Boolean
        val unavailable = values[2] as Boolean
        val filterIndex = values[3] as Int
        val query = values[4] as String
        val showSystem = values[5] as Boolean

        val currentFilter = ProcessFilterMode.entries.getOrElse(filterIndex) { ProcessFilterMode.ALL }
        val frozenCount = procs.count { it.isFrozen }
        val runningCount = procs.count { !it.isFrozen && it.frozenCount == 0 && it.unknownCount == 0 }
        val q = query.trim().lowercase()
        val filtered = procs.filter { proc ->
            val filterMatch = when (currentFilter) {
                ProcessFilterMode.ALL -> true
                ProcessFilterMode.FROZEN -> proc.isFrozen
                ProcessFilterMode.RUNNING -> !proc.isFrozen && proc.frozenCount == 0 && proc.unknownCount == 0
                ProcessFilterMode.UNKNOWN -> proc.unknownCount > 0 || (!proc.isFrozen && proc.frozenCount > 0)
            }
            val queryMatch = q.isEmpty() ||
                proc.appName.lowercase().contains(q) ||
                proc.packageName.lowercase().contains(q) ||
                proc.uid.toString().contains(q) ||
                proc.pids.any { it.toString().contains(q) } ||
                (proc.isDualApp && "双开".contains(q))
            filterMatch && queryMatch && (showSystem || !proc.isSystem)
        }
        ProcessListUiState(
            filteredProcesses = filtered,
            totalProcessCount = procs.size,
            frozenCount = frozenCount,
            runningCount = runningCount,
            filterIndex = filterIndex,
            searchQuery = query,
            showSystemApps = showSystem,
            isRefreshing = refreshing,
            snapshotUnavailable = unavailable,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ProcessListUiState(),
        )

    fun setFilterIndex(index: Int) {
        filterIndexFlow.value = index.coerceIn(0, ProcessFilterMode.entries.lastIndex)
    }

    fun applyPresetFilter(index: Int) {
        setFilterIndex(index)
        viewModelScope.launch {
            runtimeRepo.refreshRuntimeAndProcesses(visible = false)
        }
    }

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun toggleShowSystemApps() {
        showSystemAppsFlow.value = !showSystemAppsFlow.value
    }

    fun refresh() {
        viewModelScope.launch {
            runtimeRepo.refreshRuntimeAndProcesses(visible = true)
        }
    }

    fun thawAll() {
        viewModelScope.launch {
            runtimeRepo.thawAllFrozenUids()
        }
    }
}
