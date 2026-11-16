package io.github.univers629.millet.ui

import android.content.Context
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配置 JSON 备份导出、导入恢复与重置工具。
 *
 * 修正原模块缺陷：
 * 1. 原模块在恢复空集合或拆分字符串时未过滤空串，导致 `StringSet` 被塞入 `""` 空包名；
 *    本实现统一过滤 `it.isNotEmpty()`。
 * 2. 重置默认配置时严格使用 [PrefsDefaults] 单一常量表（修正缺陷 #6），确保 `disableKill = true`
 *    与 `messageAppSet = {"com.tencent.mm"}` 在重置后与 Hook 侧默认值完全一致。
 */
object BackupRestoreHelper {

    private val BOOLEAN_KEYS: List<String> = listOf(
        PrefsKeys.IS_LOG,
        PrefsKeys.SIMPLIFY_LOG,
        PrefsKeys.DISABLE_KILL,
        PrefsKeys.DISABLE_CHARGE,
        PrefsKeys.DISABLE_SCREEN_ON_THAW,
        PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS,
        PrefsKeys.BATTERY_CHANGE_OBSERVE,
        PrefsKeys.LOG_TO_LOCAL,
        PrefsKeys.DOZE_LOG,
        PrefsKeys.ENABLE_DOZE,
        PrefsKeys.SKIP_STATE_CHECK,
        PrefsKeys.DISABLE_MOTION_LISTENER,
        PrefsKeys.FREEZE_STATS
    )

    private val STRING_SET_KEYS: List<String> = listOf(
        PrefsKeys.WHITE_APP_SET,
        PrefsKeys.MESSAGE_APP_SET,
        PrefsKeys.FORCE_FREEZE_SET,
        PrefsKeys.BLOCK_THAW_SET,
        PrefsKeys.FORBID_SYNC_BINDER_SET,
        PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET,
        PrefsKeys.IGNORE_NETWORK_WAKE_SET
    )

    /**
     * 将全部配置键导出为格式化 JSON 字符串。
     */
    fun exportToJson(context: Context): String {
        val sp = PrefReader.openUiPrefs(context)
        val root = JSONObject()

        for (key in BOOLEAN_KEYS) {
            root.put(key, PrefReader.readUiBoolean(sp, key))
        }

        for (key in STRING_SET_KEYS) {
            val rawSet = PrefReader.readUiStringSet(sp, key)
            val cleanArray = JSONArray()
            rawSet.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .forEach { cleanArray.put(it) }
            root.put(key, cleanArray)
        }

        root.put(PrefsKeys.LOG_TYPE, sp.getString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE) ?: PrefsDefaults.LOG_TYPE)
        root.put(PrefsKeys.LOG_LEVEL, sp.getString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL) ?: PrefsDefaults.LOG_LEVEL)

        return root.toString(2)
    }

    /**
     * 从 JSON 字符串恢复配置（同时兼容 JSONArray 与原版逗号分隔字符串格式，自动剔除空串 `""`）。
     */
    fun importFromJson(context: Context, jsonText: String) {
        val root = JSONObject(jsonText)
        val sp = PrefReader.openUiPrefs(context)

        PrefReader.editAllAndNotify(sp, clearFirst = false) {
            for (key in BOOLEAN_KEYS) {
                if (root.has(key)) {
                    putBoolean(key, root.optBoolean(key, PrefsDefaults.booleanDefault(key)))
                }
            }

            for (key in STRING_SET_KEYS) {
                if (!root.has(key)) continue
                val parsedSet = LinkedHashSet<String>()
                val rawValue = root.opt(key)
                when (rawValue) {
                    is JSONArray -> {
                        for (i in 0 until rawValue.length()) {
                            val item = rawValue.optString(i, "").trim()
                            if (item.isNotEmpty()) {
                                parsedSet.add(item)
                            }
                        }
                    }
                    is String -> {
                        // 兼容旧版 "[pkg1, pkg2]" 或逗号分隔格式，严格过滤空字符串
                        rawValue.removeSurrounding("[", "]")
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { parsedSet.add(it) }
                    }
                }
                putStringSet(key, parsedSet)
            }

            if (root.has(PrefsKeys.LOG_TYPE)) {
                putString(PrefsKeys.LOG_TYPE, root.optString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE))
            }
            if (root.has(PrefsKeys.LOG_LEVEL)) {
                putString(PrefsKeys.LOG_LEVEL, root.optString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL))
            }
        }
    }

    /**
     * 恢复出厂默认配置：单事务重置全部 Hook 键为 [PrefsDefaults] 默认值并同步。
     */
    fun resetToDefaults(context: Context) {
        val sp = PrefReader.openUiPrefs(context)
        PrefReader.editAllAndNotify(sp, clearFirst = true) {
            for (key in BOOLEAN_KEYS) {
                putBoolean(key, PrefsDefaults.booleanDefault(key))
            }
            for (key in STRING_SET_KEYS) {
                putStringSet(key, PrefsDefaults.stringSetDefault(key))
            }
            putString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE)
            putString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL)
        }
    }
}
