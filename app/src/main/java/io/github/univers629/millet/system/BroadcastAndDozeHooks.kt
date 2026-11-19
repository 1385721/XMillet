package io.github.univers629.millet.system

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import io.github.univers629.millet.core.ActiveImeTracker
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.core.HookMode
import io.github.univers629.millet.core.HookSpec
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.VersionGate
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayList

/**
 * 广播唤醒拦截（S9 / S10 按 SDK_INT 二选一）与 Doze 状态机接管（S11 / S12 / S13 / S14）及电量统计。
 *
 * 覆盖主线：
 * - 广播唤醒拦截：S9（SDK > 33，BroadcastSkipPolicy#shouldSkipMessage）与
 *   S10（SDK <= 33，BroadcastQueue#deliverToRegisteredReceiverLocked）二选一安装。
 * - Doze 状态机：S12（onStart 抓句柄）、S13（motionLocked 运动不唤醒）、
 *   S14（moveToStateLocked 跳过 IDLE_PENDING 直进 IDLE_MAINTENANCE）。
 */
object BroadcastAndDozeHooks {

    private const val CLASS_AMS = "com.android.server.am.ActivityManagerService"
    private const val CLASS_BROADCAST_SKIP_POLICY = "com.android.server.am.BroadcastSkipPolicy"
    private const val CLASS_BROADCAST_QUEUE = "com.android.server.am.BroadcastQueue"
    private const val CLASS_BROADCAST_RECORD = "com.android.server.am.BroadcastRecord"
    private const val CLASS_BROADCAST_FILTER = "com.android.server.am.BroadcastFilter"
    private const val CLASS_DEVICE_IDLE_CONTROLLER = "com.android.server.DeviceIdleController"
    private const val CLASS_DEVICE_IDLE_SHELL = "com.android.server.DeviceIdleController\$Shell"

    private const val REASON_XMILLET_FORCE = "XMilletForce"
    private const val REASON_XMILLET_SKIP = "XMilletSkip"
    private const val SKIP_FROZEN_BROADCAST_REASON = "Skip frozen process by XMillet"

    /** DeviceIdleController$Shell 实例缓存（用于执行深度睡眠 shell 指令） */
    @Volatile
    private var idleShellInstance: Any? = null
    private val idleShellLock = Any()

    /** 当前是否已进入 Deep Doze（state == 5 IDLE 或 state == 6 IDLE_MAINTENANCE） */
    @Volatile
    private var isInDeepIdle: Boolean = false

    private val receiverLifecycleLock = Any()

    @Volatile
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var screenReceiverContext: Context? = null

    @Volatile
    private var screenReceiverRegistered: Boolean = false

    @Volatile
    private var batteryReceiverContext: Context? = null

    @Volatile
    private var batteryReceiverRegistered: Boolean = false

    @Volatile
    private var lastBatteryPercent: Int = -1

    @Volatile
    private var lastBatteryTimestamp: Long = -1L

    private val screenThreadLock = Any()

    @Volatile
    private var screenThread: HandlerThread? = null

    @Volatile
    private var screenHandlerInstance: Handler? = null

    @Volatile
    private var hotReloading: Boolean = false

    private fun ensureScreenHandler(): Handler? {
        if (hotReloading) return null
        screenHandlerInstance?.let { return it }
        synchronized(screenThreadLock) {
            if (hotReloading) return null
            screenHandlerInstance?.let { return it }
            val thread = HandlerThread("XMillet-Screen", Process.THREAD_PRIORITY_FOREGROUND)
            thread.start()
            val handler = Handler(thread.looper)
            screenThread = thread
            screenHandlerInstance = handler
            return handler
        }
    }

