package io.github.univers629.millet.ui.util

import io.github.univers629.millet.core.AppReleaseAsset
import io.github.univers629.millet.core.AppReleaseInfo
import io.github.univers629.millet.ui.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerScopeTest {

    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    @After
    fun tearDown() {
        AppUpdateController.resetForTest()
    }

    @Test
    fun downloadSurvivesUiSubscriberCancellationAndMarksPendingAutoInstall() = runTest {
        val release = AppReleaseInfo(
            tagName = "1310-1.3.1",
            releaseName = "XMillet 1.3.1",
            versionCode = 1310,
            versionName = "1.3.1",
            publishedAtUtc = "2026-11-24T00:00:00Z",
            htmlUrl = "https://github.com/example/releases/tag/1310-1.3.1",
            body = "",
            apkAsset = AppReleaseAsset(
                name = "XMillet-1310-1.3.1.apk",
                downloadUrl = "https://github.com/example/releases/download/1310-1.3.1/app.apk",
                sizeBytes = 1024L,
                sha256 = null,
            ),
        )
        AppUpdateController.setAvailableReleaseForTest(release)

        val processScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        val uiScope = CoroutineScope(SupervisorJob() + mainRule.dispatcher)
        val collectedStates = ArrayList<AppUpdateUiState>()
        val collectorJob = uiScope.launch {
            AppUpdateController.uiState.collect { collectedStates.add(it) }
        }

        val gate = CompletableDeferred<File>()
        AppUpdateController.startDownloadWithScope(
            scope = processScope,
            ioDispatcher = mainRule.dispatcher,
            downloader = { _, _, onProgress ->
                onProgress(512L, 1024L)
                gate.await()
            },
        )

        testScheduler.advanceUntilIdle()
        val downloading = AppUpdateController.uiState.value.downloadState
        assertTrue(downloading is AppUpdateDownloadState.Downloading)
        assertEquals(512L, (downloading as AppUpdateDownloadState.Downloading).bytesRead)

        // 模拟切小窗 / 旋转屏幕：旧 UI 订阅者作用域被取消，但进程级下载必须继续完成
        collectorJob.cancel()
        uiScope.cancel()

        val fakeApk = File("build/tmp/fake-update.apk")
        gate.complete(fakeApk)
        testScheduler.advanceUntilIdle()

        val finalState = AppUpdateController.uiState.value
        assertEquals(
            AppUpdateDownloadState.Ready("1310-1.3.1", fakeApk.absolutePath),
            finalState.downloadState,
        )
        assertTrue(finalState.pendingAutoInstall)
        assertFalse(collectedStates.last().pendingAutoInstall)

        processScope.cancel()
    }
}
