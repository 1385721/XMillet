package io.github.univers629.millet.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

/**
 * 配置读取、缓存刷新与 UI 写盘热更新器。
 *
 * 1. Hook 侧通过 `XposedModule#getRemotePreferences("Settings")` 读取配置。
 * 2. 分别维护 `system_server` 与 `com.miui.powerkeeper` 两个进程的配置快照，支持按 `PrefGroup` 增量重载。
 * 3. UI 侧使用 Device-Protected Storage 下的 `Settings.xml`，优先以 `MODE_WORLD_READABLE | MODE_MULTI_PROCESS` 打开，
 *    失败时自动回退至 `MODE_MULTI_PROCESS` / `MODE_PRIVATE`，并在每次写入后通过 Binder 触发双进程热更新。
 */
object PrefReader {

    @Volatile
    var systemPrefs: PrefsSnapshot = PrefsSnapshot()
        private set

    @Volatile
    var powerKeeperPrefs: PrefsSnapshot = PrefsSnapshot()
        private set

    @Volatile
    private var isSystemProcessActive: Boolean = false

    @Volatile
    private var isPowerKeeperProcessActive: Boolean = false

    /** system_server 侧配置分组热更新后的联动回调（由 SystemHooks 注册） */
    @Volatile
    var onSystemGroupReloaded: ((key: String, group: PrefGroup, snapshot: PrefsSnapshot) -> Unit)? = null

    /** powerkeeper 侧配置分组热更新后的联动回调（由 PowerKeeperHooks 注册） */
    @Volatile
    var onPowerKeeperGroupReloaded: ((key: String, group: PrefGroup, snapshot: PrefsSnapshot) -> Unit)? = null

    /**
     * 获取当前进程生效的配置快照（供 Logger 等公共组件读取）。
     */
    fun activePrefs(): PrefsSnapshot {
        return when {
            isSystemProcessActive -> systemPrefs
            isPowerKeeperProcessActive -> powerKeeperPrefs
            else -> systemPrefs
        }
    }

    /**
     * 初始化 system_server 进程配置快照。
     */
    fun initSystemPrefs(modernModule: XposedModule? = null): PrefsSnapshot {
        isSystemProcessActive = true
        if (modernModule != null) {
            ModernBridge.xposedModule = modernModule
        }
        val rawMap = readRawMapFromRemotePrefs()
        systemPrefs = buildFullSnapshot(rawMap)
        return systemPrefs
    }

    /**
     * 初始化 com.miui.powerkeeper 进程配置快照。
     */
    fun initPowerKeeperPrefs(modernModule: XposedModule? = null): PrefsSnapshot {
        isPowerKeeperProcessActive = true
        if (modernModule != null) {
            ModernBridge.xposedModule = modernModule
        }
        val rawMap = readRawMapFromRemotePrefs()
        powerKeeperPrefs = buildFullSnapshot(rawMap)
        return powerKeeperPrefs
    }