    /**
     * 热重载卸载前注销广播接收器、取消闹钟监听并停掉 `XMillet-Screen` 线程，避免旧代 ClassLoader 泄漏与回调叠加。
     */
    fun onHotReloading() {
        hotReloading = true
        val screenCtx: Context?
        val scrReceiver: BroadcastReceiver?
        val batCtx: Context?
        val wasBatRegistered: Boolean
        synchronized(receiverLifecycleLock) {
            screenCtx = screenReceiverContext
            scrReceiver = screenReceiver
            screenReceiver = null
            screenReceiverContext = null
            screenReceiverRegistered = false

            batCtx = batteryReceiverContext
            wasBatRegistered = batteryReceiverRegistered
            batteryReceiverContext = null
            batteryReceiverRegistered = false
        }
        if (screenCtx != null && scrReceiver != null) {
            runCatching { screenCtx.unregisterReceiver(scrReceiver) }
        }
        if (wasBatRegistered && batCtx != null) {
            runCatching { batCtx.unregisterReceiver(batteryChangeReceiver) }
        }
        val alarmCtx = screenCtx ?: batCtx ?: SystemState.systemContext
        alarmCtx?.let { ctx ->
            runCatching {
                val am = ctx.getSystemService(AlarmManager::class.java)
                am?.cancel(dualScreenOffFreezeListener)
                am?.cancel(deepDozeAlarmListener)
            }
        }
        val thread: HandlerThread?
        synchronized(screenThreadLock) {
            screenHandlerInstance?.removeCallbacksAndMessages(null)
            screenHandlerInstance = null
            thread = screenThread
            screenThread = null
        }
        thread?.quitSafely()
    }

    private val dualScreenOffFreezeListener = AlarmManager.OnAlarmListener {
        if (!SystemState.hasAurogonIsUidValid && PrefReader.systemPrefs.freezeDualApp) {
            ForceFreezeBridge.freezeDualAppsOnScreenOffAsync()
        }
    }

    private val dualScreenOffPostRunnable = Runnable {
        if (!SystemState.hasAurogonIsUidValid && PrefReader.systemPrefs.freezeDualApp) {
            ForceFreezeBridge.freezeDualAppsOnScreenOffAsync()
        }
    }

    private val deepDozeAlarmListener = AlarmManager.OnAlarmListener {
        synchronized(idleShellLock) {
            val (enableCode, enableOut) = execIdleShellCommand("enable", "deep")
            if (enableCode == 0) {
                val (forceCode, forceOut) = execIdleShellCommand("force-idle", "deep")
                if (forceCode == 0) {
                    Logger.alwaysWarn("深度睡眠成功")
                } else {
                    Logger.debug("深度睡眠失败 $forceOut")
                }
            } else {
                Logger.info("深度睡眠启用失败 $enableOut")
            }
        }
    }

