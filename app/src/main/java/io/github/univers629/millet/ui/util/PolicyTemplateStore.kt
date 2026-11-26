package io.github.univers629.millet.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyRules
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PolicyTemplateCodec
import io.github.univers629.millet.core.PolicyTemplatePresets
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.core.SystemBatteryOptimizationHelper
import io.github.univers629.millet.ui.screen.InstalledAppUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.EnumSet

/**
 * 策略模板的 UI 侧存取。
 *
 * 自定义模板只存进 UI prefs 的独立键 [KEY_CUSTOM_TEMPLATES]：该键不在 `PrefsKeys.ALL_KEYS` 中，
 * `XposedPrefsBridge` 会忽略它，Hook 侧永远只看到七个策略集合本身。
 */
object PolicyTemplateStore {

    const val KEY_CUSTOM_TEMPLATES = "ui_policy_custom_templates"

    fun loadCustom(prefs: SharedPreferences): List<PolicyTemplate> =
        PolicyTemplateCodec.decode(prefs.getString(KEY_CUSTOM_TEMPLATES, null))

    fun loadAll(prefs: SharedPreferences): List<PolicyTemplate> =
        PolicyTemplatePresets.ALL + loadCustom(prefs)

    fun find(prefs: SharedPreferences, id: String): PolicyTemplate? =
        PolicyTemplatePresets.find(id) ?: loadCustom(prefs).firstOrNull { it.id == id }

    /**
     * 新建或更新自定义模板。
     *
     * @param id 为 null 时新建；否则覆盖同 ID 的自定义模板
     * @return 保存后的模板；组合不合法或名称为空时返回 null
     */
    fun saveCustom(
        prefs: SharedPreferences,
        id: String?,
        name: String,
        flags: Set<PolicyFlag>,
    ): PolicyTemplate? {
        val trimmed = name.trim().take(PolicyTemplateCodec.MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || flags.isEmpty() || !PolicyRules.isValid(flags)) return null
        val current = loadCustom(prefs).toMutableList()
        val targetId = id ?: newCustomId(current)
        val template = PolicyTemplateCodec.custom(targetId, trimmed, flags)
        val index = current.indexOfFirst { it.id == targetId }
        if (index >= 0) current[index] = template else current.add(template)
        prefs.edit().putString(KEY_CUSTOM_TEMPLATES, PolicyTemplateCodec.encode(current)).apply()
        return template
    }

    fun deleteCustom(prefs: SharedPreferences, id: String) {
        val current = loadCustom(prefs)
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return
        prefs.edit().putString(KEY_CUSTOM_TEMPLATES, PolicyTemplateCodec.encode(next)).apply()
    }

    private fun newCustomId(existing: List<PolicyTemplate>): String {
        val base = PolicyTemplateCodec.CUSTOM_ID_PREFIX + System.currentTimeMillis().toString(36)
        var candidate = base
        var suffix = 1
        while (existing.any { it.id == candidate }) {
            candidate = "${base}_$suffix"
            suffix++
        }
        return candidate
    }
}

/**
 * 七个策略集合的只读快照，用来判断应用当前匹配哪个模板。
 *
 * `InstalledAppUiModel` 不含「忽略网络唤醒」，所以这里直接从 prefs 读取全部七个集合，
 * 而不是拼凑模型上的布尔字段。
 */
class PolicyMembershipSnapshot(private val sets: Map<PolicyFlag, Set<String>>) {

    fun flagsOf(packageName: String, uid: Int): Set<PolicyFlag> {
        val result = EnumSet.noneOf(PolicyFlag::class.java)
        for (flag in PolicyFlag.entries) {
            val set = sets[flag] ?: continue
            if (AppUidUtils.containsPolicyTarget(set, packageName, uid)) result.add(flag)
        }
        return result
    }

    fun flagsOf(app: InstalledAppUiModel): Set<PolicyFlag> = flagsOf(app.packageName, app.uid)

    /** 找到与应用当前状态完全一致的第一个模板；没有配置时返回 null。 */
    fun matchTemplate(app: InstalledAppUiModel, templates: List<PolicyTemplate>): PolicyTemplate? {
        val flags = flagsOf(app)
        if (flags.isEmpty()) return null
        return templates.firstOrNull { it.matches(flags) }
    }

