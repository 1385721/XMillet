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

    /** 澎湃 OS 版本名称（如 "OS1.0"、"OS2.0"、"OS3.0"）；未定义时为空 */
    val miOsVersionName: String by lazy {
        getSystemProperty("ro.mi.os.version.name", "")
    }

    /** MIUI 版本名称（如 "V140"、"V125"）；澎湃 OS 上同样有值，仅作 MIUI 14 的识别补充 */
    val miuiVersionName: String by lazy {
        getSystemProperty("ro.miui.ui.version.name", "")
    }

    /** MIUI 14 判定：澎湃 OS 属性为空，且 MIUI 版本属性以 V14 开头。 */
    val isMiui14: Boolean
        get() = miOsVersionName.isBlank() && miuiVersionName.startsWith("V14", ignoreCase = true)

    /** 宿主 ROM 的版本档位：MIUI 14 与澎湃 OS1 / OS2 / OS3。 */
    enum class RomTier(val major: Int) {
        MIUI14(14),
        OS1(1),
        OS2(2),
        OS3(3)
    }

    /**
     * 先按 `ro.miui.ui.version.name` 识别 MIUI 14，再按 `ro.mi.os.version.name` 识别澎湃 OS。
     * 无法识别时返回 null。
     */
    val romTier: RomTier?
        get() {
            if (isMiui14) return RomTier.MIUI14
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

    /** MIUI 14 与澎湃 OS1 使用旧版 Millet 安装门控；目标成员由能力探测确认。 */
    val isLegacyMilletStack: Boolean
        get() = when (romTier) {
            RomTier.MIUI14, RomTier.OS1 -> true
            else -> false
        }

    /** 形如 `澎湃 OS3.0 (Android 16 · SDK 36)`、`MIUI 14 (Android 13 · SDK 33)` 的一行环境描述 */
    fun describeRom(): String {
        val romLabel = when {
            isMiui14 -> "MIUI 14"
            miOsVersionName.isNotBlank() -> "澎湃 $miOsVersionName"
            miuiVersionName.isNotBlank() -> "MIUI $miuiVersionName"
            else -> "未知 ROM"
        }
        return "$romLabel (Android $androidRelease · SDK $sdkInt)"
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
