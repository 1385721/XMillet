package io.github.univers629.millet.system

import android.app.ActivityManager
import android.content.Context
import io.github.univers629.millet.core.FreezeReasonClassifier
import io.github.univers629.millet.core.HookMode
import io.github.univers629.millet.core.HookSpec
import io.github.univers629.millet.core.InterceptRule
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.NoRestrictSync
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.VersionGate
import io.github.univers629.millet.core.XReflect
import java.lang.reflect.Member

/**
 * 冻结与解冻核心三闸（S2 / S3 / S6 / S7 / S8 / S17 / S18 / S20 + UpdateDialog 初始化闸门）。
 *
 * 覆盖主线：
 * 1. 冻结闸门（S7 GreezeManagerService#freezeAction + S8 AurogonImmobulusMode#checkAppStatusForFreeze）
 * 2. 解冻压制（S20 GreezeManagerService#thawUid）
 *
 * 修正缺陷 #1：
 * - `isUidFrozen` 在 `SystemState.greezeService` 上调用
 * - `checkAppFgService` 在 `SystemState.aurogonService` 上调用
 *
 * 修正缺陷 #4：
 * 原模块 17 条解冻理由中，`activity start`、`tofront`、`MediaButton`、`receiver pkg` 4 条在真机
 * `miui-services.jar` 中不存在逐字匹配（真机实际为 `Activity Start`、` Activity Start!`、
 * `toFrontActivity`、`moveToFront`、`notifyMovetoFront` 等），导致被误拦。
 * 本实现同时收录真机精确字符串集与归一化前缀/包含匹配，杜绝误拦前台正常启动。
 */
object FreezeGateHooks {

    private const val CLASS_GREEZE_SERVICE = "com.miui.server.greeze.GreezeManagerService"
    private const val CLASS_AUROGON_MODE = "com.miui.server.greeze.AurogonImmobulusMode"
    private const val CLASS_AUROGON_APP_INFO = "com.miui.server.greeze.AurogonAppInfo"
    private const val CLASS_RUNNING_PROCESS = "com.miui.server.greeze.RunningProcess"

    private fun isSyncBinderThawReason(reason: String): Boolean =
        FreezeReasonClassifier.isSyncBinderThawReason(reason)

    private fun isAllowedThawReason(reason: String): Boolean =
        FreezeReasonClassifier.isAllowedThawReason(reason)

    private fun isToFrontReason(reason: String): Boolean =
        FreezeReasonClassifier.isToFrontReason(reason)

    private fun isNetworkWakeReason(reason: String): Boolean =
        FreezeReasonClassifier.isNetworkWakeReason(reason)

    private fun isChargeThawReason(reason: String): Boolean =
        FreezeReasonClassifier.isChargeThawReason(reason)

    private fun isScreenOnThawReason(reason: String): Boolean =
        FreezeReasonClassifier.isScreenOnThawReason(reason)

    /**
     * S2a（PA(3)）：GreezeManagerService#<init>(Context) —— 捕获冻结服务实例句柄。
     */
    fun buildS2GreezeInitHook(): HookSpec = HookSpec(
        id = "S2",
        name = "GreezeManagerService#<init>",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = null,
        paramTypes = listOf(Context::class.java),
        paramResolver = { _, clazz ->
            // 澎湃 1.0/2.0：<init>(Context)；澎湃 3.0（Android 16）：<init>() 无参
            clazz.declaredConstructors
                .firstOrNull { ctor -> ctor.parameterTypes.firstOrNull() == Context::class.java }
                ?.parameterTypes?.toList()
                ?: clazz.declaredConstructors.minByOrNull { it.parameterCount }?.parameterTypes?.toList()
        },
        mode = HookMode.AFTER,
        successLog = "获取冻结管理服务成功",
        after = { param ->
            if (SystemState.greezeService == null && param.thisObject != null) {
                SystemState.greezeService = param.thisObject
            }
            val ctx = param.args.firstOrNull() as? Context
            if (ctx != null && SystemState.greezeContext == null) {
                SystemState.greezeContext = ctx
            }
        }
    )

    /**
     * S2b：GreezeManagerService#<init>(Context) —— 生命周期初始化总闸门。
     * 置 `isGreezeReady = true`、记录模块更新时间、注册亮灭屏广播。
     */
    fun buildS2GreezeLifecycleReadyHook(): HookSpec = HookSpec(
        id = "S2_Gate",
        name = "GreezeManagerService#<init>_Lifecycle",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = null,
        paramTypes = listOf(Context::class.java),
        paramResolver = { _, clazz ->
            clazz.declaredConstructors
                .firstOrNull { ctor -> ctor.parameterTypes.firstOrNull() == Context::class.java }
                ?.parameterTypes?.toList()
                ?: clazz.declaredConstructors.minByOrNull { it.parameterCount }?.parameterTypes?.toList()
        },
        mode = HookMode.AFTER,
        after = { param ->
            if (SystemState.greezeService == null && param.thisObject != null) {
                SystemState.greezeService = param.thisObject
            }
            // 开启全模块总闸门
            SystemState.isGreezeReady = true

            val argCtx = param.args.firstOrNull() as? Context
            if (argCtx != null && SystemState.greezeContext == null) {
                SystemState.greezeContext = argCtx
            }
            val ctx = argCtx ?: SystemState.systemContext
            if (SystemState.moduleLastUpdateTime == -1L && ctx != null) {
                try {
                    @Suppress("DEPRECATION")
                    val pkgInfo = ctx.packageManager.getPackageInfo(PrefsKeys.MODULE_PACKAGE, 0)
                    SystemState.moduleLastUpdateTime = pkgInfo.lastUpdateTime
                } catch (_: Throwable) {
                    // 兼容未安装或旧包名场景，不中断后续亮灭屏广播注册
                }
            }

            if (ctx != null) {
                BroadcastAndDozeHooks.registerScreenStatusReceiver(ctx)
                FreezeStatsRecorder.registerScreenReceiver(ctx)
            }

            // Greeze 刚就绪：把「强制冻结」名单真正推下去（澎湃 3.0 没有 MilletPolicy 后唯一的下发通道）
            ForceFreezeBridge.applyAsync(force = true)

            // 同时把白名单同步进系统免冻结名单，开机后 millet 内核侧就不会再冻结这些应用
            val syncCtx = ctx
            Thread(
                {
                    NoRestrictSync.sync(syncCtx, PrefReader.activePrefs().whiteAppSet)
                },
                "XMillet-NoRestrictSync"
            ).start()
        }
    )