    /**
     * S9（PA(1)）：BroadcastSkipPolicy#shouldSkipMessage(BroadcastRecord, BroadcastFilter)
     * 门控：`SDK_INT > 33`（Android 14+ 新广播管线）。
     * 冻结中的非系统、非白名单应用直接返回非空跳过原因字符串。
     */
    fun buildS9BroadcastSkipPolicyHook(): HookSpec = HookSpec(
        id = "S9",
        name = "BroadcastSkipPolicy#shouldSkipMessage",
        targetClass = CLASS_BROADCAST_SKIP_POLICY,
        methodName = "shouldSkipMessage",
        paramTypes = listOf(CLASS_BROADCAST_RECORD, CLASS_BROADCAST_FILTER),
        paramResolver = { _, clazz ->
            // 澎湃 1.0/2.0：(Record, BroadcastFilter)；
            // 澎湃 3.0（Android 16）保留的是 (Record, BroadcastFilter, boolean)，
            // 另有两个 (Record, ResolveInfo/Object, boolean) 的重载，这里按第二参类型挑。
            clazz.declaredMethods
                .filter { method ->
                    method.name == "shouldSkipMessage" &&
                        method.parameterCount >= 2 &&
                        method.parameterTypes[1].name == CLASS_BROADCAST_FILTER
                }
                .minByOrNull { it.parameterCount }
                ?.parameterTypes?.toList()
        },
        mode = HookMode.REPLACE,
        gate = { VersionGate.isSdkAfter33 },
        successLog = "获取广播成功",
        replace = { param ->
            if (!SystemState.isGreezeReady) {
                return@HookSpec param.invokeOriginal()
            }
            val filter = param.args.getOrNull(1) ?: return@HookSpec param.invokeOriginal()
            val receiverList = try {
                XReflect.getObjectField(filter, "receiverList")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val appProcessRecord = try {
                XReflect.getObjectField(receiverList, "app")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val procState = SystemState.extractProcessStateOrNull(appProcessRecord)
                ?: return@HookSpec param.invokeOriginal()

            val prefs = PrefReader.systemPrefs
            if (procState.isSystemApp ||
                procState.packageName in prefs.whiteAppSet ||
                SystemState.greezeService == null ||
                !SystemState.isUidFrozen(procState.uid)
            ) {
                return@HookSpec param.invokeOriginal()
            }

            Logger.info("修改 ${procState.processName} ,跳过广播")
            SKIP_FROZEN_BROADCAST_REASON
        }
    )

    /**
     * S10（PA(0)）：BroadcastQueue#deliverToRegisteredReceiverLocked(BroadcastRecord, BroadcastFilter, boolean, int)
     * 门控：`SDK_INT <= 33`（Android 13 旧广播管线，与 S9 严格二选一安装）。
     */
    fun buildS10BroadcastQueueDeliverHook(): HookSpec = HookSpec(
        id = "S10",
        name = "BroadcastQueue#deliverToRegisteredReceiverLocked",
        targetClass = CLASS_BROADCAST_QUEUE,
        methodName = "deliverToRegisteredReceiverLocked",
        paramTypes = listOf(
            CLASS_BROADCAST_RECORD,
            CLASS_BROADCAST_FILTER,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ),
        mode = HookMode.REPLACE,
        gate = { !VersionGate.isSdkAfter33 },
        successLog = "监听广播成功",
        replace = { param ->
            if (!SystemState.isGreezeReady) {
                return@HookSpec param.invokeOriginal()
            }
            val broadcastRecord = param.args.getOrNull(0)
            val filter = param.args.getOrNull(1) ?: return@HookSpec param.invokeOriginal()
            val receiverList = try {
                XReflect.getObjectField(filter, "receiverList")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val appProcessRecord = try {
                XReflect.getObjectField(receiverList, "app")
            } catch (_: Throwable) {
                null
            } ?: return@HookSpec param.invokeOriginal()

            val procState = SystemState.extractProcessStateOrNull(appProcessRecord)
                ?: return@HookSpec param.invokeOriginal()

            val prefs = PrefReader.systemPrefs
            if (procState.isSystemApp || procState.packageName in prefs.whiteAppSet) {
                return@HookSpec param.invokeOriginal()
            }

            procState.lock.lock()
            try {
                if (SystemState.greezeService != null && SystemState.isUidFrozen(procState.uid)) {
                    XReflect.setObjectField(receiverList, "app", null)
                    if (prefs.isLog) {
                        val action = try {
                            val intent = XReflect.getObjectField(broadcastRecord, "intent") as? Intent
                            intent?.action ?: "unknown"
                        } catch (_: Throwable) {
                            "unknown"
                        }
                        Logger.info("屏蔽进程[${procState.processName}]冻结时收到的广播[$action]")
                    }
                }
                param.invokeOriginal()
            } finally {
                procState.lock.unlock()
            }
        }
    )

    /**
     * S11（PA(9)）：ActivityManagerService#setSystemProcess()
     * 捕获 AMS 实例句柄，供 `SystemState.systemContext` 提取系统 Context。
     */
    fun buildS11AmsSetSystemProcessHook(): HookSpec = HookSpec(
        id = "S11",
        name = "ActivityManagerService#setSystemProcess",
        targetClass = CLASS_AMS,
        methodName = "setSystemProcess",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        successLog = "获取安卓核心服务成功",
        before = { param ->
            if (param.thisObject != null) {
                SystemState.amsInstance = param.thisObject
            }
        }
    )

    /**
     * S12（PA(10)）：DeviceIdleController#onStart()
     * 捕获 Doze 控制器句柄并初始化 DeviceIdleController$Shell，同时同步启动期省电白/黑名单与电量观测。
     */
    fun buildS12DeviceIdleOnStartHook(): HookSpec = HookSpec(
        id = "S12",
        name = "DeviceIdleController#onStart",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "onStart",
        paramTypes = emptyList(),
        mode = HookMode.AFTER,
        successLog = "获取IDLE控制服务成功",
        after = { param ->
            val controller = param.thisObject ?: return@HookSpec
            if (SystemState.deviceIdleController == null) {
                synchronized(BroadcastAndDozeHooks::class.java) {
                    if (SystemState.deviceIdleController == null) {
                        SystemState.deviceIdleController = controller
                        initIdleShell(controller)
                    }
                }
            }
            applyStartupSync()
        }
    )

    /**
     * S13（PA(11)）：DeviceIdleController#motionLocked()
     * 运动不再把手机踢出 Doze（将 `mNotMoving` 置为 true 并吞掉原方法）。
     */
    fun buildS13DeviceIdleMotionLockedHook(): HookSpec = HookSpec(
        id = "S13",
        name = "DeviceIdleController#motionLocked",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "motionLocked",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        successLog = "获取设备运动状态监测服务成功",
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (prefs.disableMotionListener) {
                if (prefs.dozeLog) {
                    Logger.doze(Logger.Level.INFO, "[Doze]:监测到设备运动，但是阻止唤醒Doze")
                }
                param.result = null
                val controller = SystemState.deviceIdleController ?: param.thisObject
                if (controller != null) {
                    try {
                        XReflect.setBooleanField(controller, "mNotMoving", true)
                    } catch (_: Throwable) {
                    }
                }
            } else if (prefs.dozeLog) {
                Logger.doze(Logger.Level.INFO, "[Doze]:监测到设备运动")
            }
        }
    )

    /**
     * S14（PA(12)）：DeviceIdleController#moveToStateLocked(int state, String reason)
     * 按 `enableDoze` 与 `skipStateCheck` 双向接管 Doze 状态机：
     * - before：当进入 `IDLE_PENDING(2)` 时直接改写目标状态为 `IDLE_MAINTENANCE(6)`，跳过前置传感检查。
     * - after：进入 `IDLE(5)` 或 `IDLE_MAINTENANCE(6)` 时取消息屏延时 Alarm 并记录 `isInDeepIdle = true`。
     */
    fun buildS14DeviceIdleMoveToStateHook(): HookSpec = HookSpec(
        id = "S14",
        name = "DeviceIdleController#moveToStateLocked",
        targetClass = CLASS_DEVICE_IDLE_CONTROLLER,
        methodName = "moveToStateLocked",
        paramTypes = listOf(Int::class.javaPrimitiveType!!, String::class.java),
        mode = HookMode.BEFORE_AND_AFTER,
        before = { param ->
            if (!SystemState.isGreezeReady) return@HookSpec
            val prefs = PrefReader.systemPrefs
            if (prefs.enableDoze && prefs.skipStateCheck) {
                val state = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
                val reason = (param.args.getOrNull(1) as? String) ?: ""
                if (state == 2 && reason != REASON_XMILLET_FORCE) {
                    param.args[0] = 6
                    val controller = SystemState.deviceIdleController ?: param.thisObject
                    if (controller != null) {
                        try {
                            XReflect.callMethod(controller, "resetIdleManagementLocked")
                        } catch (_: Throwable) {
                        }
                    }
                    if (prefs.dozeLog) {
                        param.args[1] = REASON_XMILLET_SKIP
                        Logger.doze(Logger.Level.INFO, "[Doze]:状态IDLE_PENDING,跳过其他进入IDLE")
                    }
                }
            }
        },
        after = { param ->
            val state = (param.args.getOrNull(0) as? Int) ?: return@HookSpec
            val reason = (param.args.getOrNull(1) as? String) ?: ""
            val prefs = PrefReader.systemPrefs

            if (prefs.enableDoze) {
                if (state == 5 || state == 6) {
                    val ctx = SystemState.systemContext
                    if (ctx != null) {
                        try {
                            ctx.getSystemService(AlarmManager::class.java)?.cancel(deepDozeAlarmListener)
                        } catch (_: Throwable) {
                        }
                    }
                    isInDeepIdle = true
                } else {
                    isInDeepIdle = false
                }
            }

            if (prefs.dozeLog) {
                val stateName = when (state) {
                    0 -> "ACTIVE"
                    1 -> "INACTIVE"
                    2 -> "IDLE_PENDING"
                    3 -> "SENSING"
                    4 -> "LOCATING"
                    5 -> "IDLE"
                    6 -> "IDLE_MAINTENANCE"
                    7 -> "QUICK_DOZE_DELAY"
                    else -> "UNKNOWN($state)"
                }
                Logger.doze(Logger.Level.WARN, "[Doze状态监测]:由于$reason,切换到:$stateName")
            }
        }
    )

    // ==================== 亮灭屏广播、DeepDoze Shell 与省电白名单同步 ====================

    private fun initIdleShell(controller: Any) {
        try {
            val shellClass = XReflect.findClassIfExists(
                CLASS_DEVICE_IDLE_SHELL,
                controller.javaClass.classLoader
            ) ?: return
            idleShellInstance = XReflect.newInstance(shellClass, controller)
        } catch (t: Throwable) {
            Logger.error("初始化Idle出错", t)
        }
    }

    private fun execIdleShellCommand(vararg args: String): Pair<Int, String> {
        val controller = SystemState.deviceIdleController ?: return -1 to "controller is null"
        val shell = idleShellInstance ?: return -1 to "shell is null"
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        synchronized(idleShellLock) {
            return try {
                XReflect.setObjectField(shell, "mOutPrintWriter", pw)
                XReflect.setObjectField(shell, "mArgs", arrayOf(*args))
                XReflect.setIntField(shell, "mArgPos", 1)
                val code = (XReflect.callMethod(controller, "onShellCommand", shell, args[0]) as? Int) ?: -1
                code to sw.toString()
            } catch (t: Throwable) {
                -1 to (t.message ?: "error")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerScreenStatusReceiver(context: Context) {
        if (hotReloading) return
        synchronized(receiverLifecycleLock) {
            if (hotReloading || screenReceiverRegistered) return
            val targetCtx = context.applicationContext ?: context
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        handleScreenBroadcast(intent?.action)
                    }
                }
                targetCtx.registerReceiver(receiver, filter)
                screenReceiver = receiver
                screenReceiverContext = targetCtx
                screenReceiverRegistered = true
                Logger.alwaysWarn("registerReceiverScreenStatus")
            } catch (t: Throwable) {
                Logger.error("registerScreenStatusReceiver failed", t)
            }
        }
    }

    private fun handleScreenBroadcast(action: String?) {
        if (hotReloading || action == null || !SystemState.isGreezeReady) return
        val prefs = PrefReader.systemPrefs
        val handler = ensureScreenHandler() ?: return
        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                if (!SystemState.hasAurogonIsUidValid && prefs.freezeDualApp) {
                    handler.removeCallbacks(dualScreenOffPostRunnable)
                    val ctx = SystemState.systemContext
                    var alarmScheduled = false
                    if (ctx != null) {
                        try {
                            val am = ctx.getSystemService(AlarmManager::class.java)
                            am?.cancel(dualScreenOffFreezeListener)
                            // Android 派发 ACTION_SCREEN_OFF 时部分后台进程短暂抬升到 adj=0 约 3s，使用 wakeup 闹钟避开瞬态窗口
                            am?.setExact(
                                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                SystemClock.elapsedRealtime() + 4_500L,
                                "XMillet.DualScrOff",
                                dualScreenOffFreezeListener,
                                handler
                            )
                            alarmScheduled = am != null
                        } catch (t: Throwable) {
                            Logger.warn("dualScrOff setExact 回退 postDelayed: ${t.javaClass.simpleName}:${t.message}")
                        }
                    }
                    if (!alarmScheduled) {
                        handler.postDelayed(dualScreenOffPostRunnable, 4_500L)
                    }
                } else {
                    Logger.info(
                        "dualScrOff 未触发: hasUidValid=${SystemState.hasAurogonIsUidValid}, freezeDual=${prefs.freezeDualApp}"
                    )
                }
                if (prefs.isLog || prefs.dozeLog) {
                    val batteryPct = queryCurrentBatteryPercent()
                    Logger.doze(Logger.Level.WARN, "========[屏幕关闭,电量$batteryPct%]========")
                } else if (prefs.batteryChangeObserve && prefs.logToLocal) {
                    Logger.battery(Logger.Level.WARN, "========[屏幕关闭]========")
                }
                if (!isInDeepIdle && prefs.enableDoze) {
                    val ctx = SystemState.systemContext ?: return
                    try {
                        ctx.getSystemService(AlarmManager::class.java)?.set(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + 10_000L,
                            "XMillet.Screen",
                            deepDozeAlarmListener,
                            handler
                        )
                    } catch (_: Throwable) {
                    }
                }
            }

            Intent.ACTION_SCREEN_ON -> {
                handler.removeCallbacks(dualScreenOffPostRunnable)
                SystemState.systemContext?.let { ctx ->
                    try {
                        ctx.getSystemService(AlarmManager::class.java)?.cancel(dualScreenOffFreezeListener)
                    } catch (_: Throwable) {
                    }
                }
                if (isInDeepIdle) {
                    val ctx = SystemState.systemContext
                    if (ctx != null) {
                        try {
                            ctx.getSystemService(AlarmManager::class.java)?.cancel(deepDozeAlarmListener)
                        } catch (_: Throwable) {
                        }
                    }
                    synchronized(idleShellLock) {
                        val (code, _) = execIdleShellCommand("unforce")
                        if (code == 0) {
                            Logger.alwaysWarn("退出深度睡眠")
                        }
                    }
                }
                if (prefs.disableScreenOnThaw) {
                    val protectedPkgs = buildList {
                        ActiveImeTracker.resolveDefaultImePackage(SystemState.systemContext)?.let(::add)
                        addAll(ActiveImeTracker.resolveAccessibilityPackages(SystemState.systemContext))
                    }
                    for (svcPkg in protectedPkgs.distinct()) {
                        if (svcPkg in prefs.blockThawSet ||
                            svcPkg in prefs.ignoreNetworkWakeSet ||
                            !ActiveImeTracker.isProtectedSystemServiceApp(SystemState.systemContext, svcPkg, prefs)
                        ) {
                            continue
                        }
                        val svcUid = SystemState.getPackageUid(svcPkg)
                        if (svcUid > 0 && SystemState.isUidFrozen(svcUid)) {
                            handler.post {
                                SystemState.greezeService?.let { greeze ->
                                    runCatching { XReflect.callMethod(greeze, "thawUid", svcUid, 1000, "screen on") }
                                }
                            }
                        }
                    }
                }
                if (prefs.isLog || prefs.dozeLog) {
                    val batteryPct = queryCurrentBatteryPercent()
                    Logger.doze(Logger.Level.WARN, "========[屏幕开启,电量$batteryPct%]========")
                } else if (prefs.batteryChangeObserve && prefs.logToLocal) {
                    Logger.battery(Logger.Level.WARN, "========[屏幕开启]========")
                }
            }
        }
    }

