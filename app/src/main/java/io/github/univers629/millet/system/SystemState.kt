package io.github.univers629.millet.system

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.VersionGate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock

/**
 * system_server 作用域全局状态、服务句柄池与进程快照缓存。
 *
 * 修正缺陷 #1：
 * 原模块用单一静态槽 `systemContext` 同时承载 `GreezeManagerService` 与 `AurogonImmobulusMode`：
 * - `isUidFrozen(int)` 仅存在于 `GreezeManagerService`
 * - `checkAppFgService(int)` 仅存在于 `AurogonImmobulusMode`
 * 本实现严格拆分为 `greezeService`、`aurogonService`、`amsInstance`、`deviceIdleController` 四个独立句柄，
 * 各自缓存、各自判空、互不覆盖。
 */
object SystemState {

    /** 全局初始化总闸门：GreezeManagerService 构造完成后才置为 true，此前所有干预逻辑不生效 */
    @Volatile
    var isGreezeReady: Boolean = false

    /** 模块 APK 最近一次更新时间戳（用于检测覆盖更新） */
    @Volatile
    var moduleLastUpdateTime: Long = -1L

    /** S2 捕获的 com.miui.server.greeze.GreezeManagerService 实例（供 isUidFrozen / getFrozenUids 调用） */
    @Volatile
    var greezeService: Any? = null

    /** S3 捕获的 com.miui.server.greeze.AurogonImmobulusMode 实例（供 checkAppFgService / mMessageApp 调用） */
    @Volatile
    var aurogonService: Any? = null

    /** S11 捕获的 com.android.server.am.ActivityManagerService 实例（供获取系统 Context） */
    @Volatile
    var amsInstance: Any? = null

    /** S12 捕获的 com.android.server.DeviceIdleController 实例（供 Doze 状态机与省电白名单调用） */
    @Volatile
    var deviceIdleController: Any? = null

    /** S20 记录最近一次因 toFront 解冻的应用包名（用于配合 screen on 例外放行） */
    @Volatile
    var lastToFrontPackage: String = ""

    /** S2 构造器直接传入的系统 Context 备份 */
    @Volatile
    var greezeContext: Context? = null