    /**
     * S3（PA(8)）：AurogonImmobulusMode#<init>(Context, HandlerThread, GreezeManagerService)
     * 独立捕获奥罗贡模式实例句柄（`checkAppFgService` 所在类），并同步消息白名单。
     */
    fun buildS3AurogonInitHook(): HookSpec = HookSpec(
        id = "S3",
        name = "AurogonImmobulusMode#<init>",
        targetClass = CLASS_AUROGON_MODE,
        methodName = null,
        paramTypes = listOf(
            Context::class.java,
            android.os.HandlerThread::class.java,
            CLASS_GREEZE_SERVICE
        ),
        paramResolver = { _, clazz ->
            // 澎湃 1.0/2.0：<init>(Context, HandlerThread, GreezeManagerService)；
            // 澎湃 3.0（Android 16）：<init>() 无参，且 Aurogon 只剩极简构造
            clazz.declaredConstructors
                .firstOrNull { ctor -> ctor.parameterCount == 3 }
                ?.parameterTypes?.toList()
                ?: clazz.declaredConstructors.minByOrNull { it.parameterCount }?.parameterTypes?.toList()
        },
        mode = HookMode.AFTER,
        after = { param ->
            if (SystemState.aurogonService == null && param.thisObject != null) {
                SystemState.aurogonService = param.thisObject
            }
            SystemState.syncMessageAppsToAurogon()
        }
    )

    /**
     * S6（PA(5)）：GreezeManagerService#freezeUids(int[], long, int, String, boolean)
     * 仅做冻结记账与日志输出，不篡改返回值。
     */
    fun buildS6FreezeUidsHook(): HookSpec = HookSpec(
        id = "S6",
        name = "GreezeManagerService#freezeUids",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "freezeUids",
        paramTypes = listOf(
            IntArray::class.java,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        ),
        mode = HookMode.AFTER,
        after = { param ->
            val prefs = PrefReader.systemPrefs
            val needLog = prefs.isLog || prefs.simplifyLog
            val needStats = SystemState.isGreezeReady && prefs.freezeStats
            if (!needLog && !needStats) return@HookSpec

            val argUids = (param.args.getOrNull(0) as? IntArray) ?: IntArray(0)
            val reason = (param.args.getOrNull(3) as? String) ?: "unknown"

            if (needStats) {
                val actuallyFrozenUids = resolveEffectiveIds(param.result, param.method, argUids)
                if (actuallyFrozenUids.isNotEmpty()) {
                    FreezeStatsRecorder.recordFreezeBatch(actuallyFrozenUids, reason)
                }
            }

            if (needLog) {
                val timeout = (param.args.getOrNull(1) as? Long) ?: 0L
                val fromWho = (param.args.getOrNull(2) as? Int) ?: 0
                val flag = (param.args.getOrNull(4) as? Boolean) ?: false

                val resultList = param.result as? Collection<*>
                val effectiveUids: List<Int> = if (!resultList.isNullOrEmpty()) {
                    resultList.mapNotNull { it as? Int }
                } else {
                    argUids.toList()
                }

                val labelList = effectiveUids.joinToString(",") { uid ->
                    SystemState.formatAppLabelAndUid(uid)
                }

                if (prefs.isLog) {
                    Logger.warn("++系统[冻结Uids]:[$reason],包列表=[$labelList],time=$timeout,值=$flag,param3=$fromWho")
                    Logger.warn("冻结列表${SystemState.getFrozenUidLabels()}")
                }
                if (prefs.simplifyLog) {
                    Logger.warn("++冻结日志:冻结,因[$reason],[$labelList]")
                }
            }
        }
    )

