package io.github.univers629.millet.core

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.univers629.millet.ipc.PowerKeeperBinderClient

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

    /** 与 [PrefsKeys.PREFS_FILE_NAME] 保持一致，框架按组名存储。 */
    private const val GROUP = "Settings"

    /** 不属于任何 [PrefGroup] 的键，Hook 侧收到它会整表重建并触发变更分组回调。 */
    private const val ALL_GROUPS_KEY = "*"

    private val syncLock = Any()

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
                    }
                    Logger.alwaysInfo(
                        "LibXposed 服务已连接: ${service.frameworkName} ${service.frameworkVersion} " +
                            "(API ${service.apiVersion})"
                    )
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
            if (changed) {
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
        if (ctx != null) {
            syncDiffInternal(ctx, targetKeys = hookKeys)
        }
        notifySystemBinder(notifyKey)
    }

    /** 供 [PrefReader] 在批量导入 / 重置配置后调用：先增量对齐远程配置，再发一次整表通知。 */
    internal fun syncAndNotifyAll() {
        val ctx = appContext
        if (ctx != null) {
            syncDiffInternal(ctx, targetKeys = null)
        }
        notifySystemBinder(ALL_GROUPS_KEY)
    }

    /** 把本应用 `Settings` 里的 Hook 键按增量差异同步到框架。 */
    fun push(context: Context, forceNotify: Boolean = true) {
        val changed = syncDiffInternal(context, targetKeys = null)
        if (changed || forceNotify) {
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
            PowerKeeperBinderClient.notifyPrefChanged(notifyKey)
        }.onFailure { Logger.warn("通知 Hook 重读配置失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun syncDiffInternal(context: Context, targetKeys: Set<String>?): Boolean {
        synchronized(syncLock) {
            val service = boundService ?: return false
            return runCatching {
                val localAll = PrefReader.openUiPrefs(context).all
                val remote = service.getRemotePreferences(GROUP)

                if (lastSyncedService !== service || targetKeys == null) {
                    syncFullDiffLocked(service, localAll, remote)
                } else {
                    syncIncrementalKeysLocked(localAll, remote, targetKeys)
                }
            }.getOrElse { t ->
                Logger.warn("镜像设置到 LibXposed 失败: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    private fun syncFullDiffLocked(
        service: XposedService,
        localAll: Map<String, Any?>,
        remote: SharedPreferences
    ): Boolean {
        val remoteAll = if (lastSyncedService === service) {
            lastSyncedValues
        } else {
            runCatching { HashMap(remote.all) }.getOrElse { emptyMap() }
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
            editor.apply()
            Logger.alwaysInfo("已增量同步 $mutations 项设置到 LibXposed 远程配置 (共 ${nextSynced.size} 项)")
        }

        lastSyncedValues.clear()
        lastSyncedValues.putAll(nextSynced)
        lastSyncedService = service
        return mutations > 0
    }

    private fun syncIncrementalKeysLocked(
        localAll: Map<String, Any?>,
        remote: SharedPreferences,
        targetKeys: Set<String>
    ): Boolean {
        val editor = remote.edit()
        var mutations = 0
        for (key in targetKeys) {
            if (key !in PrefsKeys.ALL_KEYS) continue
            if (!localAll.containsKey(key)) {
                if (lastSyncedValues.containsKey(key)) {
                    editor.remove(key)
                    lastSyncedValues.remove(key)
                    mutations++
                }
                continue
            }
            val desired = normalizeValue(localAll[key]) ?: continue
            if (!lastSyncedValues.containsKey(key) || lastSyncedValues[key] != desired) {
                writeNormalizedValue(editor, key, desired)
                lastSyncedValues[key] = desired
                mutations++
            }
        }
        if (mutations > 0) {
            editor.apply()
        }
        return mutations > 0
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
