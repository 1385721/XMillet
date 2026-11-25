package io.github.univers629.millet.core

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** GitHub Release 中的 APK 资产元数据。 */
data class AppReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256: String? = null,
)

/** 从 GitHub Releases 解析出的远端发布信息。 */
data class AppReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val versionCode: Int?,
    val versionName: String,
    val publishedAtUtc: String,
    val htmlUrl: String,
    val body: String,
    val apkAsset: AppReleaseAsset?,
)

/** 单次检查更新的结果。 */
sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult
    data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateCheckResult
    data class Error(val message: String) : AppUpdateCheckResult
}

/** 从 tag / release 名称提取出的结构化版本信息。 */
data class ParsedReleaseVersion(
    val versionCode: Int?,
    val versionName: String,
)

/**
 * 进程级单次执行闸门（参考 Duck-Detector-Refactoring 的 `SingleRunUpdateCheckGate`）。
 *
 * 自动检查只在应用进程冷启动进入主界面后触发一次；配置变更、切后台再回前台或切换 Tab
 * 均不会重复发起网络请求，避免无意义的电量与性能开销。
 */
class SingleRunUpdateCheckGate {
    private val acquired = AtomicBoolean(false)

    /** 首次调用返回 true，此后恒返回 false。 */
    fun tryAcquire(): Boolean = acquired.compareAndSet(false, true)
}

/**
 * GitHub Releases JSON / 重定向解析与版本号比较工具（纯 Kotlin 实现，不依赖 `org.json`，
 * 便于在 JVM 单测中直接用真实接口报文校验）。
 */
object AppUpdateVersionParser {

    const val REPO_OWNER = "Xposed-Modules-Repo"
    const val REPO_NAME = "io.github.univers629.millet"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
    const val LATEST_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val LATEST_WEB_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** Xposed Modules Repo 标准 tag 格式，例如 `1240-1.2.4` 或 `1250-v1.2.5`。 */
    private val CODE_AND_NAME_REGEX = Regex("""^(\d+)-v?(\d+(?:\.\d+)*(?:[-+._][0-9A-Za-z.-]+)?)$""")

    /** 通用语义化 tag 格式，例如 `v1.2.5` 或 `1.2.5-rc1`。 */
    private val SEMVER_REGEX = Regex("""^v?(\d+(?:\.\d+)+(?:[-+._][0-9A-Za-z.-]+)?)$""")

    /** 从发布页标题或 APK 文件名兜底提取 `versionCode-versionName`。 */
    private val EMBEDDED_CODE_NAME_REGEX = Regex("""(\d{3,})-v?(\d+(?:\.\d+)+)""")

    /** 从 GitHub `/releases/latest` 302 跳转地址提取 tag。 */
    private val REDIRECT_TAG_REGEX = Regex("""/releases/tag/([^/?#]+)""")

    private val SHA256_DIGEST_REGEX = Regex("""^sha256:([0-9a-fA-F]{64})$""")

    /**
     * 解析 GitHub `/releases/latest` 返回的 JSON 对象。
     *
     * 自动过滤 `draft` 与 `prerelease`；缺少合法 tag 或有效版本号时返回 null。
     */
    fun parseGitHubReleaseJson(json: String): AppReleaseInfo? {
        val root = SimpleJsonParser.parse(json) as? Map<*, *> ?: return null
        if (root["draft"] == true || root["prerelease"] == true) return null

        val tagName = (root["tag_name"] as? String)?.trim().orEmpty()
        if (tagName.isEmpty()) return null

        val releaseName = (root["name"] as? String)?.trim().orEmpty().ifEmpty { tagName }
        val publishedAt = (root["published_at"] as? String)?.trim().orEmpty()
        val rawHtmlUrl = (root["html_url"] as? String)?.trim().orEmpty()
        val htmlUrl = if (rawHtmlUrl.startsWith("https://")) {
            rawHtmlUrl
        } else {
            "$RELEASES_PAGE_URL/tag/$tagName"
        }
        val body = (root["body"] as? String).orEmpty()
        val apkAsset = selectApkAsset(root["assets"] as? List<*>)
        val parsed = parseTagVersion(tagName, releaseName, apkAsset?.name)

        return AppReleaseInfo(
            tagName = tagName,
            releaseName = releaseName,
            versionCode = parsed.versionCode,
            versionName = parsed.versionName,
            publishedAtUtc = publishedAt,
            htmlUrl = htmlUrl,
            body = body,
            apkAsset = apkAsset,
        )
    }