    private fun resolveEffectiveIds(
        result: Any?,
        method: Member?,
        argIds: IntArray,
    ): List<Int> {
        return when (result) {
            is Collection<*> -> result.mapNotNull { it as? Int }
            is IntArray -> result.toList()
            is Boolean -> if (result) argIds.toList() else emptyList()
            null -> {
                val returnsVoid = (method as? java.lang.reflect.Method)?.returnType == Void.TYPE
                if (returnsVoid) argIds.toList() else emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * S7（PA(4)）：GreezeManagerService#freezeAction
     * - OS1~OS3：(int uid, int fromWho, String reason, boolean isLocked)
     * - OS4：(int uid, int fromWho, String reason, boolean isNeedCompact, boolean checkAudioGps)
     * 【冻结总闸】：
     * 1. `!isGreezeReady` -> 放行
     * 2. 包名命中 `whiteAppSet` -> 阻断冻结（返回 false）
     * 3. `LAUNCH_MODE` 特例（以及通用前台服务保护）：若包名命中 `notFreezeWhenHasFgsSet` 且
     *    `aurogonService.checkAppFgService(uid)` 判断存在前台服务 -> 阻断冻结（返回 false）
     */
    fun buildS7FreezeActionHook(): HookSpec = HookSpec(
        id = "S7",
        name = "GreezeManagerService#freezeAction",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "freezeAction",
        paramTypes = listOf(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        ),
        paramResolver = { _, clazz ->
            val candidates = clazz.declaredMethods.filter { m ->
                m.name == "freezeAction" &&
                    m.parameterCount >= 4 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == String::class.java &&
                    m.parameterTypes[3] == Boolean::class.javaPrimitiveType
            }
            (candidates.firstOrNull { it.parameterCount == 4 } ?: candidates.firstOrNull())
                ?.parameterTypes
                ?.toList()
        },
        mode = HookMode.BEFORE_AND_AFTER,
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec

            val uid = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            val reason = (param.args.getOrNull(2) as? String) ?: ""
            val pkg = SystemState.getPackageNameForUid(uid) ?: return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (prefs.freezeStats && uid >= 10000) {
                FreezeStatsRecorder.cachePackageHint(uid, pkg)
            }

            // 1. 白名单应用永不冻结（无论触发原因是什么）
            if (pkg in prefs.whiteAppSet) {
                if (prefs.freezeStats && uid >= 10000) {
                    FreezeStatsRecorder.recordProtect(uid, reason, pkg)
                }
                Logger.info("FAH判断:${pkg}在白名单，因此无法被冻结")
                Logger.logEvent(Logger.Level.INFO, "FAH判断白名单阻断冻结", pkg, uid, reason)
                param.blockSafely()
                return@HookSpec
            }

            // 2. LAUNCH_MODE 与前台服务（FGS）保护分支（强制冻结名单剥夺 FGS 豁免）
            if ((reason == "LAUNCH_MODE" || pkg in prefs.notFreezeWhenHasFgsSet) && pkg !in prefs.forceFreezeSet) {
                if (pkg in prefs.notFreezeWhenHasFgsSet) {
                    // 使用独立的 aurogonService 句柄调用 checkAppFgService（修正缺陷 #1）
                    val hasFgs = SystemState.checkAppFgService(uid)
                    if (hasFgs) {
                        if (prefs.freezeStats && uid >= 10000) {
                            FreezeStatsRecorder.recordProtect(uid, reason, pkg)
                        }
                        Logger.info("FAH判断:${pkg}具有前台服务,禁止冻结")
                        Logger.logEvent(Logger.Level.INFO, "FAH判断具有前台服务阻断冻结", pkg, uid, reason)
                        param.blockSafely()
                    }
                }
            }
        },
        after = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (!prefs.freezeStats) return@HookSpec
            if ((param.result as? Boolean) != true) return@HookSpec
            val uid = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            if (uid < 10000) return@HookSpec
            val reason = (param.args.getOrNull(2) as? String) ?: ""
            FreezeStatsRecorder.recordFreeze(
                uid = uid,
                reason = reason,
                packageNameHint = FreezeStatsRecorder.getCachedPackageName(uid),
                onlyIfNotFrozen = true,
            )
        }
    )

    /**
     * S8（PA(2)）：AurogonImmobulusMode#checkAppStatusForFreeze(AurogonAppInfo)
     * 【最早一道冻结否决门】：
     * 真机签名：`checkAppStatusForFreeze(Lcom/miui/server/greeze/AurogonAppInfo;)Z`
     * 返回 `false` 表示当前不允许冻结该应用。白名单应用直接在此否决。
     */
    fun buildS8CheckAppStatusForFreezeHook(): HookSpec = HookSpec(
        id = "S8",
        name = "AurogonImmobulusMode#checkAppStatusForFreeze",
        targetClass = CLASS_AUROGON_MODE,
        methodName = "checkAppStatusForFreeze",
        paramTypes = listOf(CLASS_AUROGON_APP_INFO),
        mode = HookMode.BEFORE,
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val appInfoObj = param.args.getOrNull(0) ?: return@HookSpec
            val pkg = try {
                XReflect.getObjectField(appInfoObj, "mPackageName") as? String
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec

            val prefs = PrefReader.systemPrefs
            if (pkg in prefs.whiteAppSet) {
                if (prefs.freezeStats) {
                    val uid = resolveAurogonAppUidZeroPm(appInfoObj, pkg)
                    if (uid >= 10000) {
                        FreezeStatsRecorder.recordProtect(uid, "checkAppStatusForFreeze", pkg)
                    }
                }
                param.blockSafely()
            }
        }
    )

    private fun resolveAurogonAppUidZeroPm(appInfoObj: Any, pkg: String): Int {
        val clazz = appInfoObj.javaClass
        val fieldUid = when {
            RomCapabilities.hasField(clazz, "mUid") ->
                runCatching { XReflect.getIntField(appInfoObj, "mUid") }.getOrDefault(-1)
            RomCapabilities.hasField(clazz, "uid") ->
                runCatching { XReflect.getIntField(appInfoObj, "uid") }.getOrDefault(-1)
            else -> -1
        }
        if (fieldUid >= 10000) return fieldUid
        return FreezeStatsRecorder.getCachedUidForPackage(pkg)
    }

    /**
     * S17（MSH(0)）：GreezeManagerService#freezeProcess(RunningProcess, long, int, String)
     * 受 `simplifyLog` 装配期门控：记录「谁冻了谁、原因」及该 UID 下冻结进程占比。
     */
    fun buildS17FreezeProcessHook(): HookSpec = HookSpec(
        id = "S17",
        name = "GreezeManagerService#freezeProcess",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "freezeProcess",
        paramTypes = listOf(
            CLASS_RUNNING_PROCESS,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java
        ),
        mode = HookMode.AFTER,
        gate = { PrefReader.systemPrefs.simplifyLog },
        after = { param ->
            if (!PrefReader.systemPrefs.simplifyLog) return@HookSpec
            val succeeded = (param.result as? Boolean) ?: false
            if (!succeeded) return@HookSpec

            val runningProc = param.args.getOrNull(0) ?: return@HookSpec
            val reason = (param.args.getOrNull(3) as? String) ?: "unknown"

            val uid = try {
                XReflect.getIntField(runningProc, "uid")
            } catch (_: Throwable) {
                -1
            }
            val pid = try {
                XReflect.getIntField(runningProc, "pid")
            } catch (_: Throwable) {
                -1
            }
            val processName = try {
                XReflect.getObjectField(runningProc, "processName") as? String
            } catch (_: Throwable) {
                null
            } ?: ""

            if (uid <= 0 || pid <= 0 || processName.contains("com.google.android.webview")) {
                return@HookSpec
            }

            Logger.postIoTask {
                if (SystemState.isSystemAppUid(uid)) return@postIoTask
                SystemState.recordPidFreezeState(uid, pid, frozen = true, reason = reason)
                val (frozenCount, totalCount, mapSnapshot) = SystemState.getUidFreezeSummary(uid)
                Logger.alwaysInfo(
                    "freezeProcess由于[$reason],冻结进程[$processName],进程冻结情况:$frozenCount/$totalCount,$mapSnapshot"
                )
            }
        }
    )

    /**
     * S18（MSH(1)）：GreezeManagerService#thawProcess(int pid, int fromWho, String reason)
     * 受 `simplifyLog` 装配期门控：从 `/proc/<pid>/status` 异步反查 UID 并更新进程解冻统计。
     */
    fun buildS18ThawProcessHook(): HookSpec = HookSpec(
        id = "S18",
        name = "GreezeManagerService#thawProcess",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "thawProcess",
        paramTypes = listOf(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java
        ),
        mode = HookMode.AFTER,
        gate = { PrefReader.systemPrefs.simplifyLog },
        after = { param ->
            if (!PrefReader.systemPrefs.simplifyLog) return@HookSpec
            val succeeded = (param.result as? Boolean) ?: false
            if (!succeeded) return@HookSpec

            val pid = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            val reason = (param.args.getOrNull(2) as? String) ?: "unknown"

            // 投递至单线程 Handler 读取 /proc/<pid>/status，不在 Hook 回调线程内执行 IO
            Logger.postIoTask {
                val uid = SystemState.readUidFromProcStatus(pid)
                if (uid <= 0 || SystemState.isSystemAppUid(uid)) return@postIoTask

                val processName = resolveProcessNameByPid(pid) ?: "pid:$pid"
                SystemState.recordPidFreezeState(uid, pid, frozen = false, reason = reason)
                if (!processName.contains("com.google.android.webview") &&
                    reason != "LAUNCH_MODE" &&
                    reason != "IMMOBULUS"
                ) {
                    val (frozenCount, totalCount, mapSnapshot) = SystemState.getUidFreezeSummary(uid)
                    Logger.alwaysInfo(
                        "thawProcess由于[$reason],解冻进程[$processName],进程冻结情况:$frozenCount/$totalCount,$mapSnapshot"
                    )
                }
            }
        }
    )

    /**
     * S20（SettingsSection）：GreezeManagerService#thawUid(int uid, int fromWho, String reason)
     * 【解冻压制 / 冻结保持核心】：
     * 1. `!isGreezeReady` 或包名/reason 为空 -> 放行
     * 2. 若 reason 代表切回前台（toFront），记录 `lastToFrontPackage = pkg`
     * 3. 若 `(reason == "screen on" && lastToFrontPackage == pkg)` 或当前 UID 本来就未冻结 -> 放行
     * 4. 若 `pkg == "com.tencent.mobileqq" && reason == "broadcast"` 或 `reason == "Broadcast"` -> 永久拒绝解冻
     * 5. 若 `reason in {"Sync Binder", "Check Binder"}` 且 `pkg in forbidSyncBinderSet` -> 拒绝解冻
     * 6. 若 `pkg in ignoreNetworkWakeSet` 且 `reason` 属网络类（PACKET / Net Change / Signal / broadcast）
     *    -> 拒绝解冻（优先于理由放行表，代价是推送延迟到下次打开应用）
     * 7. 若 `pkg !in blockThawSet` 或 `isAllowedThawReason(reason)` 为真 -> 放行
     * 8. 其余情况 -> 强力压制，拒绝解冻（`param.result = false`）
     */
    fun buildS20ThawUidHook(): HookSpec = HookSpec(
        id = "S20",
        name = "GreezeManagerService#thawUid",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "thawUid",
        paramTypes = listOf(
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java
        ),
        mode = HookMode.BEFORE,
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec

            val uid = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            val reason = (param.args.getOrNull(2) as? String) ?: return@HookSpec
            val pkg = SystemState.getPackageNameForUid(uid) ?: return@HookSpec
            val prefs = PrefReader.systemPrefs

            if (isToFrontReason(reason)) {
                SystemState.lastToFrontPackage = pkg
            }

            // 亮屏时恢复前台最后交互应用，或该 UID 当前本来就处于未冻结态 -> 直接放行
            if (isScreenOnThawReason(reason) && SystemState.lastToFrontPackage == pkg) {
                if (prefs.freezeStats && uid >= 10000 &&
                    !FreezeStatsRecorder.isDispatchThawEventActive &&
                    FreezeStatsRecorder.isUidTrackedFrozen(uid) &&
                    SystemState.isUidFrozen(uid)
                ) {
                    FreezeStatsRecorder.recordThaw(uid, reason, pkg)
                }
                return@HookSpec
            }
            if (!SystemState.isUidFrozen(uid)) {
                return@HookSpec
            }

            // 硬规则 1：QQ 广播唤醒永久拒绝
            if ((pkg == "com.tencent.mobileqq" && reason == "broadcast") || reason == "Broadcast") {
                if (prefs.freezeStats && uid >= 10000) {
                    FreezeStatsRecorder.recordIntercept(uid, InterceptRule.HARD_RULE, reason, pkg)
                }
                param.blockSafely()
                return@HookSpec
            }

            // 硬规则 2：命中「禁止同步 Binder」集合时拦截 Sync Binder* / Check Binder* 解冻
            if (isSyncBinderThawReason(reason) && pkg in prefs.forbidSyncBinderSet) {
                if (prefs.freezeStats && uid >= 10000) {
                    FreezeStatsRecorder.recordIntercept(uid, InterceptRule.FORBID_SYNC_BINDER, reason, pkg)
                }
                Logger.warn("--系统[解冻uid]:禁止[$reason]解冻[$pkg]uid:$uid")
                param.blockSafely()
                return@HookSpec
            }

            // 规则 2.5：「忽略网络唤醒」——网络类理由不再放行，冻结保持到用户主动打开。
            // 独立于「禁止后台解冻」生效：勾了这一个开关就足够，不必再额外压一层。
            if (pkg in prefs.ignoreNetworkWakeSet && isNetworkWakeReason(reason)) {
                if (prefs.freezeStats && uid >= 10000) {
                    FreezeStatsRecorder.recordIntercept(uid, InterceptRule.IGNORE_NETWORK, reason, pkg)
                }
                Logger.warn("--系统[解冻uid]:忽略网络唤醒,禁止[$reason]解冻[$pkg]uid:$uid")
                param.blockSafely()
                return@HookSpec
            }

            // 规则 2.6：全局「禁止插电时全量解冻」。
            // 插充电器这条路径在 OS1/OS2 上并不是 powerkeeper 的 MilletBrightHandler what=16，
            // 而是 Greezer 自己逐个 uid 调 thawUid(reason="battery charging")，所以只挂 P10 拦不住，
            // 必须在闸门这里一并拒掉，否则「禁止插电时全量解冻」在旧版本上等于没开。
            if (prefs.disableCharge && isChargeThawReason(reason)) {
                if (prefs.freezeStats && uid >= 10000) {
                    FreezeStatsRecorder.recordIntercept(uid, InterceptRule.NO_CHARGE_THAW, reason, pkg)
                }
                Logger.warn("--系统[解冻uid]:禁止插电解冻,拒绝[$reason]解冻[$pkg]uid:$uid")
                param.blockSafely()
                return@HookSpec
            }

            // 规则 2.7：可选「亮屏时保持冻结」——只拦 screen on，
            // 前台拉起（activity start / toFront）等其余合法理由照常放行。
            if (prefs.disableScreenOnThaw && isScreenOnThawReason(reason)) {
                if (prefs.freezeStats && uid >= 10000) {
                    FreezeStatsRecorder.recordIntercept(uid, InterceptRule.NO_SCREEN_ON_THAW, reason, pkg)
                }
                Logger.warn("--系统[解冻uid]:禁止亮屏解冻,拒绝[$reason]解冻[$pkg]uid:$uid")
                param.blockSafely()
                return@HookSpec
            }

            // 规则 3：未勾选「强力压制」(blockThawSet)，或者解冻理由命中合法解冻理由白名单 -> 放行
            if (pkg !in prefs.blockThawSet || isAllowedThawReason(reason)) {
                if (prefs.freezeStats && uid >= 10000 && !FreezeStatsRecorder.isDispatchThawEventActive) {
                    FreezeStatsRecorder.recordThaw(uid, reason, pkg)
                }
                return@HookSpec
            }

            // 规则 4：强力压制拦截
            if (prefs.freezeStats && uid >= 10000) {
                FreezeStatsRecorder.recordIntercept(uid, InterceptRule.BLOCK_THAW, reason, pkg)
            }
            Logger.warn("--系统[解冻uid]:强力压制,禁止[$reason]解冻[$pkg]uid:$uid")
            param.blockSafely()
        }
    )

