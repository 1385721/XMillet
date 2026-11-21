package io.github.univers629.millet.ui

import android.content.Context
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * 模块配置 JSON 备份导出、导入恢复与重置工具。
 * 导入先校验全部配置，重置仅写入 [PrefsDefaults] 定义的核心键。
 */
object BackupRestoreHelper {

    private const val SCHEMA_KEY = "schemaVersion"
    private const val SCHEMA_VERSION = 1

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
        PrefsKeys.FREEZE_STATS,
        PrefsKeys.FREEZE_DUAL_APP
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
        root.put(SCHEMA_KEY, SCHEMA_VERSION)

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
     * 完整校验后恢复配置；无版本备份兼容旧逗号分隔字符串格式。
     */
    fun importFromJson(context: Context, jsonText: String) {
        val tokener = JSONTokener(jsonText)
        val root = tokener.nextValue()
        require(root is JSONObject) { "Invalid backup JSON" }
        while (tokener.more()) {
            require(tokener.next() in " \t\r\n") { "Trailing backup JSON content" }
        }
        val version = if (root.has(SCHEMA_KEY)) {
            val value = root.get(SCHEMA_KEY)
            require(value is Int && value == SCHEMA_VERSION) { "Unsupported backup schema" }
            value
        } else {
            0
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key == SCHEMA_KEY || key in PrefsKeys.ALL_KEYS) { "Unknown backup key: $key" }
        }

        val booleans = LinkedHashMap<String, Boolean>()
        val sets = LinkedHashMap<String, Set<String>>()
        val strings = LinkedHashMap<String, String>()
        for (key in BOOLEAN_KEYS) {
            if (root.has(key)) {
                val value = root.get(key)
                require(value is Boolean) { "Expected Boolean for $key" }
                booleans[key] = value
            }
        }
        for (key in STRING_SET_KEYS) {
            if (root.has(key)) sets[key] = parsePolicySet(key, root.get(key), version)
        }
        if (root.has(PrefsKeys.LOG_TYPE)) {
            val value = root.get(PrefsKeys.LOG_TYPE)
            require(value is String && value in setOf("xposed", "file")) { "Invalid logType" }
            strings[PrefsKeys.LOG_TYPE] = value
        }
        if (root.has(PrefsKeys.LOG_LEVEL)) {
            val value = root.get(PrefsKeys.LOG_LEVEL)
            require(value is String) { "Expected String for logLevel" }
            strings[PrefsKeys.LOG_LEVEL] = when (value.lowercase(Locale.ROOT)) {
                "debug", "d" -> "debug"
                "info", "i", "verbose", "v" -> "info"
                "warn", "w" -> "warn"
                "error", "e" -> "error"
                else -> throw IllegalArgumentException("Invalid logLevel")
            }
        }
        require(booleans.isNotEmpty() || sets.isNotEmpty() || strings.isNotEmpty()) { "Empty backup" }

        val sp = PrefReader.openUiPrefs(context)
        val white = sets[PrefsKeys.WHITE_APP_SET] ?: PrefReader.readUiStringSet(sp, PrefsKeys.WHITE_APP_SET)
        val force = sets[PrefsKeys.FORCE_FREEZE_SET] ?: PrefReader.readUiStringSet(sp, PrefsKeys.FORCE_FREEZE_SET)
        require(white.none { it in force }) { "Conflicting whiteAppSet and forceFreezeSet targets" }

        PrefReader.editAllAndNotify(sp, clearFirst = false) {
            for ((key, value) in booleans) putBoolean(key, value)
            for ((key, value) in sets) putStringSet(key, value)
            for ((key, value) in strings) putString(key, value)
        }
    }

    private fun parsePolicySet(key: String, raw: Any, version: Int): Set<String> {
        val result = LinkedHashSet<String>()
        when (raw) {
            is JSONArray -> {
                for (index in 0 until raw.length()) {
                    val target = raw.get(index)
                    require(target is String && AppUidUtils.validPolicyTarget(target)) {
                        "Invalid policy target for $key at index $index"
                    }
                    result.add(target)
                }
            }
            is String -> {
                require(version == 0) { "Expected string array for $key" }
                var body = raw.trim()
                if (body.startsWith("[") || body.endsWith("]")) {
                    require(body.startsWith("[") && body.endsWith("]")) { "Invalid legacy set for $key" }
                    body = body.substring(1, body.length - 1).trim()
                }
                if (body.isNotEmpty()) {
                    for (item in body.split(',')) {
                        val target = item.trim()
                        require(AppUidUtils.validPolicyTarget(target)) { "Invalid policy target for $key" }
                        result.add(target)
                    }
                }
            }
            else -> throw IllegalArgumentException("Expected string array for $key")
        }
        return result
    }

    /**
     * 恢复出厂默认配置：单事务重置全部 Hook 键为 [PrefsDefaults] 默认值并同步。
     */
    fun resetToDefaults(context: Context) {
        val sp = PrefReader.openUiPrefs(context)
        PrefReader.editAllAndNotify(sp, clearFirst = false) {
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