    companion object {
        fun read(prefs: SharedPreferences): PolicyMembershipSnapshot =
            PolicyMembershipSnapshot(
                PolicyFlag.entries.associateWith { PrefReader.readUiStringSet(prefs, it.key) }
            )

        fun fromSnapshot(snapshot: PrefsSnapshot): PolicyMembershipSnapshot =
            PolicyMembershipSnapshot(
                mapOf(
                    PolicyFlag.WHITE to snapshot.whiteAppSet,
                    PolicyFlag.MESSAGE to snapshot.messageAppSet,
                    PolicyFlag.FORCE_FREEZE to snapshot.forceFreezeSet,
                    PolicyFlag.BLOCK_THAW to snapshot.blockThawSet,
                    PolicyFlag.FORBID_SYNC to snapshot.forbidSyncBinderSet,
                    PolicyFlag.NOT_FREEZE_FGS to snapshot.notFreezeWhenHasFgsSet,
                    PolicyFlag.IGNORE_NETWORK_WAKE to snapshot.ignoreNetworkWakeSet,
                )
            )
    }
}

/**
 * 模板套用 / 移除的唯一写入口。
 *
 * 语义与 `AppProfileScreenMiuix` 等价：
 * 1. 七个集合在一次 `putStringSetsAndNotify` 事务中写完，只触发一次热重载；
 * 2. 主用户应用的免冻白名单发生变化时，同步系统省电策略，失败则按配置页的方式回滚；
 * 3. 双开实例只写模块名单，系统省电策略按包名共享，不为双开单独修改。
 */
object PolicyTemplateApplier {

