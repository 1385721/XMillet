package io.github.univers629.millet.ui.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.univers629.millet.BuildConfig
import io.github.univers629.millet.core.AppReleaseAsset
import io.github.univers629.millet.core.AppReleaseInfo
import io.github.univers629.millet.core.AppUpdateCheckResult
import io.github.univers629.millet.core.AppUpdateVersionParser
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.SingleRunUpdateCheckGate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 更新检查总状态（供设置页入口展示）。 */
enum class AppUpdateStatus {
    Idle,
    Checking,
    UpToDate,
    Available,
    Failed,
}

/** 更新弹窗内 APK 下载状态。 */
sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState

    data class Downloading(
        val tagName: String,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : AppUpdateDownloadState {
        val fraction: Float?
            get() = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    }

    data class Ready(
        val tagName: String,
        val apkPath: String,
    ) : AppUpdateDownloadState

    data class Failed(
        val tagName: String,
        val message: String,
    ) : AppUpdateDownloadState
}

/** 应用更新 UI 状态快照。 */
data class AppUpdateUiState(
    val status: AppUpdateStatus = AppUpdateStatus.Idle,
    val availableRelease: AppReleaseInfo? = null,
    val dialogVisible: Boolean = false,
    val downloadState: AppUpdateDownloadState = AppUpdateDownloadState.Idle,
)

/** 调用系统安装器的结果。 */
enum class AppUpdateInstallResult {
    Launched,
    PermissionRequired,
    ApkUnavailable,
    Failed,
}

/**
 * 进程级应用更新控制器（仅在 XMillet App UI 进程运行，不触及 system_server / powerkeeper）。
 *
 * - 自动检查通过 [SingleRunUpdateCheckGate] 保证每个进程冷启动仅执行一次，绝不轮询；
 * - 发现新版本且未被用户忽略时弹出 Miuix 更新对话框；
 * - 支持应用内校验下载（HTTPS + 精确大小 + GitHub SHA-256 摘要）并调起系统安装器，或跳转 GitHub Releases 页面。
 */
object AppUpdateController {

    /** UI 本地已忽略版本 tag（纯 UI 键，不在 `PrefsKeys.ALL_KEYS` 中，不同步至 Hook 进程）。 */
    const val KEY_IGNORED_UPDATE_TAG = "ui_update_ignored_tag"

