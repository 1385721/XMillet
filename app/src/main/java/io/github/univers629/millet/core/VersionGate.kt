package io.github.univers629.millet.core

import android.annotation.SuppressLint
import android.os.Build

/**
 * 版本与系统属性门控集中管理。
 * 所有 Android SDK、澎湃 OS 版本判断及设备基础信息读取均收口于此。
 */
object VersionGate {

    /** 当前 Android SDK 级别 */
    val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    /**
     * S9 / S10 广播拦截管线二选一门控：
     * Android 14 (API 34, SDK_INT > 33) 走 BroadcastSkipPolicy#shouldSkipMessage；
     * Android 13 及以下 (SDK_INT <= 33) 走 BroadcastQueue#deliverToRegisteredReceiverLocked。
     */
    val isSdkAfter33: Boolean
        get() = Build.VERSION.SDK_INT > 33

    /** Android 发行版字符串（如 "13"、"14"） */
    val androidRelease: String
        get() = Build.VERSION.RELEASE ?: "unknown"

    /** 澎湃 OS 版本名称（如 "OS1.0"、"OS2.0"、"OS3.0"） */
    val miOsVersionName: String by lazy {
        getSystemProperty("ro.mi.os.version.name", "")
    }

    /**
     * P4（MilletPolicy#onCloudWhiteListChanged）安装门控：
     * 原规格中 AbstractC0207.isSystemProcess() 实际判断 "OS1.0".equals(ro.mi.os.version.name)。
     */
    val isHyperOs1: Boolean
        get() = "OS1.0" == miOsVersionName

    /**
     * 宿主 ROM 的版本档位，只区分澎湃 OS1 / OS2 / OS3。
     *
     * 各代的宿主子系统差异很大（OS1/OS2 有 `com.miui.powerkeeper.millet`，
     * OS3 起整块移除），文案与诊断必须按档位区分，不能再用一套固定描述。
     */
    enum class RomTier(val major: Int) {
        OS1(1),
        OS2(2),
        OS3(3)
    }

    /**
     * 依据 `ro.mi.os.version.name` 判断档位。
     *
     * 读不到或格式不认识时返回 null：调用方必须按「不做假设」处理，
     * 而不是把未知环境猜成某一代。
     */
    val romTier: RomTier?
        get() {
            val osName = miOsVersionName
            if (!osName.startsWith("OS", ignoreCase = true)) return null
            val major = osName.substring(2).substringBefore('.').toIntOrNull() ?: return null
            return when {
                major <= 1 -> RomTier.OS1
                major == 2 -> RomTier.OS2
                major >= 3 -> RomTier.OS3
                else -> null
            }
        }

    /** 形如 `澎湃 OS3.0 (Android 16 · SDK 36)` 的一行环境描述 */
    fun describeRom(): String {
        val osLabel = miOsVersionName.takeIf { it.isNotBlank() }
            ?.let { "澎湃 $it" }
            ?: "未知 ROM"
        return "$osLabel (Android $androidRelease · SDK $sdkInt)"
    }

    /**
     * 通过反射读取 android.os.SystemProperties，失败时返回兜底默认值，不抛异常。
     */
    @SuppressLint("PrivateApi")
    fun getSystemProperty(key: String, defaultValue: String = ""): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            (getMethod.invoke(null, key, defaultValue) as? String) ?: defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }

    /**
     * 拼装 P1（电量与性能启动）时的环境诊断信息字符串。
     */
    fun buildDeviceEnvSummary(): String {
        val incremental = Build.VERSION.INCREMENTAL ?: "unknown"
        val release = androidRelease
        val sdk = sdkInt
        val kernel = try {
            System.getProperty("os.version") ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
        val model = Build.MODEL ?: "unknown"
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        return "电量与性能启动,当前系统版本:$incremental,当前Android版本:$release,当前AndroidSDK:$sdk,内核版本:$kernel,手机型号:$model,手机厂商:$manufacturer"
    }
}