    /** 同步省电策略可能跨越页面生命周期，放在进程级作用域里，避免离开页面导致回滚丢失。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val batteryMutex = Mutex()

    data class Result(
        /** 实际写入了新组合的应用数 */
        val changed: Int,
        /** 已经是目标组合、无需写入的应用数 */
        val unchanged: Int,
        /** UID 或包名不受模块管理而被跳过的应用数 */
        val skipped: Int,
        /** 需要后台同步系统省电策略的主用户应用数 */
        val pendingBatterySync: Int,
    )

    fun isTargetable(app: InstalledAppUiModel): Boolean =
        AppUidUtils.isManagedAppUid(app.uid) &&
            AppUidUtils.validPolicyTarget(AppUidUtils.encodeQueryTarget(app.packageName, app.uid))

    /**
     * 把 [apps] 的七项策略写成 [flags]；传空集合即「移除全部策略」。
     *
     * @param onPolicyChanged 每次落盘后回调（含省电策略回滚），宿主据此重建应用列表
     */
    fun apply(
        context: Context,
        apps: List<InstalledAppUiModel>,
        flags: Set<PolicyFlag>,
        onPolicyChanged: () -> Unit,
    ): Result {
        require(PolicyRules.isValid(flags)) { "Invalid policy combination: $flags" }
        val appContext = context.applicationContext
        val prefs = PrefReader.openUiPrefs(appContext)
        val targets = apps.filter(::isTargetable)
            .distinctBy { AppUidUtils.encodeQueryTarget(it.packageName, it.uid) }
        val skipped = apps.size - targets.size
        val snapshot = PolicyMembershipSnapshot.read(prefs)
        val previous = targets.associateWith { snapshot.flagsOf(it) }
        val changed = targets.filter { previous[it] != flags }
        if (changed.isEmpty()) {
            return Result(changed = 0, unchanged = targets.size, skipped = skipped, pendingBatterySync = 0)
        }

        writeFlags(prefs, changed.associateWith { flags })
        onPolicyChanged()

        val whiteOn = changed.filter {
            !it.isDualApp && PolicyFlag.WHITE in flags && PolicyFlag.WHITE !in previous[it].orEmpty()
        }
        val whiteOff = changed.filter {
            !it.isDualApp && PolicyFlag.WHITE !in flags && PolicyFlag.WHITE in previous[it].orEmpty()
        }
        if (whiteOn.isNotEmpty() || whiteOff.isNotEmpty()) {
            syncBatteryPolicy(appContext, whiteOn, whiteOff, flags, onPolicyChanged)
        }
        return Result(
            changed = changed.size,
            unchanged = targets.size - changed.size,
            skipped = skipped,
            pendingBatterySync = whiteOn.size + whiteOff.size,
        )
    }

    /** 在一次事务里把每个应用的七项成员关系写成给定组合，只写真正变化的键。 */
    private fun writeFlags(
        prefs: SharedPreferences,
        changes: Map<InstalledAppUiModel, Set<PolicyFlag>>,
    ) {
        if (changes.isEmpty()) return
        val updates = LinkedHashMap<String, Set<String>>()
        for (flag in PolicyFlag.entries) {
            val original = PrefReader.readUiStringSet(prefs, flag.key)
            val next = LinkedHashSet(original)
            for ((app, target) in changes) {
                val encoded = AppUidUtils.encodeQueryTarget(app.packageName, app.uid)
                if (flag in target) next.add(encoded) else next.remove(encoded)
            }
            if (next != original) updates[flag.key] = next
        }
        if (updates.isEmpty()) return
        PrefReader.putStringSetsAndNotify(prefs, updates, updates.keys.first())
    }

    private fun syncBatteryPolicy(
        context: Context,
        whiteOn: List<InstalledAppUiModel>,
        whiteOff: List<InstalledAppUiModel>,
        appliedFlags: Set<PolicyFlag>,
        onPolicyChanged: () -> Unit,
    ) {
        scope.launch {
            batteryMutex.withLock {
                val failedOn = ArrayList<InstalledAppUiModel>()
                val failedOff = ArrayList<InstalledAppUiModel>()
                for (app in whiteOn) {
                    val ok = runSafely {
                        SystemBatteryOptimizationHelper.setAppIgnoringBatteryOptimization(context, app.packageName)
                    }
                    if (!ok) failedOn.add(app)
                }
                for (app in whiteOff) {
                    val ok = runSafely {
                        SystemBatteryOptimizationHelper.restoreAppBatteryOptimizationToDefault(context, app.packageName)
                    }
                    if (!ok) failedOff.add(app)
                }
                if (failedOn.isEmpty() && failedOff.isEmpty()) {
                    onPolicyChanged()
                    return@withLock
                }
                rollback(context, failedOn, failedOff, appliedFlags)
                onPolicyChanged()
                val message = buildString {
                    if (failedOn.isNotEmpty()) {
                        append("${failedOn.size} 个应用写入系统省电策略失败，已移出免冻白名单")
                    }
                    if (failedOff.isNotEmpty()) {
                        if (isNotEmpty()) append("；")
                        append("${failedOff.size} 个应用恢复系统省电策略失败，已保留免冻白名单")
                    }
                    append("（需要模块在线或 Root 授权）")
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 省电策略同步失败后的回滚，对应配置页的两种处理：
     * - 打开白名单失败：移出白名单（其余策略已在写入时清空，保持清空）；
     * - 关闭白名单失败：恢复为仅免冻白名单，保证组合仍合法。
     * 回滚前确认应用仍处于本次写入的状态，避免覆盖用户在同步期间做的新修改。
     */
    private fun rollback(
        context: Context,
        failedOn: List<InstalledAppUiModel>,
        failedOff: List<InstalledAppUiModel>,
        appliedFlags: Set<PolicyFlag>,
    ) {
        val prefs = PrefReader.openUiPrefs(context)
        val snapshot = PolicyMembershipSnapshot.read(prefs)
        val changes = LinkedHashMap<InstalledAppUiModel, Set<PolicyFlag>>()
        for (app in failedOn) {
            val current = snapshot.flagsOf(app)
            if (current == appliedFlags) changes[app] = current - PolicyFlag.WHITE
        }
        for (app in failedOff) {
            val current = snapshot.flagsOf(app)
            if (current == appliedFlags) changes[app] = setOf(PolicyFlag.WHITE)
        }
        writeFlags(prefs, changes)
    }

    private suspend fun runSafely(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.error("同步系统省电策略失败", e)
        false
    }
}