    /**
     * 多源兜底获取 system_server Context（对应原模块 AbstractC0207.getSystemContext）：
     * 1. S11 捕获的 ActivityManagerService.mContext
     * 2. S2 捕获的 GreezeManagerService 构造参数 Context
     * 3. ActivityThread.currentActivityThread().mSystemContext / ActivityManagerService.self().mContext
     */
    val systemContext: Context?
        get() {
            val ams = amsInstance
            if (ams != null) {
                try {
                    val ctx = XReflect.getObjectField(ams, "mContext") as? Context
                    if (ctx != null) return ctx
                } catch (_: Throwable) {
                }
            }
            greezeContext?.let { return it }
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAt = XReflect.callStaticMethod(atClass, "currentActivityThread")
                if (currentAt != null) {
                    val sysCtx = XReflect.getObjectField(currentAt, "mSystemContext") as? Context
                    if (sysCtx != null) return sysCtx
                }
            } catch (_: Throwable) {
            }
            return try {
                val amsClass = Class.forName("com.android.server.am.ActivityManagerService")
                val selfAms = XReflect.callStaticMethod(amsClass, "self")
                if (selfAms != null) {
                    amsInstance = selfAms
                    XReflect.getObjectField(selfAms, "mContext") as? Context
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

    // ==================== 1. GreezeManagerService 反射方法封装 ====================

    /**
     * 查询指定 uid 当前是否处于冻结态。
     * 真机声明：`com.miui.server.greeze.GreezeManagerService#isUidFrozen(int) : boolean`
     */
    fun isUidFrozen(uid: Int): Boolean {
        val service = greezeService ?: return false
        return try {
            (XReflect.callMethod(service, "isUidFrozen", uid) as? Boolean) ?: false
        } catch (t: Throwable) {
            Logger.error("call isUidFrozen($uid) failed", t)
            false
        }
    }

    /**
     * 获取当前所有已冻结的 uid 原始整型数组。
     */
    fun getFrozenUidsArray(): IntArray {
        val service = greezeService ?: return IntArray(0)
        return try {
            val raw = XReflect.callMethod(service, "getFrozenUids", 9999)
            when (raw) {
                is IntArray -> raw
                is Collection<*> -> raw.mapNotNull { it as? Int }.toIntArray()
                else -> IntArray(0)
            }
        } catch (_: Throwable) {
            IntArray(0)
        }
    }

    /**
     * 一键解冻当前所有已冻结的 UID（供 XMillet 主页快捷操作调用）。
     * @return 触发解冻的 UID 数量
     */
    fun thawAllFrozenUids(): Int {
        val service = greezeService ?: return 0
        val uids = getFrozenUidsArray()
        if (uids.isEmpty()) return 0
        var count = 0
        for (uid in uids) {
            try {
                XReflect.callMethod(service, "thawUid", uid, 1000, "toFrontActivity")
                count++
            } catch (_: Throwable) {
            }
        }
        return count
    }

    /**
     * 获取当前所有已冻结的 uid 列表，并格式化为 `应用名@uid` 集合。
     * 真机声明：`com.miui.server.greeze.GreezeManagerService#getFrozenUids(int) : int[]`
     */
    fun getFrozenUidLabels(): Set<String> {
        val uids = getFrozenUidsArray()
        if (uids.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (uid in uids) {
            result.add(formatAppLabelAndUid(uid))
        }
        return result
    }

    /**
     * 获取当前所有已冻结的 uid 对应的应用名称集合（用于墓碑日志）。
     */
    fun getFrozenAppNames(): Set<String> {
        val uids = getFrozenUidsArray()
        if (uids.isEmpty()) return emptySet()
        val result = LinkedHashSet<String>()
        for (uid in uids) {
            result.add(formatAppLabelOnly(uid))
        }
        return result
    }

    // ==================== 2. AurogonImmobulusMode 反射方法封装 ====================

    /**
     * 查询指定 uid 是否拥有活跃的前台服务（FGS）。
     * 真机声明：`com.miui.server.greeze.AurogonImmobulusMode#checkAppFgService(int) : boolean`
     * 该方法仅存在于 AurogonImmobulusMode，不可在 GreezeManagerService 上调用。
     */
    fun checkAppFgService(uid: Int): Boolean {
        val aurogon = aurogonService
        if (aurogon == null) {
            Logger.error("判断fgs: aurogonService instance is null")
            return false
        }
        return try {
            (XReflect.callMethod(aurogon, "checkAppFgService", uid) as? Boolean) ?: false
        } catch (t: Throwable) {
            Logger.error("call checkAppFgService($uid) failed", t)
            false
        }
    }

    /**
     * 将模块配置的 `messageAppSet` 同步到 `AurogonImmobulusMode.mMessageApp` 静态列表，
     * 并在 SDK > 33 时更新 `mBroadcastTargetWhiteList`。
     */
    @Suppress("UNCHECKED_CAST")
    fun syncMessageAppsToAurogon() {
        val aurogon = aurogonService ?: return
        val messageApps = PrefReader.systemPrefs.messageAppSet
        try {
            val list = XReflect.getStaticObjectField(aurogon.javaClass, "mMessageApp") as? MutableList<String>
            if (list != null) {
                list.clear()
                list.addAll(messageApps)
            }
        } catch (_: Throwable) {
            // 部分 MIUI 版本字段为实例成员
            try {
                val instanceList = XReflect.getObjectField(aurogon, "mMessageApp") as? MutableList<String>
                if (instanceList != null) {
                    instanceList.clear()
                    instanceList.addAll(messageApps)
                }
            } catch (t: Throwable) {
                Logger.error("syncMessageAppsToAurogon mMessageApp failed", t)
            }
        }

        if (VersionGate.isSdkAfter33) {
            try {
                val targetMap = XReflect.getObjectField(
                    aurogon,
                    "mBroadcastTargetWhiteList"
                ) as? MutableMap<String, MutableList<String>>
                if (targetMap != null) {
                    val allowedActions = arrayListOf(
                        "android.net.wifi.STATE_CHANGE",
                        "android.net.conn.CONNECTIVITY_CHANGE"
                    )
                    for (pkg in messageApps) {
                        targetMap[pkg] = ArrayList(allowedActions)
                    }
                    targetMap.keys.retainAll(messageApps)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ==================== 3. 包名 / UID / /proc 工具方法 ====================

    /**
     * 根据 UID 反查主包名（失败时返回 null）。
     */
    fun getPackageNameForUid(uid: Int): String? {
        if (uid <= 0) return null
        val ctx = systemContext ?: return null
        return try {
            val pkgs = ctx.packageManager.getPackagesForUid(uid)
            pkgs?.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 根据包名查询 UID（失败时返回 -1）。
     */
    fun getPackageUid(packageName: String): Int {
        val ctx = systemContext ?: return -1
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * 判断指定 UID 是否为系统应用（FLAG_SYSTEM，对应原模块误命名的 isUidForeground）。
     */
    fun isSystemAppUid(uid: Int): Boolean {
        if (uid < 10000) return true
        val ctx = systemContext ?: return false
        return try {
            val pkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return false
            @Suppress("DEPRECATION")
            val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 格式化纯应用名（uid == 99000 特判为 Android System WebView，对应原模块 AbstractC0207.getAppLabel）。
     */
    fun formatAppLabelOnly(uid: Int): String {
        if (uid == 99000) return "Android System WebView"
        val ctx = systemContext ?: return "uid:$uid"
        return try {
            val pm = ctx.packageManager
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return "uid:$uid"
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Throwable) {
            "uid:$uid"
        }
    }

    /**
     * 格式化 `应用名@uid`（用于 S6 freezeUids 日志）。
     */
    fun formatAppLabelAndUid(uid: Int): String {
        val ctx = systemContext ?: return "uid@$uid"
        return try {
            val pm = ctx.packageManager
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return "uid@$uid"
            @Suppress("DEPRECATION")
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            "$label@$uid"
        } catch (_: Throwable) {
            "uid@$uid"
        }
    }

    /**
     * 从 `/proc/<pid>/status` 反查进程真实 UID（专用于 S18 thawProcess，所有异常判空兜底返回 -1）。
     */
    fun readUidFromProcStatus(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val statusFile = File("/proc/$pid/status")
            if (!statusFile.exists() || !statusFile.canRead()) return -1
            statusFile.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("Uid:")) {
                        val parts = line.substringAfter("Uid:").trim().split(Regex("\\s+"))
                        val uid = parts.firstOrNull()?.toIntOrNull()
                        if (uid != null) return uid
                    }
                }
            }
            -1
        } catch (_: Throwable) {
            -1
        }
    }

    // ==================== 4. ProcessRecord 缓存表（供 S4/S5/S9/S10/S15/S19 使用） ====================

    data class CachedProcessState(
        val processRecord: Any,
        val processName: String,
        val packageName: String,
        val uid: Int,
        val runningUid: Int,
        val userId: Int,
        val isSystemApp: Boolean,
        val isIsolated: Boolean,
        val lock: ReentrantLock = ReentrantLock()
    )

    /** processName -> (uid -> CachedProcessState) */
    private val processCacheByName = ConcurrentHashMap<String, ConcurrentHashMap<Int, CachedProcessState>>()

    /**
     * 从 AOSP ProcessRecord 反射提取进程快照（失败返回 null）。
     */
    fun extractProcessStateOrNull(processRecord: Any?): CachedProcessState? {
        if (processRecord == null) return null
        return try {
            val appInfo = XReflect.getObjectField(processRecord, "info") as? ApplicationInfo
                ?: return null
            val pkgName = appInfo.packageName ?: return null
            val appUid = appInfo.uid
            val runningUid = try {
                XReflect.getIntField(processRecord, "uid")
            } catch (_: Throwable) {
                appUid
            }
            val userId = try {
                XReflect.getIntField(processRecord, "userId")
            } catch (_: Throwable) {
                0
            }
            val procName = (XReflect.getObjectField(processRecord, "processName") as? String)
                ?: pkgName
            val isSys = pkgName == "android" ||
                appUid < 10000 ||
                (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

            CachedProcessState(
                processRecord = processRecord,
                processName = procName,
                packageName = pkgName,
                uid = appUid,
                runningUid = runningUid,
                userId = userId,
                isSystemApp = isSys,
                isIsolated = runningUid != appUid
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * S4：缓存新增进程。
     */
    fun cacheProcessRecord(state: CachedProcessState) {
        val byUid = processCacheByName.getOrPut(state.processName) { ConcurrentHashMap() }
        byUid[state.uid] = state
    }

    /**
     * S15：移除已退出的进程缓存。
     */
    fun removeCachedProcessRecord(processName: String?, uid: Int): CachedProcessState? {
        if (processName.isNullOrEmpty()) return null
        val byUid = processCacheByName[processName] ?: return null
        val removed = byUid.remove(uid)
        if (byUid.isEmpty()) {
            processCacheByName.remove(processName)
        }
        return removed
    }

    /**
     * S5 / S19：根据 ProcessRecord 查找已缓存的进程状态。
     */
    fun findCachedProcess(processRecord: Any?): CachedProcessState? {
        if (processRecord == null) return null
        return try {
            val procName = XReflect.getObjectField(processRecord, "processName") as? String
                ?: return null
            val appInfo = XReflect.getObjectField(processRecord, "info") as? ApplicationInfo
                ?: return null
            processCacheByName[procName]?.get(appInfo.uid)
        } catch (_: Throwable) {
            null
        }
    }

    // ==================== 5. PID 冻结状态记账表（供 S16/S17/S18 simplifyLog 使用） ====================

    data class UidPidFreezeRecord(
        @Volatile var lastReason: String = "",
        @Volatile var allFrozen: Boolean = false,
        val pidFrozenMap: ConcurrentHashMap<Int, Boolean> = ConcurrentHashMap()
    )

    private val pidFreezeLock = ReentrantLock()
    private val uidPidFreezeTable = ConcurrentHashMap<Int, UidPidFreezeRecord>()
    private val recentTombstoneReasons = CopyOnWriteArrayList<String>()

    fun isPidTracked(uid: Int, pid: Int): Boolean {
        pidFreezeLock.lock()
        try {
            return uidPidFreezeTable[uid]?.pidFrozenMap?.containsKey(pid) == true
        } finally {
            pidFreezeLock.unlock()
        }
    }

    fun recordPidFreezeState(uid: Int, pid: Int, frozen: Boolean, reason: String): UidPidFreezeRecord {
        var emitThawTombstone = false
        var emitFreezeTombstone = false
        var effectiveReason = reason
        val record: UidPidFreezeRecord

        pidFreezeLock.lock()
        try {
            record = uidPidFreezeTable.getOrPut(uid) { UidPidFreezeRecord() }
            record.lastReason = reason
            record.pidFrozenMap[pid] = frozen
            val nowAllFrozen = record.pidFrozenMap.isNotEmpty() && record.pidFrozenMap.values.all { it }
            val wasAllFrozen = record.allFrozen

            if (wasAllFrozen && !nowAllFrozen) {
                record.allFrozen = false
                if (isGreezeReady && PrefReader.systemPrefs.simplifyLog) {
                    emitThawTombstone = true
                    effectiveReason = record.lastReason
                }
            } else if (!wasAllFrozen && nowAllFrozen) {
                record.allFrozen = true
                if (isGreezeReady && PrefReader.systemPrefs.simplifyLog) {
                    if (recentTombstoneReasons.size >= 2) {
                        recentTombstoneReasons.removeAt(0)
                    }
                    recentTombstoneReasons.add(record.lastReason)
                    emitFreezeTombstone = true
                    effectiveReason = record.lastReason
                }
            }
        } finally {
            pidFreezeLock.unlock()
        }

        if (emitThawTombstone) {
            Logger.alwaysWarn(
                "墓碑日志(T):由于[$effectiveReason],解冻应用[${formatAppLabelOnly(uid)}],冻结列表:${getFrozenAppNames()}"
            )
        } else if (emitFreezeTombstone) {
            Logger.alwaysWarn(
                "墓碑日志(F):由于[$effectiveReason],冻结应用[${formatAppLabelOnly(uid)}],冻结列表:${getFrozenAppNames()}"
            )
        }
        return record
    }

    fun removePidFromFreezeTable(uid: Int, pid: Int) {
        pidFreezeLock.lock()
        try {
            val record = uidPidFreezeTable[uid] ?: return
            record.pidFrozenMap.remove(pid)
            if (record.pidFrozenMap.isEmpty()) {
                uidPidFreezeTable.remove(uid)
            }
        } finally {
            pidFreezeLock.unlock()
        }
    }

    fun getUidFreezeSummary(uid: Int): Triple<Int, Int, Map<Int, Boolean>> {
        pidFreezeLock.lock()
        try {
            val record = uidPidFreezeTable[uid] ?: return Triple(0, 0, emptyMap())
            val snapshot = LinkedHashMap(record.pidFrozenMap)
            val frozenCount = snapshot.values.count { it }
            return Triple(frozenCount, snapshot.size, snapshot)
        } finally {
            pidFreezeLock.unlock()
        }
    }

    /**
     * 热重载后重新拿回框架服务句柄。
     *
     * 热重载会让新一代代码的静态缓存全部为空，而这些句柄原本只在开机时的构造器
     * Hook（S2 / S3 / S11 / S12）里被捕获。不重新获取的话，新一代的所有闸门都会因为
     * `isGreezeReady == false` 直接放行 —— Hook 已安装但不生效。
     *
     * - `greeze` / `deviceidle` 都在 ServiceManager 里注册过（服务名 `greezer` /
     *   `deviceidle`），系统进程内 `getService(name)` 拿到的就是服务实现本身；
     * - `AurogonImmobulusMode` 不是独立 binder，从 greeze 实例的字段/无参方法里找。
     *
     * @return true 表示至少拿回了 greeze 句柄
     */
    fun reacquireFrameworkServices(): Boolean {
        if (greezeService == null) {
            greezeService = resolveSystemService("greezer")
        }
        val greeze = greezeService ?: run {
            isGreezeReady = false
            return false
        }
        isGreezeReady = true
        if (aurogonService == null) {
            aurogonService = findInstanceOfType(greeze, CLASS_AUROGON_MODE)
        }
        if (deviceIdleController == null) {
            deviceIdleController = resolveSystemService("deviceidle")
                ?: resolveSystemService("device_idle")
        }
        return true
    }

    private const val CLASS_AUROGON_MODE = "com.miui.server.greeze.AurogonImmobulusMode"

    private fun resolveSystemService(name: String): Any? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name)
    } catch (_: Throwable) {
        null
    }

    /** 在 [owner] 的字段或零参方法里找类型为 [typeName] 的实例。 */
    private fun findInstanceOfType(owner: Any, typeName: String): Any? {
        val type = runCatching {
            Class.forName(typeName, false, owner.javaClass.classLoader)
        }.getOrNull() ?: return null
        for (field in owner.javaClass.declaredFields) {
            if (!type.isAssignableFrom(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(owner)
            }.getOrNull()
            if (value != null) return value
        }
        for (method in owner.javaClass.methods) {
            if (method.parameterCount != 0 || !type.isAssignableFrom(method.returnType)) continue
            val value = runCatching { method.invoke(owner) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    fun clearAllPidFreezeRecords() {
        pidFreezeLock.lock()
        try {
            uidPidFreezeTable.clear()
            recentTombstoneReasons.clear()
            Logger.info("清除冻结记录")
        } finally {
            pidFreezeLock.unlock()
        }
    }
}
