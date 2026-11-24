package io.github.univers629.millet.ui.vm

import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.ui.MainDispatcherRule
import io.github.univers629.millet.ui.data.FreezeStatsDataSource
import io.github.univers629.millet.ui.model.FreezeStatsSnapshot
import io.github.univers629.millet.ui.model.StatsLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TombStatsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private class FakeFreezeStatsDataSource : FreezeStatsDataSource {
        var loadCalls = 0
        var resetCalls = 0
        var resetResult = true
        var nextStateByWindow: (StatsWindow) -> StatsLoadState = { w ->
            StatsLoadState.Ready(sampleSnapshot(w))
        }

        override suspend fun load(
            window: StatsWindow,
            serviceAlive: Boolean,
            statsEnabled: Boolean,
        ): StatsLoadState {
            loadCalls++
            if (!statsEnabled) return StatsLoadState.Disabled
            if (!serviceAlive) return StatsLoadState.ServiceUnavailable
            return nextStateByWindow(window)
        }

        override suspend fun loadSample(window: StatsWindow): StatsLoadState =
            StatsLoadState.Ready(sampleSnapshot(window, isSample = true))

        override suspend fun reset(): Boolean {
            resetCalls++
            return resetResult
        }

        private fun sampleSnapshot(window: StatsWindow, isSample: Boolean = false) = FreezeStatsSnapshot(
            window = window,
            sinceMillis = 1_000L,
            generatedAtMillis = 2_000L,
            freezeCount = 12,
            wakeByCategory = emptyMap(),
            screenOffWakeCount = 2,
            screenOffMillis = 60_000L,
            interceptByRule = emptyMap(),
            protectedCount = 1,
            killBlockedCount = 0,
            frozenMillisTotal = 10_000L,
            buckets = emptyList(),
            apps = emptyList(),
            recent = emptyList(),
            isSample = isSample,
        )
    }

    @Test
    fun loadsReadyPreservesStaleSnapshotOnFailureAndStopsPollingWhenInactive() = runTest(mainRule.dispatcher) {
        val ds = FakeFreezeStatsDataSource()
        val vm = TombStatsViewModel(ds)

        vm.updateEnvironment(isActive = true, serviceAlive = true, statsEnabled = true)
        testScheduler.runCurrent()

        assertEquals(1, ds.loadCalls)
        assertTrue(vm.uiState.value.loadState is StatsLoadState.Ready)
        assertFalse(vm.uiState.value.refreshFailed)

        // 下一次 10s 自动刷新时服务断开：保留同窗口上次成功快照并标记 refreshFailed = true
        vm.updateEnvironment(isActive = true, serviceAlive = false, statsEnabled = true)
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.loadState is StatsLoadState.Ready)
        assertTrue(vm.uiState.value.refreshFailed)

        // 切走页面（isActive = false）：彻底停止 10s 轮询，推进 30 秒应为 0 次额外查询
        val callsBeforeInactive = ds.loadCalls
        vm.updateEnvironment(isActive = false, serviceAlive = true, statsEnabled = true)
        testScheduler.advanceTimeBy(30_000L)
        testScheduler.runCurrent()
        assertEquals(callsBeforeInactive, ds.loadCalls)
    }

    @Test
    fun windowSwitchAndResetClearStaleDetailSelection() = runTest(mainRule.dispatcher) {
        val ds = FakeFreezeStatsDataSource()
        val vm = TombStatsViewModel(ds)

        vm.updateEnvironment(isActive = true, serviceAlive = true, statsEnabled = true)
        testScheduler.runCurrent()
        vm.selectDetail(10123)
        assertEquals(10123, vm.uiState.value.detailSelection?.uid)

        vm.selectWindow(StatsWindow.WEEK.ordinal)
        testScheduler.runCurrent()
        val weekSnap = (vm.uiState.value.loadState as StatsLoadState.Ready).snapshot
        assertEquals(StatsWindow.WEEK, weekSnap.window)

        var resetOk = false
        vm.resetStats { resetOk = it }
        testScheduler.runCurrent()
        assertTrue(resetOk)
        assertEquals(1, ds.resetCalls)
        assertNull(vm.uiState.value.detailSelection)

        vm.updateEnvironment(isActive = false, serviceAlive = true, statsEnabled = true)
    }
}