    /**
     * 当 `api.github.com` 触发匿名限流（403/429）或超时时，从 `releases/latest` 的
     * 302 `Location` 响应头中解析最新 tag，作为零 API 配额消耗的降级发现通道。
     */
    fun parseRedirectLocation(location: String?): AppReleaseInfo? {
        if (location.isNullOrBlank()) return null
        val match = REDIRECT_TAG_REGEX.find(location) ?: return null
        val tagName = match.groupValues[1].trim()
        if (tagName.isEmpty()) return null
        val parsed = parseTagVersion(tagName)
        return AppReleaseInfo(
            tagName = tagName,
            releaseName = "XMillet ${parsed.versionName}",
            versionCode = parsed.versionCode,
            versionName = parsed.versionName,
            publishedAtUtc = "",
            htmlUrl = "$RELEASES_PAGE_URL/tag/$tagName",
            body = "",
            apkAsset = null,
        )
    }

    /** 从 tag、标题与 APK 文件名中提取 `versionCode` 与 `versionName`。 */
    fun parseTagVersion(
        tagName: String,
        releaseName: String = "",
        apkName: String? = null,
    ): ParsedReleaseVersion {
        val trimmedTag = tagName.trim()
        CODE_AND_NAME_REGEX.matchEntire(trimmedTag)?.let { match ->
            val code = match.groupValues[1].toIntOrNull()
            val name = match.groupValues[2]
            return ParsedReleaseVersion(versionCode = code, versionName = name)
        }
        SEMVER_REGEX.matchEntire(trimmedTag)?.let { match ->
            val embeddedCode = apkName?.let { EMBEDDED_CODE_NAME_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            return ParsedReleaseVersion(versionCode = embeddedCode, versionName = match.groupValues[1])
        }
        for (source in listOfNotNull(apkName, releaseName)) {
            EMBEDDED_CODE_NAME_REGEX.find(source)?.let { match ->
                return ParsedReleaseVersion(
                    versionCode = match.groupValues[1].toIntOrNull(),
                    versionName = match.groupValues[2],
                )
            }
        }
        val cleanName = trimmedTag.removePrefix("v").removePrefix("V").ifEmpty { trimmedTag }
        return ParsedReleaseVersion(versionCode = null, versionName = cleanName)
    }

    /**
     * 判断远端发布是否高于当前已安装版本。
     *
     * 优先比较整型 `versionCode`（与 Xposed Modules Repo 及 Android 包管理口径完全一致）；
     * 仅当远端 tag 未携带 `versionCode` 时退回到语义化版本号分段比较。
     */
    fun isNewer(
        remote: AppReleaseInfo,
        currentVersionCode: Int,
        currentVersionName: String,
    ): Boolean {
        if (remote.versionCode != null) {
            return remote.versionCode > currentVersionCode
        }
        return compareVersionNames(remote.versionName, currentVersionName) > 0
    }

    /** 按数字分段比较两个版本名（如 `1.10.0 > 1.2.9`，`1.2.5 > 1.2.5-beta1`）。 */
    fun compareVersionNames(remote: String, current: String): Int {
        val (remoteNums, remotePre) = splitVersionName(remote)
        val (currentNums, currentPre) = splitVersionName(current)
        if (remoteNums.isEmpty() || currentNums.isEmpty()) return 0
        val maxLen = maxOf(remoteNums.size, currentNums.size)
        for (i in 0 until maxLen) {
            val r = remoteNums.getOrElse(i) { 0 }
            val c = currentNums.getOrElse(i) { 0 }
            if (r != c) return r.compareTo(c)
        }
        return when {
            remotePre == null && currentPre != null -> 1
            remotePre != null && currentPre == null -> -1
            remotePre != null && currentPre != null -> remotePre.compareTo(currentPre, ignoreCase = true)
            else -> 0
        }
    }

    /** 将 GitHub Markdown 更新日志整理为适合对话框阅读的纯文本段落。 */
    fun formatReleaseBody(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val lines = rawBody.replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = ArrayList<String>(lines.size)
        var lastBlank = true
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                if (!lastBlank) {
                    out.add("")
                    lastBlank = true
                }
                continue
            }
            val cleaned = when {
                trimmed.startsWith("#") -> {
                    val heading = trimmed.trimStart('#').trim()
                    if (heading.isNotEmpty()) "【$heading】" else ""
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "• ${trimmed.substring(2).trim()}"
                else -> trimmed
            }.replace("**", "").replace("`", "")
            if (cleaned.isNotEmpty()) {
                out.add(cleaned)
                lastBlank = false
            }
        }
        while (out.isNotEmpty() && out.last().isEmpty()) {
            out.removeAt(out.lastIndex)
        }
        return out.joinToString("\n")
    }