    /**
     * 按变更的键名增量重载 system_server 侧配置。
     */
    fun reloadSystemByKey(changedKey: String) {
        val rawMap = readRawMapFromRemotePrefs()
        val prev = systemPrefs
        val group = PrefGroup.fromKey(changedKey)
        val updated = if (group == null) {
            buildFullSnapshot(rawMap)
        } else {
            mergeGroupIntoSnapshot(prev, rawMap, group)
        }
        systemPrefs = updated
        val tag = if (group == null) "整表[$changedKey]" else changedKey
        logSnapshot("system_server", tag, updated)

        if (group == null || group == PrefGroup.MILLET_FLAGS) {
            if (!updated.simplifyLog &&
                (changedKey == PrefsKeys.SIMPLIFY_LOG || prev.simplifyLog != updated.simplifyLog)
            ) {
                dispatchSystemReloadCallback(PrefsKeys.SIMPLIFY_LOG, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.BATTERY_CHANGE_OBSERVE ||
                prev.batteryChangeObserve != updated.batteryChangeObserve
            ) {
                dispatchSystemReloadCallback(PrefsKeys.BATTERY_CHANGE_OBSERVE, PrefGroup.MILLET_FLAGS, updated)
            }
            if (changedKey == PrefsKeys.FREEZE_STATS ||
                prev.freezeStats != updated.freezeStats
            ) {
                dispatchSystemReloadCallback(PrefsKeys.FREEZE_STATS, PrefGroup.MILLET_FLAGS, updated)
            }
        }
        if (group == null || group == PrefGroup.APP_SETS) {
            if (changedKey == PrefsKeys.MESSAGE_APP_SET || prev.messageAppSet != updated.messageAppSet) {
                dispatchSystemReloadCallback(PrefsKeys.MESSAGE_APP_SET, PrefGroup.APP_SETS, updated)
            }
            if (changedKey == PrefsKeys.WHITE_APP_SET || prev.whiteAppSet != updated.whiteAppSet) {
                dispatchSystemReloadCallback(PrefsKeys.WHITE_APP_SET, PrefGroup.APP_SETS, updated)
            }
            if (changedKey == PrefsKeys.FORCE_FREEZE_SET || prev.forceFreezeSet != updated.forceFreezeSet) {
                dispatchSystemReloadCallback(PrefsKeys.FORCE_FREEZE_SET, PrefGroup.APP_SETS, updated)
            }
        }
    }

    private fun dispatchSystemReloadCallback(key: String, group: PrefGroup, snapshot: PrefsSnapshot) {
        try {
            onSystemGroupReloaded?.invoke(key, group, snapshot)
        } catch (t: Throwable) {
            Logger.error("onSystemGroupReloaded [$key] failed", t)
        }
    }

    /**
     * 按变更的键名增量重载 powerkeeper 侧配置。
     */
    fun reloadPowerKeeperByKey(changedKey: String) {
        val rawMap = readRawMapFromRemotePrefs()
        val prev = powerKeeperPrefs
        val group = PrefGroup.fromKey(changedKey)
        val updated = if (group == null) {
            buildFullSnapshot(rawMap)
        } else {
            mergeGroupIntoSnapshot(prev, rawMap, group)
        }
        powerKeeperPrefs = updated
        val tag = if (group == null) "整表[$changedKey]" else changedKey
        logSnapshot("powerkeeper", tag, updated)

        val appSetsChanged = prev.whiteAppSet != updated.whiteAppSet ||
            prev.messageAppSet != updated.messageAppSet ||
            prev.forceFreezeSet != updated.forceFreezeSet
        if (group == PrefGroup.APP_SETS || (group == null && appSetsChanged)) {
            try {
                onPowerKeeperGroupReloaded?.invoke(changedKey, PrefGroup.APP_SETS, updated)
            } catch (t: Throwable) {
                Logger.error("onPowerKeeperGroupReloaded [$changedKey] failed", t)
            }
        }
    }

    /**
     * 打印一份快照的关键开关与名单大小。
     *
     * 必须用 `alwaysWarn`：`Logger.info` 受 `isLog` 门控，而这里恰恰是要观测 `isLog` 本身。
     */
    private fun logSnapshot(scene: String, tag: String, snapshot: PrefsSnapshot) {
        Logger.alwaysWarn(
            "配置快照[$scene][$tag]: isLog=${snapshot.isLog}, simplifyLog=${snapshot.simplifyLog}, " +
                "disableKill=${snapshot.disableKill}, disableCharge=${snapshot.disableCharge}, " +
                "disableScreenOnThaw=${snapshot.disableScreenOnThaw}, " +
                "freezeStats=${snapshot.freezeStats}, " +
                "enableDoze=${snapshot.enableDoze}, whiteAppSet=${snapshot.whiteAppSet.size}, " +
                "messageAppSet=${snapshot.messageAppSet.size}, ignoreNetworkWake=${snapshot.ignoreNetworkWakeSet.size}"
        )
    }

    /**
     * 读取完整键值表（通过 Modern LibXposed getRemotePreferences 读取）。
     */
    private fun readRawMapFromRemotePrefs(): Map<String, Any?> {
        val modernModule = ModernBridge.xposedModule
        if (modernModule != null) {
            try {
                val remotePrefs = modernModule.getRemotePreferences(PrefsKeys.PREFS_FILE_NAME)
                val all = remotePrefs.all
                if (!all.isNullOrEmpty()) {
                    return HashMap(all)
                }
            } catch (_: Throwable) {
            }
        }

        // 框架未注入或 RemotePreferences 尚未就绪时返回空表，由默认值常量表兜底
        return emptyMap()
    }

    private fun buildFullSnapshot(raw: Map<String, Any?>): PrefsSnapshot {
        var snapshot = PrefsSnapshot()
        for (group in PrefGroup.entries) {
            snapshot = mergeGroupIntoSnapshot(snapshot, raw, group)
        }
        return snapshot
    }

    private fun mergeGroupIntoSnapshot(
        base: PrefsSnapshot,
        raw: Map<String, Any?>,
        group: PrefGroup
    ): PrefsSnapshot {
        return when (group) {
            PrefGroup.MILLET_FLAGS -> base.copy(
                isLog = getBooleanOrDefault(raw, PrefsKeys.IS_LOG),
                simplifyLog = getBooleanOrDefault(raw, PrefsKeys.SIMPLIFY_LOG),
                disableKill = getBooleanOrDefault(raw, PrefsKeys.DISABLE_KILL),
                disableCharge = getBooleanOrDefault(raw, PrefsKeys.DISABLE_CHARGE),
                disableScreenOnThaw = getBooleanOrDefault(raw, PrefsKeys.DISABLE_SCREEN_ON_THAW),
                blockAddPowerSaveWhitelistApps = getBooleanOrDefault(
                    raw,
                    PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS
                ),
                batteryChangeObserve = getBooleanOrDefault(raw, PrefsKeys.BATTERY_CHANGE_OBSERVE),
                logToLocal = getBooleanOrDefault(raw, PrefsKeys.LOG_TO_LOCAL),
                freezeStats = getBooleanOrDefault(raw, PrefsKeys.FREEZE_STATS)
            )

            PrefGroup.DOZE_FLAGS -> base.copy(
                dozeLog = getBooleanOrDefault(raw, PrefsKeys.DOZE_LOG),
                enableDoze = getBooleanOrDefault(raw, PrefsKeys.ENABLE_DOZE),
                skipStateCheck = getBooleanOrDefault(raw, PrefsKeys.SKIP_STATE_CHECK),
                disableMotionListener = getBooleanOrDefault(raw, PrefsKeys.DISABLE_MOTION_LISTENER)
            )

            PrefGroup.APP_SETS -> base.copy(
                whiteAppSet = getStringSetOrDefault(raw, PrefsKeys.WHITE_APP_SET),
                messageAppSet = getStringSetOrDefault(raw, PrefsKeys.MESSAGE_APP_SET),
                forceFreezeSet = getStringSetOrDefault(raw, PrefsKeys.FORCE_FREEZE_SET),
                blockThawSet = getStringSetOrDefault(raw, PrefsKeys.BLOCK_THAW_SET),
                forbidSyncBinderSet = getStringSetOrDefault(raw, PrefsKeys.FORBID_SYNC_BINDER_SET),
                notFreezeWhenHasFgsSet = getStringSetOrDefault(raw, PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET),
                ignoreNetworkWakeSet = getStringSetOrDefault(raw, PrefsKeys.IGNORE_NETWORK_WAKE_SET)
            )

            PrefGroup.LOG_CONFIG -> base.copy(
                logType = (raw[PrefsKeys.LOG_TYPE] as? String) ?: PrefsDefaults.LOG_TYPE,
                logLevel = (raw[PrefsKeys.LOG_LEVEL] as? String) ?: PrefsDefaults.LOG_LEVEL
            )
        }
    }

    private fun getBooleanOrDefault(raw: Map<String, Any?>, key: String): Boolean {
        return (raw[key] as? Boolean) ?: PrefsDefaults.booleanDefault(key)
    }

    private fun getStringSetOrDefault(raw: Map<String, Any?>, key: String): Set<String> {
        val value = raw[key]
        if (value is Collection<*>) {
            val result = LinkedHashSet<String>()
            for (item in value) {
                val str = (item as? String)?.trim()
                if (!str.isNullOrEmpty()) {
                    result.add(str)
                }
            }
            return result
        }
        return PrefsDefaults.stringSetDefault(key)
    }

    // ==================== UI 侧读写与热更新封装 ====================

    private const val MODE_WORLD_READABLE_LEGACY = 0x0001
    private const val MODE_MULTI_PROCESS_LEGACY = 0x0004

    /**
     * 打开 Device-Protected Storage 下的 Settings.xml（供 App UI 进程调用）。
     * 优先使用 MODE_WORLD_READABLE | MODE_MULTI_PROCESS (5)，以配合 LSPosed XSharedPreferences；
     * 若宿主机限制 MODE_WORLD_READABLE 则依次回退到 MODE_MULTI_PROCESS (4) 与 MODE_PRIVATE (0)。
     */
    @SuppressLint("WorldReadableFiles")
    fun openUiPrefs(context: Context): SharedPreferences {
        val deContext = try {
            context.createDeviceProtectedStorageContext() ?: context
        } catch (_: Throwable) {
            context
        }
        return try {
            @Suppress("DEPRECATION")
            deContext.getSharedPreferences(
                PrefsKeys.PREFS_FILE_NAME,
                MODE_WORLD_READABLE_LEGACY or MODE_MULTI_PROCESS_LEGACY
            )
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                deContext.getSharedPreferences(PrefsKeys.PREFS_FILE_NAME, MODE_MULTI_PROCESS_LEGACY)
            } catch (_: Throwable) {
                deContext.getSharedPreferences(PrefsKeys.PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    /**
     * UI 侧读取布尔开关（与 Hook 侧共享同一默认值表）。
     */
    fun readUiBoolean(prefs: SharedPreferences, key: String): Boolean {
        return prefs.getBoolean(key, PrefsDefaults.booleanDefault(key))
    }

    /**
     * UI 侧读取应用包名集合（返回可变 LinkedHashSet 副本，与 Hook 侧共享同一默认值表）。
     */
    fun readUiStringSet(prefs: SharedPreferences, key: String): LinkedHashSet<String> {
        val defaultSet = PrefsDefaults.stringSetDefault(key)
        val stored = prefs.getStringSet(key, defaultSet) ?: defaultSet
        val copy = LinkedHashSet<String>()
        for (item in stored) {
            val trimmed = item.trim()
            if (trimmed.isNotEmpty()) {
                copy.add(trimmed)
            }
        }
        return copy
    }

    /**
     * 归一化并去空白，确保写入 Settings 与 RemotePreferences 的集合语义一致。
     */
    fun sanitizeStringSet(values: Set<String>): LinkedHashSet<String> {
        val cleanCopy = LinkedHashSet<String>()
        for (pkg in values) {
            val trimmed = pkg.trim()
            if (trimmed.isNotEmpty()) {
                cleanCopy.add(trimmed)
            }
        }
        return cleanCopy
    }

    private fun applyPrefsSuppressed(
        prefs: SharedPreferences,
        clearFirst: Boolean = false,
        block: SharedPreferences.Editor.() -> Unit
    ) {
        XposedPrefsBridge.withLocalListenerSuppressed {
            val editor = prefs.edit()
            if (clearFirst) {
                editor.clear()
            }
            editor.block()
            editor.apply()
        }
    }

    private fun writeKeysAndNotify(
        prefs: SharedPreferences,
        keys: Set<String>,
        notifyKey: String,
        block: SharedPreferences.Editor.() -> Unit
    ) {
        applyPrefsSuppressed(prefs, block = block)
        XposedPrefsBridge.syncAndNotifyKeys(keys, notifyKey)
    }

    /**
     * UI 侧写入布尔配置，同步远程配置并触发一次跨进程热重载。
     */
    fun putBooleanAndNotify(prefs: SharedPreferences, key: String, value: Boolean) =
        writeKeysAndNotify(prefs, setOf(key), key) { putBoolean(key, value) }

    /**
     * UI 侧写入字符串配置，同步远程配置并触发一次跨进程热重载。
     */
    fun putStringAndNotify(prefs: SharedPreferences, key: String, value: String) =
        writeKeysAndNotify(prefs, setOf(key), key) { putString(key, value) }

    /**
     * UI 侧写入包名集合配置，同步远程配置并触发一次跨进程热重载。
     */
    fun putStringSetAndNotify(prefs: SharedPreferences, key: String, values: Set<String>) =
        writeKeysAndNotify(prefs, setOf(key), key) { putStringSet(key, sanitizeStringSet(values)) }

    /**
     * UI 侧在单次事务中批量更新多个应用集合，并只发一次远程同步与热重载通知。
     */
    fun putStringSetsAndNotify(
        prefs: SharedPreferences,
        updates: Map<String, Set<String>>,
        notifyKey: String = updates.keys.firstOrNull() ?: "*"
    ) {
        if (updates.isEmpty()) return
        writeKeysAndNotify(prefs, updates.keys, notifyKey) {
            for ((key, values) in updates) {
                putStringSet(key, sanitizeStringSet(values))
            }
        }
    }

    /**
     * UI 侧在单次事务中批量编辑配置（如 JSON 恢复 / 恢复默认），避免逐键反复触发推表。
     */
    fun editAllAndNotify(
        prefs: SharedPreferences,
        clearFirst: Boolean = false,
        block: SharedPreferences.Editor.() -> Unit
    ) {
        applyPrefsSuppressed(prefs, clearFirst = clearFirst, block = block)
        XposedPrefsBridge.syncAndNotifyAll()
    }
}
