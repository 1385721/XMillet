package io.github.univers629.millet.ui.util

import android.os.Build
import java.util.regex.Pattern

/**
 * 设备与系统环境参数工具类（逻辑参考 HyperCeiler 关于页面实现）：
 * - 设备型号：优先读取小米/红米市场推广名 (ro.product.marketname)，兜底使用 Build.MODEL
 * - Android 版本：读取 ro.build.version.release 与 API 级别
 * - OS 版本：读取并对比 HyperOS / MIUI 增量版本号与分包版本后缀 (persist.sys.xms.version / ro.mi.xms.version.incremental)
 */
object AboutPhoneUtils {
    private val VERSION_PATTERN = Pattern.compile("^[a-zA-Z][0-9]{1,3}$")

    fun getProp(name: String, def: String = ""): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, name, def) as? String ?: def
        }.getOrDefault(def)
    }

    private fun getXmsVersion(): String = getProp("persist.sys.xms.version")
    private fun getRoXmsVersion(): String = getProp("ro.mi.xms.version.incremental")
    private fun getOsVersionCode(): String {
        val str = getProp("ro.mi.os.version.incremental")
        return if (str.isEmpty() || !str.startsWith("OS") || str.length <= 2) str else str.substring(2)
    }

    private fun isValid(str: String): Boolean = str.isNotEmpty() && VERSION_PATTERN.matcher(str).matches()

    private fun compareValidVersion(s: String, s2: String): String {
        val c1 = s[0].lowercaseChar()
        val c2 = s2[0].lowercaseChar()
        if (c1 != c2) return if (c1 > c2) s else s2
        return try {
            val int1 = s.substring(1).toInt()
            val int2 = s2.substring(1).toInt()
            if (int1 >= int2) s else s2
        } catch (_: Exception) {
            s2
        }
    }

    private fun insertSuffixBeforeBeta(str: String, suffix: String): String {
        return when {
            str.endsWith("Beta") -> str.substring(0, str.length - 4).trim() + "." + suffix + " Beta"
            else -> "$str.$suffix"
        }
    }

    fun addVersionSuffix(): String {
        var xmsVersion = getXmsVersion()
        val roXmsVersion = getRoXmsVersion()
        val osVersionCode = getOsVersionCode()

        val isXmsValid = isValid(xmsVersion)
        val isRoXmsValid = isValid(roXmsVersion)
        if (!isXmsValid && !isRoXmsValid) {
            return osVersionCode
        }
        xmsVersion = if (isXmsValid) compareValidVersion(xmsVersion, roXmsVersion) else roXmsVersion
        return insertSuffixBeforeBeta(osVersionCode, xmsVersion)
    }

    fun getDeviceModel(): String {
        val marketName = getProp("ro.product.marketname")
        return marketName.ifEmpty { Build.MODEL }
    }

    fun getAndroidVersion(): String {
        val release = getProp("ro.build.version.release")
        val finalRelease = release.ifEmpty { Build.VERSION.RELEASE }
        return "$finalRelease (API ${Build.VERSION.SDK_INT})"
    }

    fun getOsVersion(): String {
        var os = addVersionSuffix()
        if (os.isEmpty()) {
            os = getProp("ro.mi.os.version.incremental")
        }
        if (os.isEmpty()) {
            os = getProp("ro.system.build.version.incremental")
        }
        if (os.isEmpty()) {
            os = getProp("ro.build.version.incremental", Build.VERSION.INCREMENTAL)
        }
        val rawIncremental = getProp("ro.mi.os.version.incremental")
        return if (rawIncremental.startsWith("OS") && !os.startsWith("OS")) {
            "OS$os"
        } else {
            os
        }
    }
}
