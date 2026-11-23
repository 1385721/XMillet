package io.github.univers629.millet.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把本应用的 Hook 设置增量镜像到 LibXposed 框架的远程配置（RemotePreferences）。
 *
 * 背景：Hook 跑在 system_server / powerkeeper 进程，直接读本应用的 SharedPreferences 会被
 * SELinux 的 app_data_file 规则挡掉。
 *
 * 设计要点：
 * 1. 只同步 [PrefsKeys.ALL_KEYS] 里的 Hook 配置键，忽略主题 / OOBE 等纯 UI 键；
 * 2. 以键值对比做增量更新与清理，不在每次点击时调用 `remote.edit().clear()` 全表擦除；
 * 3. 与 [PrefReader] 共享同一同步状态机：单键写盘后先同步远程配置再发一次 IPC 通知，
 *    `OnSharedPreferenceChangeListener` 仅兜底未经过 [PrefReader] 的直接写盘，避免重复推送。
 */
object XposedPrefsBridge {

    enum class HookTargetState {
        UP_TO_DATE,
        STALE,
        RELOADING,
        FAILED
    }

    data class HookTargetSnapshot(
        val processName: String,
        val uid: Int,
        val pid: Int,
        val state: HookTargetState,
        val loadedVersionCode: Long
    )

    data class XposedFrameworkState(
        val isServiceConnected: Boolean = false,
        val apiVersion: Int = 0,
        val frameworkName: String = "",
        val frameworkVersion: String = "",
        val frameworkVersionCode: Long = 0L,
        val supportsSystemHook: Boolean = false,
        val supportsRemotePrefs: Boolean = false,
        val apiProtectionEnabled: Boolean = false,
        val scope: List<String> = emptyList(),
        val runningTargets: List<HookTargetSnapshot> = emptyList()
    ) {
        val hasSystemScope: Boolean
            get() = "system" in scope || "android" in scope

        val hasPowerKeeperScope: Boolean
            get() = "com.miui.powerkeeper" in scope

        val missingScopes: List<String>
            get() = buildList {
                if (!hasSystemScope) add("system")
                if (!hasPowerKeeperScope) add("com.miui.powerkeeper")
            }

        val staleTargets: List<HookTargetSnapshot>
            get() = runningTargets.filter { it.state == HookTargetState.STALE }
    }

    /** 与 [PrefsKeys.PREFS_FILE_NAME] 保持一致，框架按组名存储。 */
    private const val GROUP = "Settings"

    /** 不属于任何 [PrefGroup] 的键，Hook 侧收到它会整表重建并触发变更分组回调。 */
    private const val ALL_GROUPS_KEY = "*"

    private val syncLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _frameworkState = MutableStateFlow(XposedFrameworkState())
    val frameworkState: StateFlow<XposedFrameworkState> = _frameworkState.asStateFlow()

    @Volatile
    private var remoteUnsupportedLogged = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var boundService: XposedService? = null

    @Volatile
    private var lastSyncedService: XposedService? = null

    private val lastSyncedValues = HashMap<String, Any?>()

    /** 必须保留强引用，否则 SharedPreferences 的弱引用监听器会被回收。 */
    private var localListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * 抑制当前线程在 `PrefReader.*AndNotify` 的 `apply()` 期间同步触发的本地监听器，
     * 避免单次事务（尤其是批量修改多组集合时）在 `syncAndNotify*` 之前重复推表与发 IPC。
     */
    private val suppressLocalListenerDepth = ThreadLocal.withInitial { 0 }

    internal fun withLocalListenerSuppressed(block: () -> Unit) {
        val prev = suppressLocalListenerDepth.get() ?: 0
        suppressLocalListenerDepth.set(prev + 1)
        try {
            block()
        } finally {
            if (prev <= 0) {
                suppressLocalListenerDepth.remove()
            } else {
                suppressLocalListenerDepth.set(prev)
            }
        }
    }

    fun install(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        registerLocalListener(ctx)

        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    synchronized(syncLock) {
                        boundService = service
                        lastSyncedService = null
                        lastSyncedValues.clear()
                        remoteUnsupportedLogged = false
                    }
                    Logger.alwaysInfo(
                        "LibXposed 服务已连接: ${service.frameworkName} ${service.frameworkVersion} " +
                            "(API ${service.apiVersion})"
                    )
                    refreshFrameworkState()
                    push(ctx, forceNotify = true)
                }