    /**
     * S21：GreezeManagerService#thawUids(int[] uids, int fromWho, String reason)
     *
     * 澎湃 3.0 的框架侧批量解冻（音量键、音频焦点、亮屏批量恢复）走这个入口，而不是单个 `thawUid`。
     * 平时只记录；命中「忽略网络唤醒」名单的应用在批量网络唤醒里会被摘出去，保持冻结。
     */
    fun buildS21ThawUidsProbeHook(): HookSpec = HookSpec(
        id = "S21",
        name = "GreezeManagerService#thawUids",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "thawUids",
        paramTypes = listOf(
            IntArray::class.java,
            Int::class.javaPrimitiveType!!,
            String::class.java
        ),
        mode = HookMode.BEFORE,
        before = { param ->
            val prefs = PrefReader.systemPrefs
            if (prefs.isLog) {
                val uids = param.args.getOrNull(0) as? IntArray
                val reason = param.args.getOrNull(2) as? String ?: "-"
                val pkgNames = uids
                    ?.asSequence()
                    ?.mapNotNull { SystemState.getPackageNameForUid(it) }
                    ?.distinct()
                    ?.joinToString(",")
                    .orEmpty()
                Logger.info("批量解冻入口[$reason]: uids=[${uids?.joinToString(",") ?: "-"}] pkgs=[$pkgNames]")
            }

            // 批量解冻同样要尊重「忽略网络唤醒」：框架侧网络事件可能一次唤醒多个 uid，
            // 只拦 S20 的单个入口会漏掉这条路径。
            // 另外「禁止插电时全量解冻」在部分版本上也是走批量入口的，一并处理。
            val uids = param.args.getOrNull(0) as? IntArray ?: return@HookSpec
            val reason = param.args.getOrNull(2) as? String ?: return@HookSpec
            val needFallbackThaw = SystemState.isGreezeReady &&
                prefs.freezeStats &&
                !FreezeStatsRecorder.isDispatchThawEventActive
            val ignoreSet = prefs.ignoreNetworkWakeSet
            val chargeThaw = prefs.disableCharge && isChargeThawReason(reason)
            val networkThaw = isNetworkWakeReason(reason) && ignoreSet.isNotEmpty()
            val screenOnThaw = prefs.disableScreenOnThaw && isScreenOnThawReason(reason)
            if (!chargeThaw && !networkThaw && !screenOnThaw) {
                if (needFallbackThaw) {
                    for (uid in uids) {
                        if (uid >= 10000 && FreezeStatsRecorder.isUidTrackedFrozen(uid)) {
                            FreezeStatsRecorder.recordThaw(uid, reason)
                        }
                    }
                }
                return@HookSpec
            }

            var blocked = false
            val kept = ArrayList<Int>(uids.size)
            for (uid in uids) {
                val pkg = SystemState.getPackageNameForUid(uid)
                val interceptRule: InterceptRule? = if (pkg != null) {
                    when {
                        chargeThaw -> InterceptRule.NO_CHARGE_THAW
                        screenOnThaw && pkg != SystemState.lastToFrontPackage -> InterceptRule.NO_SCREEN_ON_THAW
                        networkThaw && pkg in ignoreSet -> InterceptRule.IGNORE_NETWORK
                        else -> null
                    }
                } else {
                    null
                }
                if (interceptRule != null) {
                    blocked = true
                    if (prefs.freezeStats && uid >= 10000) {
                        FreezeStatsRecorder.recordIntercept(uid, interceptRule, reason, pkg)
                    }
                    Logger.warn("thawUids 拒绝批量解冻: 保持冻结 $pkg uid=$uid reason=$reason")
                } else {
                    kept.add(uid)
                    if (needFallbackThaw && uid >= 10000 && FreezeStatsRecorder.isUidTrackedFrozen(uid)) {
                        FreezeStatsRecorder.recordThaw(uid, reason, pkg)
                    }
                }
            }
            if (!blocked) return@HookSpec
            if (kept.isEmpty()) {
                param.blockSafely()
            } else {
                param.args[0] = kept.toIntArray()
            }
        }
    )

