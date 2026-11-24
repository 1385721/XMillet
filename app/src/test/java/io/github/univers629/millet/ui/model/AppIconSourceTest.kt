package io.github.univers629.millet.ui.model

import io.github.univers629.millet.ui.screen.InstalledAppUiModel
import io.github.univers629.millet.ui.screen.RunningProcessUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppIconSourceTest {

    @Test
    fun equalMetadataProducesEqualIconSourcesAndUiModels() {
        val first = AppIconSource.forTest(
            uid = 10123,
            packageName = "com.tencent.mm",
            sourceDir = "/data/app/~~abc/com.tencent.mm-1/base.apk",
        )
        val second = AppIconSource.forTest(
            uid = 10123,
            packageName = "com.tencent.mm",
            sourceDir = "/data/app/~~abc/com.tencent.mm-1/base.apk",
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())

        val appA = InstalledAppUiModel(
            appName = "微信",
            packageName = "com.tencent.mm",
            uid = 10123,
            versionName = "8.0.50",
            versionCode = 2800L,
            isSystem = false,
            iconSource = first,
            inWhiteSet = false,
            inMessageSet = true,
            inForceFreezeSet = false,
            inBlockThawSet = false,
            inForbidSyncSet = false,
            inNotFreezeFgsSet = false,
            inBatteryWhiteSet = false,
        )
        val appB = appA.copy(iconSource = second)
        assertEquals(appA, appB)

        val procA = RunningProcessUiModel(
            appName = "微信",
            packageName = "com.tencent.mm",
            uid = 10123,
            pids = listOf(1001),
            isFrozen = true,
            iconSource = first,
        )
        val procB = procA.copy(iconSource = second)
        assertEquals(procA, procB)
    }

    @Test
    fun updatedApkPathInvalidatesIconEquality() {
        val oldApk = AppIconSource.forTest(10123, "com.tencent.mm", "/data/app/v1/base.apk")
        val newApk = AppIconSource.forTest(10123, "com.tencent.mm", "/data/app/v2/base.apk")
        assertNotEquals(oldApk, newApk)
    }
}