    private fun queryCurrentBatteryPercent(): Int {
        val ctx = SystemState.systemContext ?: return -1
        return try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100f).toInt()
            } else {
                -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    // ==================== 启动期与热更新联动 ====================

    /**
     * 启动期全量配置同步：
     * 1. 刷新 AurogonImmobulusMode.mMessageApp
     * 2. 若开启 batteryChangeObserve，启动电量变化广播监听
     *
     * 省电白/黑名单不在这里处理：现在由 `BatteryPolicyApplier` 按应用逐个写
     * `DeviceIdleController` / `AppOps` / PowerKeeper 配置库 / 系统无限制名单，不再维护自建集合。
     */
    fun applyStartupSync() {
        if (!SystemState.isGreezeReady) return
        SystemState.systemContext?.let { registerScreenStatusReceiver(it) }
        SystemState.syncMessageAppsToAurogon()
        if (PrefReader.systemPrefs.isLog || PrefReader.systemPrefs.batteryChangeObserve) {
            updateBatteryObservation(true)
        }
    }

    private val batteryChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
            if (pct < 0 || pct == lastBatteryPercent) return

            val now = System.currentTimeMillis()
            if (lastBatteryPercent != -1 && lastBatteryTimestamp > 0) {
                val elapsedMs = now - lastBatteryTimestamp
                val durationStr = formatDuration(elapsedMs)
                val diff = lastBatteryPercent - pct
                if (diff > 0) {
                    Logger.battery(Logger.Level.INFO, "电量统计:消耗 $lastBatteryPercent% --> $pct% ,耗时$durationStr")
                } else if (diff < 0) {
                    Logger.battery(Logger.Level.INFO, "电量统计:充电 $lastBatteryPercent% --> $pct% ,耗时$durationStr")
                }
            }
            lastBatteryPercent = pct
            lastBatteryTimestamp = now
        }
    }

    fun updateBatteryObservation(enabled: Boolean) {
        synchronized(receiverLifecycleLock) {
            if (enabled && !hotReloading && !batteryReceiverRegistered) {
                val ctx = SystemState.systemContext ?: return
                try {
                    val targetCtx = ctx.applicationContext ?: ctx
                    targetCtx.registerReceiver(batteryChangeReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    batteryReceiverContext = targetCtx
                    batteryReceiverRegistered = true
                    Logger.battery(Logger.Level.INFO, "开启电量记录成功")
                } catch (t: Throwable) {
                    Logger.error("updateBatteryObservation($enabled) failed", t)
                }
            } else if (!enabled && batteryReceiverRegistered) {
                val ctx = batteryReceiverContext ?: SystemState.systemContext ?: return
                try {
                    ctx.unregisterReceiver(batteryChangeReceiver)
                    batteryReceiverContext = null
                    batteryReceiverRegistered = false
                    Logger.battery(Logger.Level.INFO, "关闭电量记录成功")
                } catch (t: Throwable) {
                    Logger.error("updateBatteryObservation($enabled) failed", t)
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        if (totalSec < 60) return "${totalSec}秒"
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours < 1) {
            "${minutes}分${seconds}秒"
        } else {
            "${hours}小时${minutes}分钟"
        }
    }
}
