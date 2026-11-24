package io.github.univers629.millet.ui.data

import android.content.SharedPreferences
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.ui.util.PolicyTemplateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI 侧配置写入适配器：生产环境委托给 [PrefReader] 与 [PolicyTemplateStore] 同步远程配置并热重载，
 * 单测环境可替换为纯内存实现。
 */
interface PolicyWriter {
    fun putBoolean(key: String, value: Boolean)
    fun putDetailedLogging(enabled: Boolean)
    fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String)
    fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate?
    fun deleteCustomTemplate(id: String)
}

class PrefReaderPolicyWriter(private val prefs: SharedPreferences) : PolicyWriter {
    override fun putBoolean(key: String, value: Boolean) {
        PrefReader.putBooleanAndNotify(prefs, key, value)
    }

    override fun putDetailedLogging(enabled: Boolean) {
        PrefReader.editAllAndNotify(prefs) {
            putBoolean(PrefsKeys.IS_LOG, enabled)
            putBoolean(PrefsKeys.LOG_TO_LOCAL, enabled)
            putBoolean(PrefsKeys.BATTERY_CHANGE_OBSERVE, enabled)
            putBoolean(PrefsKeys.DOZE_LOG, enabled)
            putString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL)
            putString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE)
        }
    }

    override fun putStringSets(updates: Map<String, Set<String>>, notifyKey: String) {
        PrefReader.putStringSetsAndNotify(prefs, updates, notifyKey)
    }

    override fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate? =
        PolicyTemplateStore.saveCustom(prefs, id, name, flags)

    override fun deleteCustomTemplate(id: String) {
        PolicyTemplateStore.deleteCustom(prefs, id)
    }
}

/**
 * UI 层配置的唯一响应式真值源（Single Source of Truth）。
 *
 * 所有写入均在事务内同步刷新 [prefsFlow] 与 [templatesFlow]，确保 Compose 开关零帧回弹；
 * 同时保留强引用监听兜底外部写入（备份导入、恢复默认、模板批量套用等），彻底取代 `configVersion++`。
 */
class PolicyRepository(
    private val prefs: SharedPreferences,
    private val writer: PolicyWriter = PrefReaderPolicyWriter(prefs),
    bindGlobalPrefReaderHook: Boolean = false,
) {

    private val _prefsFlow = MutableStateFlow(PrefReader.readUiSnapshot(prefs))
    val prefsFlow: StateFlow<PrefsSnapshot> = _prefsFlow.asStateFlow()

    private val _templatesFlow = MutableStateFlow(PolicyTemplateStore.loadAll(prefs))
    val templatesFlow: StateFlow<List<PolicyTemplate>> = _templatesFlow.asStateFlow()

    // SharedPreferences 内部以 WeakHashMap 持有监听器，必须保留强引用避免被 GC 回收。
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null -> refreshAllFromPrefs()
            key == PolicyTemplateStore.KEY_CUSTOM_TEMPLATES ->
                _templatesFlow.value = PolicyTemplateStore.loadAll(prefs)
            key in PrefsKeys.ALL_KEYS ->
                _prefsFlow.value = PrefReader.readUiSnapshot(prefs)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (bindGlobalPrefReaderHook) {
            PrefReader.onUiPrefsMutated = { mutatedPrefs ->
                if (mutatedPrefs === prefs) {
                    refreshAllFromPrefs()
                }
            }
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        writer.putBoolean(key, value)
        _prefsFlow.value = PrefReader.readUiSnapshot(prefs)
    }

    fun setDetailedLogging(enabled: Boolean) {
        writer.putDetailedLogging(enabled)
        _prefsFlow.value = PrefReader.readUiSnapshot(prefs)
    }

    fun updateAppMemberships(
        packageName: String,
        uid: Int,
        vararg changes: Pair<String, Boolean>,
    ) {
        if (changes.isEmpty()) return
        val policyTarget = AppUidUtils.encodeQueryTarget(packageName, uid)
        val updates = LinkedHashMap<String, Set<String>>(changes.size)
        for ((key, add) in changes) {
            val currentSet = PrefReader.readUiStringSet(prefs, key)
            if (add) currentSet.add(policyTarget) else currentSet.remove(policyTarget)
            updates[key] = currentSet
        }
        writer.putStringSets(updates, changes.first().first)
        _prefsFlow.value = PrefReader.readUiSnapshot(prefs)
    }

    fun saveCustomTemplate(id: String?, name: String, flags: Set<PolicyFlag>): PolicyTemplate? {
        val saved = writer.saveCustomTemplate(id, name, flags)
        if (saved != null) {
            _templatesFlow.value = PolicyTemplateStore.loadAll(prefs)
        }
        return saved
    }

    fun deleteCustomTemplate(id: String) {
        writer.deleteCustomTemplate(id)
        _templatesFlow.value = PolicyTemplateStore.loadAll(prefs)
    }

    fun refreshAllFromPrefs() {
        _prefsFlow.value = PrefReader.readUiSnapshot(prefs)
        _templatesFlow.value = PolicyTemplateStore.loadAll(prefs)
    }
}