                override fun onServiceDied(service: XposedService) {
                    synchronized(syncLock) {
                        if (boundService === service) {
                            boundService = null
                            if (lastSyncedService === service) {
                                lastSyncedService = null
                                lastSyncedValues.clear()
                            }
                            Logger.warn("LibXposed 服务已断开")
                        }
                    }
                    refreshFrameworkState()
                }
            })
        }.onFailure { Logger.error("注册 LibXposed 服务监听失败", it) }

        // 系统服务 Binder 通常在推送之后才就绪，连上时补一次全表检查并通知 Hook 重载
        PowerKeeperBinderClient.onSystemBinderReady = {
            push(ctx, forceNotify = true)
        }

        // 服务可能在 install 之前就递送过 binder（框架先于界面连进来），这里补推一次
        push(ctx, forceNotify = false)
    }

    private fun registerLocalListener(ctx: Context) {
        if (localListener != null) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if ((suppressLocalListenerDepth.get() ?: 0) > 0) {
                return@OnSharedPreferenceChangeListener
            }
            if (key != null && key !in PrefsKeys.ALL_KEYS) {
                return@OnSharedPreferenceChangeListener
            }
            val changed = if (key == null) {
                syncDiffInternal(ctx, targetKeys = null)
            } else {
                syncDiffInternal(ctx, targetKeys = setOf(key))
            }
            if (changed == SyncResult.CHANGED) {
                notifySystemBinder(key ?: ALL_GROUPS_KEY)
            }
        }
        localListener = listener
        runCatching {
            PrefReader.openUiPrefs(ctx).registerOnSharedPreferenceChangeListener(listener)
        }.onFailure { Logger.error("监听本地设置变化失败", it) }
    }

    /** 供 [PrefReader] 在修改指定 Hook 键后调用：先同步远程配置，再发送单次热重载通知。 */
    internal fun syncAndNotifyKeys(keys: Set<String>, notifyKey: String) {
        val hookKeys = keys.filterTo(LinkedHashSet()) { it in PrefsKeys.ALL_KEYS }
        if (hookKeys.isEmpty()) return
        val ctx = appContext
        if (ctx != null && syncDiffInternal(ctx, targetKeys = hookKeys) != SyncResult.FAILED) {
            notifySystemBinder(notifyKey)
        }
    }

    /** 供 [PrefReader] 在批量导入 / 重置配置后调用：先增量对齐远程配置，再发一次整表通知。 */
    internal fun syncAndNotifyAll() {
        val ctx = appContext
        if (ctx != null && syncDiffInternal(ctx, targetKeys = null) != SyncResult.FAILED) {
            notifySystemBinder(ALL_GROUPS_KEY)
        }
    }

    /** 把本应用 `Settings` 里的 Hook 键按增量差异同步到框架。 */
    fun push(context: Context, forceNotify: Boolean = true) {
        val changed = syncDiffInternal(context, targetKeys = null)
        if (changed != SyncResult.FAILED && (changed == SyncResult.CHANGED || forceNotify)) {
            notifySystemBinder(ALL_GROUPS_KEY)
        }
    }

    private fun notifySystemBinder(notifyKey: String) {
        if (!PowerKeeperBinderClient.isSystemServiceReady) {
            // 常见于冷启动：推送先于 Binder 绑定完成，等 onSystemBinderReady 再补一次
            Logger.warn("系统服务 Binder 未就绪，配置将在连接后补推")
            return
        }
        runCatching {
            if (!PowerKeeperBinderClient.notifyPrefChanged(notifyKey)) {
                Logger.warn("配置通知未被系统服务接收")
            }
        }.onFailure { Logger.warn("通知 Hook 重读配置失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun refreshFrameworkState(): XposedFrameworkState {
        val service = boundService ?: return XposedFrameworkState().also {
            _frameworkState.value = it
        }
        val state = runCatching {
            val apiVersion = service.apiVersion
            val props = service.frameworkProperties
            val scope = runCatching { service.scope }.getOrDefault(emptyList())
            val targets = if (apiVersion >= XposedService.API_102) {
                runCatching {
                    service.runningTargets.map { target ->
                        HookTargetSnapshot(
                            processName = target.processName,
                            uid = target.uid,
                            pid = target.pid,
                            state = when (target.state) {
                                HookedTarget.State.UP_TO_DATE -> HookTargetState.UP_TO_DATE
                                HookedTarget.State.STALE -> HookTargetState.STALE
                                HookedTarget.State.RELOADING -> HookTargetState.RELOADING
                                HookedTarget.State.FAILED -> HookTargetState.FAILED
                            },
                            loadedVersionCode = target.loadedVersionCode
                        )
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            XposedFrameworkState(
                isServiceConnected = true,
                apiVersion = apiVersion,
                frameworkName = service.frameworkName,
                frameworkVersion = service.frameworkVersion,
                frameworkVersionCode = service.frameworkVersionCode,
                supportsSystemHook = (props and XposedService.PROP_CAP_SYSTEM) != 0L,
                supportsRemotePrefs = (props and XposedService.PROP_CAP_REMOTE) != 0L,
                apiProtectionEnabled = (props and XposedService.PROP_RT_API_PROTECTION) != 0L,
                scope = scope,
                runningTargets = targets
            )
        }.getOrElse { XposedFrameworkState() }
        _frameworkState.value = state
        return state
    }

    /**
     * 通过 `XposedService.requestScope` 主动向框架申请缺失的核心作用域（`system` 与 `com.miui.powerkeeper`）。
     */
    fun requestMissingScopes(onResult: (approved: Boolean, message: String) -> Unit) {
        val service = boundService
        if (service == null) {
            mainHandler.post { onResult(false, "Xposed 服务未连接") }
            return
        }
        val missing = refreshFrameworkState().missingScopes
        if (missing.isEmpty()) {
            mainHandler.post { onResult(true, "核心作用域已授权") }
            return
        }
        runCatching {
            service.requestScope(missing, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    refreshFrameworkState()
                    mainHandler.post {
                        onResult(true, "已授权作用域: ${approved.joinToString(", ")}")
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    refreshFrameworkState()
                    mainHandler.post {
                        onResult(false, message.ifBlank { "作用域授权被拒绝" })
                    }
                }
            })
        }.onFailure { t ->
            mainHandler.post { onResult(false, t.message ?: t.javaClass.simpleName) }
        }
    }

    /**
     * 针对 API 102 的 `STALE` 过期目标进程批量发起模块热重载（无需重启系统）。
     */
    fun hotReloadStaleTargets(onResult: (succeeded: Int, total: Int, summary: String) -> Unit) {
        val service = boundService
        if (service == null) {
            mainHandler.post { onResult(0, 0, "Xposed 服务未连接") }
            return
        }
        if (runCatching { service.apiVersion }.getOrDefault(0) < XposedService.API_102) {
            mainHandler.post { onResult(0, 0, "当前框架不支持 API 102 热重载") }
            return
        }
        val staleTargets = runCatching {
            service.runningTargets.filter { it.state == HookedTarget.State.STALE }
        }.getOrDefault(emptyList())
        if (staleTargets.isEmpty()) {
            refreshFrameworkState()
            mainHandler.post { onResult(0, 0, "所有目标进程已是最新模块代码") }
            return
        }

        val total = staleTargets.size
        val remaining = AtomicInteger(total)
        val succeeded = AtomicInteger(0)
        val failures = ArrayList<String>()

        for (target in staleTargets) {
            runCatching {
                service.hotReloadModule(target, null) { proc, result ->
                    when (result.status) {
                        HotReloadResult.Status.SUCCEEDED -> succeeded.incrementAndGet()
                        else -> synchronized(failures) {
                            val detail = result.message?.let { ": $it" }.orEmpty()
                            failures.add("${proc.processName}(${result.status}$detail)")
                        }
                    }
                    if (remaining.decrementAndGet() == 0) {
                        refreshFrameworkState()
                        val ok = succeeded.get()
                        val summary = if (failures.isEmpty()) {
                            "已热重载 $ok/$total 个目标进程"
                        } else {
                            "热重载完成 $ok/$total，未完成: ${failures.joinToString("; ")}"
                        }
                        mainHandler.post { onResult(ok, total, summary) }
                    }
                }
            }.onFailure { t ->
                synchronized(failures) {
                    failures.add("${target.processName}(${t.javaClass.simpleName})")
                }
                if (remaining.decrementAndGet() == 0) {
                    refreshFrameworkState()
                    val ok = succeeded.get()
                    mainHandler.post {
                        onResult(ok, total, "热重载完成 $ok/$total，未完成: ${failures.joinToString("; ")}")
                    }
                }
            }
        }
    }

    private enum class SyncResult { FAILED, UNCHANGED, CHANGED }

    private fun syncDiffInternal(context: Context, targetKeys: Set<String>?): SyncResult {
        synchronized(syncLock) {
            val service = boundService ?: return SyncResult.FAILED
            return runCatching {
                if ((service.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L) {
                    if (!remoteUnsupportedLogged) {
                        remoteUnsupportedLogged = true
                        Logger.alwaysWarn("当前 Xposed 框架未启用 PROP_CAP_REMOTE 远程配置能力，设置同步已跳过")
                    }
                    return SyncResult.FAILED
                }
                val localAll = PrefReader.openUiPrefs(context).all
                val remote = service.getRemotePreferences(GROUP)

                if (lastSyncedService !== service || targetKeys == null) {
                    syncFullDiffLocked(service, localAll, remote)
                } else {
                    syncIncrementalKeysLocked(localAll, remote, targetKeys)
                }
            }.getOrElse { t ->
                Logger.warn("镜像设置到 LibXposed 失败: ${t.javaClass.simpleName}: ${t.message}")
                SyncResult.FAILED
            }
        }
    }

    private fun syncFullDiffLocked(
        service: XposedService,
        localAll: Map<String, Any?>,
        remote: SharedPreferences
    ): SyncResult {
        val remoteAll = if (lastSyncedService === service) {
            lastSyncedValues
        } else {
            HashMap(remote.all)
        }

        val editor = remote.edit()
        var mutations = 0

        for (staleKey in remoteAll.keys) {
            if (staleKey !in PrefsKeys.ALL_KEYS || !localAll.containsKey(staleKey)) {
                editor.remove(staleKey)
                mutations++
            }
        }

        val nextSynced = HashMap<String, Any?>(PrefsKeys.ALL_KEYS.size)
        for (key in PrefsKeys.ALL_KEYS) {
            if (!localAll.containsKey(key)) {
                if (remoteAll.containsKey(key)) {
                    editor.remove(key)
                    mutations++
                }
                continue
            }
            val desired = normalizeValue(localAll[key]) ?: continue
            nextSynced[key] = desired
            val existing = normalizeValue(remoteAll[key])
            if (!remoteAll.containsKey(key) || existing != desired) {
                writeNormalizedValue(editor, key, desired)
                mutations++
            }
        }

        if (mutations > 0) {
            if (!editor.commit()) return SyncResult.FAILED
            Logger.alwaysInfo("已增量同步 $mutations 项设置到 LibXposed 远程配置 (共 ${nextSynced.size} 项)")
        }

        lastSyncedValues.clear()
        lastSyncedValues.putAll(nextSynced)
        lastSyncedService = service
        return if (mutations > 0) SyncResult.CHANGED else SyncResult.UNCHANGED
    }

    private fun syncIncrementalKeysLocked(
        localAll: Map<String, Any?>,
        remote: SharedPreferences,
        targetKeys: Set<String>
    ): SyncResult {
        val editor = remote.edit()
        val nextSynced = HashMap(lastSyncedValues)
        var mutations = 0
        for (key in targetKeys) {
            if (key !in PrefsKeys.ALL_KEYS) continue
            if (!localAll.containsKey(key)) {
                if (lastSyncedValues.containsKey(key)) {
                    editor.remove(key)
                    nextSynced.remove(key)
                    mutations++
                }
                continue
            }
            val desired = normalizeValue(localAll[key]) ?: continue
            if (!lastSyncedValues.containsKey(key) || lastSyncedValues[key] != desired) {
                writeNormalizedValue(editor, key, desired)
                nextSynced[key] = desired
                mutations++
            }
        }
        if (mutations > 0) {
            if (!editor.commit()) return SyncResult.FAILED
        }
        lastSyncedValues.clear()
        lastSyncedValues.putAll(nextSynced)
        return if (mutations > 0) SyncResult.CHANGED else SyncResult.UNCHANGED
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        is Boolean, is Int, is Long, is Float, is String -> value
        is Set<*> -> value.asSequence()
            .filterIsInstance<String>()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 必须包一层 java.util.LinkedHashSet：Kotlin EmptySet 反序列化到框架侧会抛 ClassNotFoundException
            .toCollection(java.util.LinkedHashSet())
        else -> null
    }

    private fun writeNormalizedValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(
                key,
                java.util.LinkedHashSet(value as Collection<String>)
            )
        }
    }
}