    /**
     * S22（诊断探点）：GreezeManagerService#dispatchThawEvent(int uid, String reason)
     *
     * 解冻之后的通知分发，uid + reason 都在参数里，用来确认音量键解冻到底走哪条链路。
     */
    fun buildS22DispatchThawProbeHook(): HookSpec = HookSpec(
        id = "S22",
        name = "GreezeManagerService#dispatchThawEvent",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "dispatchThawEvent",
        paramTypes = listOf(Int::class.javaPrimitiveType!!, String::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            val uid = param.args.getOrNull(0) as? Int ?: return@HookSpec
            val reason = param.args.getOrNull(1) as? String ?: "-"
            val prefs = PrefReader.systemPrefs
            if (SystemState.isGreezeReady && prefs.freezeStats && uid >= 10000) {
                FreezeStatsRecorder.recordThaw(uid, reason)
            }
            if (prefs.isLog) {
                val pkg = SystemState.getPackageNameForUid(uid) ?: "uid$uid"
                Logger.info("解冻事件[$reason]: $pkg uid=$uid")
            }
        }
    )

    /**
     * S23：【冻结闸门 - 进程级】GreezeManagerService#freezePids(...)
     *
     * 澎湃 3.0 的批量冻结还有一条只按 pid 走的分支
     * （`ActionExecute.freezeNonKeyProcesses → freezePids`），它不经过 `freezeUid` /
     * `freezeProcess`，因此免冻白名单与「前台服务不冻结」在这条路径上原本是失效的：
     * 多进程应用（微信 `:push`、QQ `:MSF` 等）的子进程会被单独冻掉。
     *
     * 这里在冻结之前把属于免冻结应用的 pid 从数组里摘掉；全部摘空时直接否决本次调用。
     */
    fun buildS23FreezePidsHook(): HookSpec = HookSpec(
        id = "S23",
        name = "GreezeManagerService#freezePids",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "freezePids",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            clazz.declaredMethods.firstOrNull { it.name == "freezePids" }?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE_AND_AFTER,
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val pidArray = param.args.getOrNull(0) as? IntArray ?: return@HookSpec
            if (pidArray.isEmpty()) return@HookSpec

            val prefs = PrefReader.systemPrefs
            if (prefs.whiteAppSet.isEmpty() && prefs.notFreezeWhenHasFgsSet.isEmpty()) return@HookSpec

            var blocked = false
            val kept = ArrayList<Int>(pidArray.size)
            var protectedUids: HashSet<Int>? = null
            var protectReason: String? = null
            for (pid in pidArray) {
                val uid = uidForPid(pid)
                val pkg = if (uid > 0) SystemState.getPackageNameForUid(uid) else null
                val exempt = pkg != null && (
                    pkg in prefs.whiteAppSet ||
                        (pkg !in prefs.forceFreezeSet &&
                            pkg in prefs.notFreezeWhenHasFgsSet &&
                            SystemState.checkAppFgService(uid))
                    )
                if (exempt) {
                    blocked = true
                    if (prefs.freezeStats && uid >= 10000) {
                        val seen = protectedUids ?: HashSet<Int>().also { protectedUids = it }
                        if (seen.add(uid)) {
                            val r = protectReason ?: (
                                param.args.filterIsInstance<String>()
                                    .firstOrNull { it.isNotBlank() } ?: "freezePids"
                                ).also { protectReason = it }
                            FreezeStatsRecorder.recordProtect(uid, r, pkg)
                        }
                    }
                    Logger.info("freezePids 跳过免冻结应用进程: $pkg pid=$pid")
                } else {
                    kept.add(pid)
                }
            }

            if (!blocked) return@HookSpec
            if (kept.isEmpty()) {
                param.blockSafely()
            } else {
                param.args[0] = kept.toIntArray()
            }
            Logger.logEvent(Logger.Level.INFO, "freezePids 冻结闸门生效", kept.joinToString(","))
        },
        after = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (!prefs.freezeStats) return@HookSpec
            val argPids = (param.args.getOrNull(0) as? IntArray)?.takeIf { it.isNotEmpty() } ?: return@HookSpec
            val frozenPids = resolveEffectiveIds(param.result, param.method, argPids)
            if (frozenPids.isEmpty()) return@HookSpec
            val reason = param.args.filterIsInstance<String>()
                .firstOrNull { it.isNotBlank() } ?: "freezePids"
            Logger.postIoTask {
                val seenUids = HashSet<Int>()
                for (pid in frozenPids) {
                    val uid = uidForPid(pid)
                    if (uid >= 10000 && seenUids.add(uid)) {
                        FreezeStatsRecorder.recordFreeze(uid, reason, onlyIfNotFrozen = true)
                    }
                }
            }
        }
    )

    /**
     * pid → uid。`android.os.Process#getUidForPid` 属于隐藏 API，走反射调用；
     * 取不到时返回 -1，由调用方按「未知身份」放行，避免误伤系统进程。
     */
    private fun uidForPid(pid: Int): Int {
        return try {
            val method = getUidForPidMethod ?: return -1
            (method.invoke(null, pid) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private val getUidForPidMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.os.Process")
                .getDeclaredMethod("getUidForPid", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private fun resolveProcessNameByPid(pid: Int): String? {
        val ctx = SystemState.systemContext ?: return null
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * S21: PowerManagerService#acquireWakeLockInternal
     * 当应用处于墓碑冻结状态时，拦截其申请 WakeLock，防止被冻结应用锁死 CPU 影响 Deep Sleep。
     */
    fun buildWakeLockHook(): HookSpec = HookSpec(
        id = "S21_WAKELOCK",
        name = "PowerManagerService#acquireWakeLockInternal",
        targetClass = "com.android.server.power.PowerManagerService",
        methodName = "acquireWakeLockInternal",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            clazz.declaredMethods.firstOrNull { it.name == "acquireWakeLockInternal" }?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            val uid = (param.args.getOrNull(7) as? Int)
                ?: (param.args.getOrNull(6) as? Int)
                ?: return@HookSpec
            if (uid > 10000 && SystemState.isUidFrozen(uid)) {
                param.result = null
                if (PrefReader.systemPrefs.isLog) {
                    Logger.debug("阻止已冻结应用申请 WakeLock: UID=$uid")
                }
            }
        }
    )

    /**
     * S24: GreezeManagerService#isAllowBroadcast
     *
     * 官方广播放行入口（澎湃 1.0 起存在）。系统在决定「这条广播能不能送给冻结中的应用」
     * 时会先问它。本模块的 S9/S10 挂在广播队列侧，澎湃 3.0 的推送投递会绕过队列直连，
     * 「消息应用 / 免冻白名单」的推送只在部分投递路径上放行，「禁止后台解冻」对推送
     * 仅部分生效。这里只在官方入口上按应用名单放行，其余交回系统判断，
     * 不改变非白名单应用的既有行为。
     */
    fun buildS24AllowBroadcastHook(): HookSpec = HookSpec(
        id = "S24",
        name = "GreezeManagerService#isAllowBroadcast",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "isAllowBroadcast",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            clazz.declaredMethods
                .filter { it.name == "isAllowBroadcast" }
                .minByOrNull { it.parameterCount }
                ?.parameterTypes?.toList()
        },
        mode = HookMode.REPLACE,
        replace = { param ->
            if (!SystemState.isGreezeReady) {
                return@HookSpec param.invokeOriginal()
            }
            val prefs = PrefReader.systemPrefs
            // 已知签名：isAllowBroadcast(int callerUid, String callerPkg, int calleeUid, String calleePkg, String action)
            val calleeUid = param.args.getOrNull(2) as? Int ?: -1
            val calleePkg = (param.args.getOrNull(3) as? String)?.takeIf { it.isNotEmpty() }
                ?: SystemState.getPackageNameForUid(calleeUid)
                ?: return@HookSpec param.invokeOriginal()
            val exempt = calleePkg in prefs.whiteAppSet ||
                (calleePkg in prefs.messageAppSet && calleePkg !in prefs.ignoreNetworkWakeSet) ||
                (calleePkg !in prefs.forceFreezeSet &&
                    calleePkg in prefs.notFreezeWhenHasFgsSet &&
                    SystemState.checkAppFgService(calleeUid))
            if (!exempt) {
                return@HookSpec param.invokeOriginal()
            }
            if (prefs.isLog) {
                val action = param.args.getOrNull(4) as? String ?: "-"
                Logger.info("isAllowBroadcast 放行消息应用广播: $calleePkg action=$action")
            }
            true
        }
    )

    /**
     * S25: GreezeManagerService#deferBroadcastForMiui
     *
     * 澎湃自己的「延后投递」入口：返回 true 表示这条广播先攒着，等应用解冻再发。
     * 该入口只带 action、不带收件应用，因此仅在 action 字符串里能认出消息应用包名时
     * 放行（澎湃常见形式为 `com.tencent.mm/com.tencent.mm.push.X`），认不出就交回系统，
     * 避免变成「全局关掉延后投递」这种额外实现。
     */
    fun buildS25DeferBroadcastHook(): HookSpec = HookSpec(
        id = "S25",
        name = "GreezeManagerService#deferBroadcastForMiui",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "deferBroadcastForMiui",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            clazz.declaredMethods
                .filter { it.name == "deferBroadcastForMiui" }
                .minByOrNull { it.parameterCount }
                ?.parameterTypes?.toList()
        },
        mode = HookMode.REPLACE,
        replace = { param ->
            if (!SystemState.isGreezeReady) {
                return@HookSpec param.invokeOriginal()
            }
            val prefs = PrefReader.systemPrefs
            val targets = (prefs.messageAppSet - prefs.ignoreNetworkWakeSet) + prefs.whiteAppSet
            if (targets.isEmpty()) {
                return@HookSpec param.invokeOriginal()
            }
            val action = param.args.filterIsInstance<String>().firstOrNull()
                ?: return@HookSpec param.invokeOriginal()
            val matched = targets.firstOrNull { action.contains(it) }
                ?: return@HookSpec param.invokeOriginal()
            if (prefs.isLog) {
                Logger.info("deferBroadcastForMiui 放行消息应用广播: $matched action=$action")
            }
            false
        }
    )

    /**
     * S26: GreezeManagerService#thawUidAsync
     *
     * OS1/OS2 上插充电器的解冻不走 `thawUid`（同步版），而是发给异步版
     * `thawUidAsync`：greezer 历史里能看到逐条 `THAW uid = x reason : battery charging`，
     * 但 S20 的同步闸门完全不触发，于是「禁止插电时全量解冻」在旧版本上形同虚设。
     * 这里只按理由文本识别充电事件，命中且开关打开时拒绝本次解冻，其余情况放行。
     */
    fun buildS26ThawUidAsyncHook(): HookSpec = HookSpec(
        id = "S26",
        name = "GreezeManagerService#thawUidAsync",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "thawUidAsync",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            clazz.declaredMethods
                .filter { it.name == "thawUidAsync" }
                .minByOrNull { it.parameterCount }
                ?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            val prefs = PrefReader.systemPrefs
            val reason = param.args.filterIsInstance<String>()
                .firstOrNull { it.isNotBlank() } ?: return@HookSpec
            val chargeThaw = prefs.disableCharge && isChargeThawReason(reason)
            val screenOnThaw = prefs.disableScreenOnThaw && isScreenOnThawReason(reason)
            if (!chargeThaw && !screenOnThaw) return@HookSpec
            val uid = param.args.filterIsInstance<Int>()
                .firstOrNull { it >= 10000 } ?: -1
            val pkg = if (uid >= 0) SystemState.getPackageNameForUid(uid) else null
            if (prefs.freezeStats && uid >= 10000) {
                val rule = if (chargeThaw) InterceptRule.NO_CHARGE_THAW else InterceptRule.NO_SCREEN_ON_THAW
                FreezeStatsRecorder.recordIntercept(uid, rule, reason, pkg)
            }
            val label = if (chargeThaw) "禁止插电解冻" else "禁止亮屏解冻"
            Logger.warn("--系统[解冻uid]:$label(async),拒绝[$reason]解冻[${pkg ?: "uid$uid"}]")
            param.result = null
        }
    )

    /**
     * S27: GreezeManagerService#thawAll
     *
     * 真正的「全量解冻」入口（开机完成、插电、切换省电模式时会被调用）。
     * 只拦充电理由：其它理由（开机、用户操作）必须原样放行，否则会把系统自己的
     * 恢复流程一起掐掉。
     */
    fun buildS27ThawAllHook(): HookSpec = HookSpec(
        id = "S27",
        name = "GreezeManagerService#thawAll",
        targetClass = CLASS_GREEZE_SERVICE,
        methodName = "thawAll",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            clazz.declaredMethods
                .filter { it.name == "thawAll" }
                .minByOrNull { it.parameterCount }
                ?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            val prefs = PrefReader.systemPrefs
            val reason = param.args.filterIsInstance<String>()
                .firstOrNull { it.isNotBlank() } ?: return@HookSpec
            val chargeThaw = prefs.disableCharge && isChargeThawReason(reason)
            val screenOnThaw = prefs.disableScreenOnThaw && isScreenOnThawReason(reason)
            if (!chargeThaw && !screenOnThaw) return@HookSpec
            if (prefs.freezeStats) {
                val rule = if (chargeThaw) InterceptRule.NO_CHARGE_THAW else InterceptRule.NO_SCREEN_ON_THAW
                FreezeStatsRecorder.recordSystemIntercept(rule, reason)
            }
            val label = if (chargeThaw) "禁止插电解冻" else "禁止亮屏解冻"
            Logger.warn("--系统[解冻uid]:$label(thawAll),拒绝[$reason]全量解冻")
            param.blockSafely()
        }
    )

    /**
     * S22: ProcessManager#kill(ProcessConfig)
     * 当 system_server 尝试通过 ProcessManager 杀应用时，若该应用正处于墓碑冻结状态或受保护，拦截杀戮调用。
     */
    fun buildSystemProcessManagerKillHook(): HookSpec = HookSpec(
        id = "S22_KILL",
        name = "ProcessManager#kill",
        targetClass = "miui.process.ProcessManager",
        methodName = "kill",
        paramTypes = emptyList(),
        paramResolver = { _, clazz ->
            val killMethods = clazz.declaredMethods.filter { it.name == "kill" }
            val configMethod = killMethods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].name == "miui.process.ProcessConfig"
            }
            (configMethod ?: killMethods.firstOrNull())?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            val config = param.args.getOrNull(0) ?: return@HookSpec
            val uid = try {
                XReflect.getIntField(config, "mUid")
            } catch (_: Throwable) {
                try {
                    XReflect.callMethod(config, "getUid") as? Int
                } catch (_: Throwable) { null }
            } ?: -1
            if (uid > 10000 && (SystemState.isUidFrozen(uid) || PrefReader.systemPrefs.disableKill)) {
                if (PrefReader.systemPrefs.freezeStats) {
                    FreezeStatsRecorder.recordKillBlocked(uid, "ProcessManager#kill")
                }
                if (param.blockSafely() && PrefReader.systemPrefs.isLog) {
                    Logger.warn("阻止系统杀戮处于墓碑状态的应用: UID=$uid")
                }
            }
        }
    )

}