    private const val UPDATE_CACHE_DIR = "updates"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 128L * 1024L * 1024L
    private const val PROGRESS_INTERVAL_NANOS = 150_000_000L
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val autoCheckGate = SingleRunUpdateCheckGate()
    private val _uiState = MutableStateFlow(AppUpdateUiState())
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    private val downloadGeneration = AtomicLong(0L)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * 应用冷启动进入主界面后触发一次静默更新检查。
     *
     * 重复调用直接短路返回，不创建协程、不发起网络请求。
     */
    fun checkOnAppLaunch(
        prefs: SharedPreferences,
        currentVersionCode: Int,
        currentVersionName: String,
        scope: CoroutineScope,
    ) {
        if (!autoCheckGate.tryAcquire()) return
        if (checkJob?.isActive == true) return
        val ignoredTag = prefs.getString(KEY_IGNORED_UPDATE_TAG, null)
        checkJob = scope.launch {
            _uiState.update { it.copy(status = AppUpdateStatus.Checking) }
            val result = withContext(Dispatchers.IO) {
                fetchLatestUpdate(currentVersionCode, currentVersionName)
            }
            when (result) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    val shouldPop = result.release.tagName != ignoredTag
                    _uiState.update { prev ->
                        val sameRelease = prev.availableRelease?.tagName == result.release.tagName
                        prev.copy(
                            status = AppUpdateStatus.Available,
                            availableRelease = result.release,
                            dialogVisible = shouldPop,
                            downloadState = if (sameRelease) prev.downloadState else AppUpdateDownloadState.Idle,
                        )
                    }
                }

                AppUpdateCheckResult.UpToDate -> {
                    _uiState.update {
                        it.copy(status = AppUpdateStatus.UpToDate, availableRelease = null, dialogVisible = false)
                    }
                }

                is AppUpdateCheckResult.Error -> {
                    // 自动检查失败静默退回 Idle，不打扰离线或受限网络用户。
                    Logger.warn("启动检查更新跳过: ${result.message}")
                    _uiState.update { it.copy(status = AppUpdateStatus.Idle) }
                }
            }
        }
    }

    /** 设置页手动触发检查更新；若已发现新版本则直接弹出对话框，不重复请求网络。 */
    fun checkManually(
        context: Context,
        currentVersionCode: Int,
        currentVersionName: String,
        scope: CoroutineScope,
    ) {
        val current = _uiState.value
        if (current.status == AppUpdateStatus.Checking) return
        if (current.status == AppUpdateStatus.Available && current.availableRelease != null) {
            _uiState.update { it.copy(dialogVisible = true) }
            return
        }
        checkJob?.cancel()
        checkJob = scope.launch {
            _uiState.update { it.copy(status = AppUpdateStatus.Checking) }
            val result = withContext(Dispatchers.IO) {
                fetchLatestUpdate(currentVersionCode, currentVersionName)
            }
            when (result) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    _uiState.update { prev ->
                        val sameRelease = prev.availableRelease?.tagName == result.release.tagName
                        prev.copy(
                            status = AppUpdateStatus.Available,
                            availableRelease = result.release,
                            dialogVisible = true,
                            downloadState = if (sameRelease) prev.downloadState else AppUpdateDownloadState.Idle,
                        )
                    }
                }

                AppUpdateCheckResult.UpToDate -> {
                    _uiState.update {
                        it.copy(status = AppUpdateStatus.UpToDate, availableRelease = null, dialogVisible = false)
                    }
                    Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                }

                is AppUpdateCheckResult.Error -> {
                    Logger.warn("手动检查更新失败: ${result.message}")
                    _uiState.update { it.copy(status = AppUpdateStatus.Failed) }
                    Toast.makeText(context, "检查更新失败，请检查网络连接", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 关闭更新弹窗（若正在下载则一并取消）。 */
    fun dismissDialog() {
        if (_uiState.value.downloadState is AppUpdateDownloadState.Downloading) {
            cancelDownload()
        }
        _uiState.update { it.copy(dialogVisible = false) }
    }

    /** 忽略当前远端版本，下次启动不再自动弹窗，但仍可在设置页手动检查。 */
    fun ignoreCurrentRelease(prefs: SharedPreferences) {
        val tag = _uiState.value.availableRelease?.tagName
        if (!tag.isNullOrBlank()) {
            prefs.edit().putString(KEY_IGNORED_UPDATE_TAG, tag).apply()
        }
        dismissDialog()
    }

    /** 开始下载当前可用版本的 APK，下载并校验完成后自动调起系统安装器。 */
    fun startDownload(context: Context, scope: CoroutineScope) {
        val release = _uiState.value.availableRelease ?: return
        val asset = release.apkAsset
        if (asset == null) {
            openReleasePage(context, release.htmlUrl)
            return
        }
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        val generation = downloadGeneration.incrementAndGet()
        _uiState.update {
            it.copy(
                downloadState = AppUpdateDownloadState.Downloading(
                    tagName = release.tagName,
                    bytesRead = 0L,
                    totalBytes = asset.sizeBytes,
                )
            )
        }
        downloadJob = scope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    downloadVerifiedApk(appContext, release.tagName, asset) { read, total ->
                        if (downloadGeneration.get() == generation) {
                            _uiState.update { state ->
                                if (state.availableRelease?.tagName == release.tagName &&
                                    state.downloadState is AppUpdateDownloadState.Downloading
                                ) {
                                    state.copy(
                                        downloadState = AppUpdateDownloadState.Downloading(
                                            tagName = release.tagName,
                                            bytesRead = read,
                                            totalBytes = total,
                                        )
                                    )
                                } else {
                                    state
                                }
                            }
                        }
                    }
                }
                if (downloadGeneration.get() != generation) return@launch
                _uiState.update { state ->
                    if (state.availableRelease?.tagName == release.tagName) {
                        state.copy(downloadState = AppUpdateDownloadState.Ready(release.tagName, apkFile.absolutePath))
                    } else {
                        state
                    }
                }
                installReadyApk(context)
            } catch (c: CancellationException) {
                if (downloadGeneration.get() == generation) {
                    _uiState.update { it.copy(downloadState = AppUpdateDownloadState.Idle) }
                }
                throw c
            } catch (t: Throwable) {
                Logger.warn("下载更新 APK 失败: ${t.javaClass.simpleName}: ${t.message}")
                if (downloadGeneration.get() == generation) {
                    _uiState.update { state ->
                        if (state.availableRelease?.tagName == release.tagName) {
                            state.copy(
                                downloadState = AppUpdateDownloadState.Failed(
                                    tagName = release.tagName,
                                    message = "下载失败，可重试或在浏览器打开",
                                )
                            )
                        } else {
                            state
                        }
                    }
                    Toast.makeText(context, "下载失败，可重试或在浏览器打开", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 取消进行中的 APK 下载并恢复空闲状态。 */
    fun cancelDownload() {
        downloadGeneration.incrementAndGet()
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update {
            if (it.downloadState is AppUpdateDownloadState.Downloading) {
                it.copy(downloadState = AppUpdateDownloadState.Idle)
            } else {
                it
            }
        }
    }

    /** 对已下载就绪的 APK 发起安装；若尚未授权安装未知应用，则跳转系统授权页并保留缓存文件。 */
    fun installReadyApk(context: Context): AppUpdateInstallResult {
        val state = _uiState.value
        val release = state.availableRelease ?: return AppUpdateInstallResult.ApkUnavailable
        val ready = state.downloadState as? AppUpdateDownloadState.Ready ?: return AppUpdateInstallResult.ApkUnavailable
        val apkFile = File(ready.apkPath)
        val expectedSize = release.apkAsset?.sizeBytes ?: 0L
        if (!apkFile.isFile || !apkFile.canRead() || (expectedSize > 0L && apkFile.length() != expectedSize)) {
            _uiState.update { it.copy(downloadState = AppUpdateDownloadState.Idle) }
            Toast.makeText(context, "安装包缓存已失效，请重新下载", Toast.LENGTH_SHORT).show()
            return AppUpdateInstallResult.ApkUnavailable
        }
        val result = launchApkInstaller(context, apkFile)
        when (result) {
            AppUpdateInstallResult.Launched -> {
                _uiState.update { it.copy(dialogVisible = false) }
            }

            AppUpdateInstallResult.PermissionRequired -> {
                Toast.makeText(context, "请先允许安装未知应用，返回后再次点击「立即安装」", Toast.LENGTH_LONG).show()
            }

            AppUpdateInstallResult.ApkUnavailable -> {
                _uiState.update { it.copy(downloadState = AppUpdateDownloadState.Idle) }
                Toast.makeText(context, "安装包不可用，请重新下载", Toast.LENGTH_SHORT).show()
            }

            AppUpdateInstallResult.Failed -> {
                Toast.makeText(context, "无法拉起系统安装器，正在打开浏览器发布页", Toast.LENGTH_SHORT).show()
                openReleasePage(context, release.htmlUrl)
            }
        }
        return result
    }

    /** 在系统浏览器打开指定发布页链接。 */
    fun openReleasePage(context: Context, url: String = AppUpdateVersionParser.RELEASES_PAGE_URL): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** 将 UTC ISO-8601 发布时间格式化为本机时区 `yyyy-MM-dd HH:mm`。 */
    fun formatPublishedTime(publishedAtUtc: String): String {
        if (publishedAtUtc.isBlank()) return ""
        return runCatching {
            val instant = Instant.parse(publishedAtUtc)
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }.getOrDefault(publishedAtUtc.substringBefore('T'))
    }

    private fun fetchLatestUpdate(
        currentVersionCode: Int,
        currentVersionName: String,
    ): AppUpdateCheckResult {
        val apiError: String
        try {
            val json = fetchJsonWithSingleRetry(AppUpdateVersionParser.LATEST_API_URL)
            val release = AppUpdateVersionParser.parseGitHubReleaseJson(json)
                ?: return AppUpdateCheckResult.Error("GitHub Releases 报文缺少有效发布信息")
            return if (AppUpdateVersionParser.isNewer(release, currentVersionCode, currentVersionName)) {
                AppUpdateCheckResult.UpdateAvailable(release)
            } else {
                AppUpdateCheckResult.UpToDate
            }
        } catch (t: Throwable) {
            apiError = "${t.javaClass.simpleName}: ${t.message}"
        }

        // REST API 触发匿名限流或瞬时失败时，退回检查 releases/latest 的 302 跳转（不消耗 API 配额）。
        val fallbackRelease = runCatching { fetchReleaseViaLatestRedirect() }.getOrNull()
        if (fallbackRelease != null) {
            return if (AppUpdateVersionParser.isNewer(fallbackRelease, currentVersionCode, currentVersionName)) {
                AppUpdateCheckResult.UpdateAvailable(fallbackRelease)
            } else {
                AppUpdateCheckResult.UpToDate
            }
        }
        return AppUpdateCheckResult.Error(apiError)
    }

    private fun fetchJsonWithSingleRetry(url: String): String {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                val conn = openSecureUpdateConnection(
                    initialUrl = URL(url),
                    accept = "application/vnd.github+json",
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                )
                return try {
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.use { it.readUtf8Bounded(MAX_JSON_BYTES) }.orEmpty()
                    if (code !in 200..299) {
                        throw UpdateHttpStatusException(code)
                    }
                    body
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                lastError = e
                val retryable = e !is UpdateHttpStatusException || e.statusCode in setOf(408, 425, 500, 502, 503, 504)
                if (attempt == 1 || !retryable) throw e
            }
        }
        throw lastError ?: IOException("Unknown update fetch error")
    }

    private fun fetchReleaseViaLatestRedirect(): AppReleaseInfo? {
        val url = URL(AppUpdateVersionParser.LATEST_WEB_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", userAgent())
        }
        return try {
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                AppUpdateVersionParser.parseRedirectLocation(conn.getHeaderField("Location"))
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadVerifiedApk(
        appContext: Context,
        tagName: String,
        asset: AppReleaseAsset,
        onProgress: (Long, Long) -> Unit,
    ): File {
        if (asset.sizeBytes > MAX_APK_BYTES) {
            throw IOException("APK 体积超出上限 (${asset.sizeBytes} B)")
        }
        val dir = File(appContext.cacheDir, UPDATE_CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("无法创建更新缓存目录")
        }
        val safeTag = tagName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val targetFile = File(dir, "XMillet-$safeTag.apk")
        if (isCachedApkValid(targetFile, asset)) {
            onProgress(targetFile.length(), targetFile.length())
            return targetFile
        }
        cleanupOldCachedApks(dir, keepName = targetFile.name)

        val partial = File.createTempFile("xmillet-update-", ".part", dir)
        val ctx = coroutineContext
        try {
            val conn = openSecureUpdateConnection(
                initialUrl = URL(asset.downloadUrl),
                accept = "application/octet-stream",
                connectTimeoutMs = DOWNLOAD_CONNECT_TIMEOUT_MS,
                readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS,
                checkActive = { ctx.ensureActive() },
            )
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateHttpStatusException(conn.responseCode)
                }
                val contentLength = conn.contentLengthLong
                val expectedSize = if (asset.sizeBytes > 0L) asset.sizeBytes else contentLength.coerceAtLeast(0L)
                if (asset.sizeBytes > 0L && contentLength > 0L && contentLength != asset.sizeBytes) {
                    throw IOException("响应大小与发布元数据不一致")
                }
                val limit = if (asset.sizeBytes > 0L) asset.sizeBytes else MAX_APK_BYTES
                val digest = MessageDigest.getInstance("SHA-256")
                var bytesRead = 0L
                var lastProgressAt = System.nanoTime()
                onProgress(0L, expectedSize)

                conn.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ctx.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytesRead + count > limit) {
                                throw IOException("下载体积超过预期上限")
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            bytesRead += count
                            val now = System.nanoTime()
                            if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
                                onProgress(bytesRead, expectedSize)
                                lastProgressAt = now
                            }
                        }
                    }
                }
                if (asset.sizeBytes > 0L && bytesRead != asset.sizeBytes) {
                    throw IOException("下载不完整：$bytesRead / ${asset.sizeBytes}")
                }
                val expectedSha = asset.sha256
                if (!expectedSha.isNullOrEmpty()) {
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        throw IOException("APK SHA-256 校验失败")
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partial.renameTo(targetFile)) {
                throw IOException("无法落盘安装包文件")
            }
            onProgress(targetFile.length(), targetFile.length())
            return targetFile
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun isCachedApkValid(file: File, asset: AppReleaseAsset): Boolean {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return false
        if (asset.sizeBytes > 0L && file.length() != asset.sizeBytes) return false
        val expectedSha = asset.sha256
        if (expectedSha.isNullOrEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha, ignoreCase = true)
    }

    private fun cleanupOldCachedApks(dir: File, keepName: String) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != keepName && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                runCatching { file.delete() }
            }
        }
    }

    private fun launchApkInstaller(context: Context, apkFile: File): AppUpdateInstallResult {
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return AppUpdateInstallResult.PermissionRequired
            }
            val apkUri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppUpdateInstallResult.Launched
        } catch (_: Throwable) {
            AppUpdateInstallResult.Failed
        }
    }

    private fun userAgent(): String = "XMillet-Android/${BuildConfig.VERSION_NAME}"
}