    /** 格式化文件大小为易读字符串。 */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    private fun splitVersionName(raw: String): Pair<List<Int>, String?> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val dashIdx = cleaned.indexOf('-')
        val core = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val pre = if (dashIdx >= 0) cleaned.substring(dashIdx + 1).takeIf { it.isNotEmpty() } else null
        val nums = core.split('.').mapNotNull { it.toIntOrNull() }
        return nums to pre
    }

    private fun selectApkAsset(assets: List<*>?): AppReleaseAsset? {
        if (assets.isNullOrEmpty()) return null
        val parsed = assets.mapNotNull { entry ->
            val obj = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (obj["name"] as? String)?.trim().orEmpty()
            val url = (obj["browser_download_url"] as? String)?.trim().orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true) || !url.startsWith("https://")) {
                return@mapNotNull null
            }
            val size = (obj["size"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            val rawDigest = (obj["digest"] as? String)?.trim().orEmpty()
            val sha256 = SHA256_DIGEST_REGEX.matchEntire(rawDigest)?.groupValues?.get(1)?.lowercase(Locale.US)
            AppReleaseAsset(
                name = name,
                sizeBytes = size,
                downloadUrl = url,
                sha256 = sha256,
            )
        }
        if (parsed.isEmpty()) return null
        return parsed.firstOrNull { it.name.contains("arm64", ignoreCase = true) }
            ?: parsed.firstOrNull { it.name.startsWith("XMillet", ignoreCase = true) }
            ?: parsed.first()
    }
}

/**
 * 极简 RFC 8259 JSON 递归下降解析器（仅用于解析 GitHub Releases 响应，不引入三方库且兼容 JVM 单测）。
 */
internal object SimpleJsonParser {

    fun parse(input: String): Any? {
        val reader = Reader(input)
        return runCatching {
            val value = reader.readValue()
            reader.skipWhitespace()
            if (reader.hasMore()) null else value
        }.getOrNull()
    }

    private class Reader(private val src: String) {
        private var pos = 0

        fun hasMore(): Boolean = pos < src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (!hasMore()) error("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> error("Unexpected token '${src[pos]}' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (!peek('"')) error("Expected object key at $pos")
                val key = readString()
                skipWhitespace()
                expectColon()
                val value = readValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) error("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("Unknown escape \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (peek('.')) {
                isFloat = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (peek('e') || peek('E')) {
                isFloat = true
                pos++
                if (peek('+') || peek('-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) token.toDouble() else token.toLong()
        }

        private fun readLiteral(expected: String, result: Any?): Any? {
            if (!src.startsWith(expected, pos)) error("Expected $expected at $pos")
            pos += expected.length
            return result
        }

        private fun peek(c: Char): Boolean = pos < src.length && src[pos] == c

        private fun expectColon() {
            if (!peek(':')) error("Expected ':' at $pos")
            pos++
        }
    }
}
