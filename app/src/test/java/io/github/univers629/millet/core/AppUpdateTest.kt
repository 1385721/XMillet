package io.github.univers629.millet.core

import io.github.univers629.millet.ui.util.openSecureUpdateConnection
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateTest {

    @Test
    fun parseGitHubReleaseJson_extractsXposedModulesRepoReleaseAndSha256() {
        val sha = "3de676245cda60a29ef2c6719ab9c25f515eb5e897d81a172faa1a06fc413dcb"
        val json = """
            {
              "tag_name": "1250-1.2.5",
              "name": "XMillet 1.2.5",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-23T01:11:25Z",
              "html_url": "https://github.com/Xposed-Modules-Repo/io.github.univers629.millet/releases/tag/1250-1.2.5",
              "body": "### 云白名单\r\n\r\n- 新增 **云免冻** 与 `云墓碑白` 总开关。\r\n- 修复若干问题。",
              "assets": [
                {
                  "name": "checksums.txt",
                  "size": 128,
                  "browser_download_url": "https://github.com/Xposed-Modules-Repo/io.github.univers629.millet/releases/download/1250-1.2.5/checksums.txt"
                },
                {
                  "name": "XMillet-1250-1.2.5.apk",
                  "size": 4338457,
                  "digest": "sha256:$sha",
                  "browser_download_url": "https://github.com/Xposed-Modules-Repo/io.github.univers629.millet/releases/download/1250-1.2.5/XMillet-1250-1.2.5.apk"
                }
              ]
            }
        """.trimIndent()

        val release = requireNotNull(AppUpdateVersionParser.parseGitHubReleaseJson(json))
        assertEquals("1250-1.2.5", release.tagName)
        assertEquals(1250, release.versionCode)
        assertEquals("1.2.5", release.versionName)
        assertEquals("2026-11-23T01:11:25Z", release.publishedAtUtc)
        val asset = requireNotNull(release.apkAsset)
        assertEquals("XMillet-1250-1.2.5.apk", asset.name)
        assertEquals(4338457L, asset.sizeBytes)
        assertEquals(sha, asset.sha256)

        assertTrue(AppUpdateVersionParser.isNewer(release, 1240, "1.2.4"))
        assertFalse(AppUpdateVersionParser.isNewer(release, 1250, "1.2.5"))
        assertFalse(AppUpdateVersionParser.isNewer(release, 1260, "1.2.6"))

        val formatted = AppUpdateVersionParser.formatReleaseBody(release.body)
        assertEquals("【云白名单】\n\n• 新增 云免冻 与 云墓碑白 总开关。\n• 修复若干问题。", formatted)
    }

    @Test
    fun parseGitHubReleaseJson_skipsDraftAndPrerelease() {
        val draftJson = """{"tag_name":"1250-1.2.5","draft":true,"prerelease":false,"assets":[]}"""
        val preJson = """{"tag_name":"1250-1.2.5-beta1","draft":false,"prerelease":true,"assets":[]}"""
        assertNull(AppUpdateVersionParser.parseGitHubReleaseJson(draftJson))
        assertNull(AppUpdateVersionParser.parseGitHubReleaseJson(preJson))
    }

    @Test
    fun versionComparison_handlesSemverFallbackWhenCodeAbsent() {
        val release = AppReleaseInfo(
            tagName = "v1.10.0",
            releaseName = "XMillet 1.10.0",
            versionCode = null,
            versionName = "1.10.0",
            publishedAtUtc = "",
            htmlUrl = AppUpdateVersionParser.RELEASES_PAGE_URL,
            body = "",
            apkAsset = null,
        )
        assertTrue(AppUpdateVersionParser.isNewer(release, 1240, "1.2.9"))
        assertFalse(AppUpdateVersionParser.isNewer(release, 1240, "1.10.0"))
        assertTrue(AppUpdateVersionParser.compareVersionNames("1.2.5", "1.2.5-beta1") > 0)
        assertTrue(AppUpdateVersionParser.compareVersionNames("1.2.5-beta1", "1.2.5") < 0)
    }

    @Test
    fun parseRedirectLocation_extractsTagWithoutApiQuota() {
        val fallback = requireNotNull(
            AppUpdateVersionParser.parseRedirectLocation(
                "https://github.com/Xposed-Modules-Repo/io.github.univers629.millet/releases/tag/1260-1.2.6"
            )
        )
        assertEquals("1260-1.2.6", fallback.tagName)
        assertEquals(1260, fallback.versionCode)
        assertEquals("1.2.6", fallback.versionName)
        assertTrue(AppUpdateVersionParser.isNewer(fallback, 1240, "1.2.4"))
        assertNull(fallback.apkAsset)
    }

    @Test
    fun singleRunUpdateCheckGate_acquiresStrictlyOnceAcrossThreads() {
        val gate = SingleRunUpdateCheckGate()
        val winners = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                latch.await()
                if (gate.tryAcquire()) winners.incrementAndGet()
            }.apply { start() }
        }
        latch.countDown()
        threads.forEach { it.join() }

        assertEquals(1, winners.get())
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun openSecureUpdateConnection_rejectsHttpCredentialsAndExcessiveRedirects() {
        try {
            openSecureUpdateConnection(
                initialUrl = URL("http://github.com/test"),
                accept = "application/json",
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000,
            )
            fail("Expected HTTP URL to be rejected")
        } catch (_: IOException) {
            // expected
        }

        try {
            openSecureUpdateConnection(
                initialUrl = URL("https://user:pass@github.com/test"),
                accept = "application/json",
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000,
            )
            fail("Expected URL with credentials to be rejected")
        } catch (_: IOException) {
            // expected
        }

        val fakeRedirect = { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = 302
                override fun getHeaderField(name: String?): String? =
                    if (name.equals("Location", ignoreCase = true)) "https://github.com/loop" else null
            }
        }
        try {
            openSecureUpdateConnection(
                initialUrl = URL("https://github.com/start"),
                accept = "application/json",
                connectTimeoutMs = 1000,
                readTimeoutMs = 1000,
                openConnection = fakeRedirect,
            )
            fail("Expected redirect loop to be rejected")
        } catch (_: IOException) {
            // expected
        }
    }
}