internal class UpdateHttpStatusException(val statusCode: Int) :
    IOException("Update HTTP request failed with status $statusCode")

/**
 * 严格 HTTPS 传输连接器（参考 Duck-Detector-Refactoring 的 `openUpdateConnection`）：
 * 每一跳重定向都强制校验 HTTPS 且拒绝 URL 内嵌凭据，最多跟踪 5 次跳转。
 */
internal fun openSecureUpdateConnection(
    initialUrl: URL,
    accept: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    userAgent: String = "XMillet-Android",
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    checkActive: () -> Unit = {},
): HttpURLConnection {
    var url = initialUrl
    repeat(6) { redirects ->
        checkActive()
        if (!url.protocol.equals("https", ignoreCase = true) || url.userInfo != null) {
            throw IOException("更新请求仅允许不带凭据的 HTTPS 地址")
        }
        val connection = openConnection(url)
        var retained = false
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", userAgent)
            if (url.host.equals("api.github.com", ignoreCase = true)) {
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = connection.responseCode
            if (code !in setOf(301, 302, 303, 307, 308)) {
                retained = true
                return connection
            }
            if (redirects == 5) throw IOException("更新请求重定向次数过多")
            val location = connection.getHeaderField("Location")
                ?: throw IOException("更新重定向缺少 Location 响应头")
            url = URL(url, location)
        } finally {
            if (!retained) connection.disconnect()
        }
    }
    throw IOException("更新请求重定向次数过多")
}

private fun InputStream.readUtf8Bounded(maxBytes: Int): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("更新响应体积超出上限")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
