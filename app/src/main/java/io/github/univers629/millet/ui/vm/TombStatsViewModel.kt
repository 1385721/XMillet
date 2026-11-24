package io.github.univers629.millet.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.ui.data.FreezeStatsDataSource
import io.github.univers629.millet.ui.model.FreezeStatsSnapshot
import io.github.univers629.millet.ui.model.StatsLoadState
import io.github.univers629.millet.ui.util.StatsDetailSelection
import io.github.univers629.millet.ui.util.StatsQueryScope
import io.github.univers629.millet.ui.util.StatsRefreshGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TombStatsUiState(
    val windowIndex: Int = 0,
    val rankModeIndex: Int = 0,
    val useSample: Boolean = false,
    val isActive: Boolean = false,
    val serviceAlive: Boolean = false,
    val statsEnabled: Boolean = true,
    val loadState: StatsLoadState = StatsLoadState.Loading,
    val refreshFailed: Boolean = false,
    val isRefreshing: Boolean = false,
    val detailSelection: StatsDetailSelection? = null,
) {
    val window: StatsWindow get() = StatsWindow.entries.getOrElse(windowIndex) { StatsWindow.TODAY }
    val queryScope: StatsQueryScope get() = StatsQueryScope(window, useSample)

    /** 防止切换时间窗/示例模式瞬间闪出旧窗口缓存快照。 */
    val effectiveLoadState: StatsLoadState
        get() = loadState.takeUnless {
            it is StatsLoadState.Ready &&
                (it.snapshot.window != window || it.snapshot.isSample != useSample)
        } ?: StatsLoadState.Loading
}

/**
 * 墓碑统计页状态机：
 * - 仅在 [TombStatsUiState.isActive] 为 true 时开启前景刷新循环（10 秒周期），切走或退后台立即停止；
 * - 刷新失败且已有同窗口成功快照时保留上次数据并标记 `refreshFailed = true`；
 * - 通过 [StatsRefreshGate] 保证切窗口/重置后的旧响应绝不会覆盖新窗口状态。
 */
class TombStatsViewModel(
    private val dataSource: FreezeStatsDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TombStatsUiState())
    val uiState: StateFlow<TombStatsUiState> = _uiState.asStateFlow()

    private val refreshGate = StatsRefreshGate()
    private val snapshotCache = HashMap<StatsQueryScope, FreezeStatsSnapshot>()
    private var pollingJob: Job? = null
    private var envKey: EnvKey? = null

    private data class EnvKey(
        val isActive: Boolean,
        val window: StatsWindow,
        val useSample: Boolean,
        val serviceAlive: Boolean,
        val statsEnabled: Boolean,
    )

    fun updateEnvironment(
        isActive: Boolean,
        serviceAlive: Boolean,
        statsEnabled: Boolean,
    ) {
        _uiState.update {
            it.copy(
                isActive = isActive,
                serviceAlive = serviceAlive,
                statsEnabled = statsEnabled,
            )
        }
        restartPollingIfNeeded(force = false)
    }

    fun selectWindow(index: Int) {
        val clamped = index.coerceIn(0, StatsWindow.entries.lastIndex)
        if (_uiState.value.windowIndex == clamped) return
        _uiState.update { it.copy(windowIndex = clamped, isRefreshing = false) }
        restartPollingIfNeeded(force = true)
    }

    fun selectRankMode(index: Int) {
        _uiState.update { it.copy(rankModeIndex = index) }
    }

    fun setUseSample(enabled: Boolean) {
        if (_uiState.value.useSample == enabled) return
        _uiState.update { it.copy(useSample = enabled, isRefreshing = false) }
        restartPollingIfNeeded(force = true)
    }

    fun selectDetail(uid: Int?) {
        val scope = _uiState.value.queryScope
        _uiState.update {
            it.copy(detailSelection = uid?.let { id -> StatsDetailSelection(id, scope) })
        }
    }

    fun refreshManually() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        restartPollingIfNeeded(force = true)
    }

    fun enableStatsAndRefresh(onEnableStats: (() -> Unit)?) {
        viewModelScope.launch {
            onEnableStats?.invoke()
            delay(350L)
            restartPollingIfNeeded(force = true)
        }
    }

    fun resetStats(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = try {
                dataSource.reset()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                false
            }
            onResult(ok)
            if (ok) {
                snapshotCache.clear()
                refreshGate.invalidate()
                _uiState.update {
                    it.copy(
                        loadState = StatsLoadState.Loading,
                        detailSelection = null,
                        refreshFailed = false,
                        isRefreshing = false,
                    )
                }
                restartPollingIfNeeded(force = true)
            }
        }
    }

    private fun restartPollingIfNeeded(force: Boolean) {
        val state = _uiState.value
        val nextKey = EnvKey(
            isActive = state.isActive,
            window = state.window,
            useSample = state.useSample,
            serviceAlive = state.serviceAlive,
            statsEnabled = state.statsEnabled,
        )
        if (!force && envKey == nextKey) return
        envKey = nextKey
        pollingJob?.cancel()
        refreshGate.invalidate()

        if (!state.isActive) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }

        val queryScope = state.queryScope
        val ticket = refreshGate.begin(queryScope)
        val cached = snapshotCache[queryScope]
        _uiState.update {
            if (cached == null) {
                it.copy(loadState = StatsLoadState.Loading, refreshFailed = false)
            } else {
                it.copy(loadState = StatsLoadState.Ready(cached))
            }
        }

        pollingJob = viewModelScope.launch {
            try {
                while (true) {
                    var next = loadOnce(nextKey)
                    if (!nextKey.useSample && nextKey.statsEnabled && next is StatsLoadState.Disabled) {
                        delay(300L)
                        next = loadOnce(nextKey)
                    }
                    if (!refreshGate.accepts(ticket, queryScope)) return@launch
                    if (next is StatsLoadState.Ready) {
                        snapshotCache[queryScope] = next.snapshot
                        _uiState.update {
                            it.copy(
                                loadState = next,
                                refreshFailed = false,
                                isRefreshing = false,
                            )
                        }
                    } else {
                        val previous = snapshotCache[queryScope]
                        if (nextKey.statsEnabled && next !is StatsLoadState.Disabled && previous != null) {
                            _uiState.update {
                                it.copy(
                                    loadState = StatsLoadState.Ready(previous),
                                    refreshFailed = true,
                                    isRefreshing = false,
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    loadState = next,
                                    refreshFailed = false,
                                    isRefreshing = false,
                                )
                            }
                        }
                    }
                    if (nextKey.useSample || next !is StatsLoadState.Ready) break
                    delay(AUTO_REFRESH_INTERVAL_MS)
                }
            } finally {
                if (refreshGate.accepts(ticket, queryScope)) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private suspend fun loadOnce(key: EnvKey): StatsLoadState = try {
        if (key.useSample) {
            dataSource.loadSample(key.window)
        } else {
            dataSource.load(key.window, key.serviceAlive, key.statsEnabled)
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        StatsLoadState.BackendUnsupported
    }

    override fun onCleared() {
        pollingJob?.cancel()
        refreshGate.invalidate()
        super.onCleared()
    }

    private companion object {
        const val AUTO_REFRESH_INTERVAL_MS = 10_000L
    }
}
