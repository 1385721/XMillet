package io.github.univers629.millet.core

import java.util.Locale

/**
 * 配置 Schema 版本与防御性清洗器。
 *
 * 确保无论是 UI 进程首启升级、备份导入还是 Hook 进程读取远程配置，
 * 7 个策略集合的互斥/依附关系与日志枚举值始终保持合法一致。
 */
object PrefsSchema {

    const val CURRENT_VERSION = 1

    /** 仅存储在 UI 本地 SharedPreferences 中的结构版本号，不进入 RemotePreferences。 */
    const val KEY_SCHEMA_VERSION = "prefsSchemaVersion"

    data class SanitizeResult(
        val snapshot: PrefsSnapshot,
        val modified: Boolean,
    )

    fun normalizeLogType(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "xposed", "file" -> raw.trim().lowercase(Locale.ROOT)
        else -> PrefsDefaults.LOG_TYPE
    }

    fun normalizeLogLevel(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "debug", "d" -> "debug"
        "info", "i", "verbose", "v" -> "info"
        "warn", "w" -> "warn"
        "error", "e" -> "error"
        else -> PrefsDefaults.LOG_LEVEL
    }

    /**
     * 对 [PrefsSnapshot] 进行防御性清洗（7 个策略集合安全优先收敛 + 日志配置归一化）。
     */
    fun sanitizeSnapshot(raw: PrefsSnapshot): SanitizeResult {
        val rawSets = linkedMapOf(
            PrefsKeys.WHITE_APP_SET to raw.whiteAppSet,
            PrefsKeys.MESSAGE_APP_SET to raw.messageAppSet,
            PrefsKeys.FORCE_FREEZE_SET to raw.forceFreezeSet,
            PrefsKeys.BLOCK_THAW_SET to raw.blockThawSet,
            PrefsKeys.FORBID_SYNC_BINDER_SET to raw.forbidSyncBinderSet,
            PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET to raw.notFreezeWhenHasFgsSet,
            PrefsKeys.IGNORE_NETWORK_WAKE_SET to raw.ignoreNetworkWakeSet,
        )
        val (cleanSets, setsModified) = PolicyRules.sanitizePolicySets(rawSets)
        val cleanLogType = normalizeLogType(raw.logType)
        val cleanLogLevel = normalizeLogLevel(raw.logLevel)
        val logModified = cleanLogType != raw.logType || cleanLogLevel != raw.logLevel
        if (!setsModified && !logModified) {
            return SanitizeResult(raw, modified = false)
        }
        val cleaned = raw.copy(
            whiteAppSet = cleanSets.getValue(PrefsKeys.WHITE_APP_SET),
            messageAppSet = cleanSets.getValue(PrefsKeys.MESSAGE_APP_SET),
            forceFreezeSet = cleanSets.getValue(PrefsKeys.FORCE_FREEZE_SET),
            blockThawSet = cleanSets.getValue(PrefsKeys.BLOCK_THAW_SET),
            forbidSyncBinderSet = cleanSets.getValue(PrefsKeys.FORBID_SYNC_BINDER_SET),
            notFreezeWhenHasFgsSet = cleanSets.getValue(PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET),
            ignoreNetworkWakeSet = cleanSets.getValue(PrefsKeys.IGNORE_NETWORK_WAKE_SET),
            logType = cleanLogType,
            logLevel = cleanLogLevel,
        )
        return SanitizeResult(cleaned, modified = true)
    }
}
