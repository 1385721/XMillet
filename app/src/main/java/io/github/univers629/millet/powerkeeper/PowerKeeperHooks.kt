package io.github.univers629.millet.powerkeeper

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.univers629.millet.core.ModernHookInstaller
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.core.HookInstallRegistry
import io.github.univers629.millet.core.HookMode
import io.github.univers629.millet.core.HookSpec
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefGroup
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.UnifiedHookParam
import io.github.univers629.millet.core.VersionGate
import io.github.univers629.millet.ipc.MilletIpcContract
import io.github.univers629.millet.ipc.PowerKeeperBinderClient

/**
 * 作用域 `"com.miui.powerkeeper"` 全部 20 个安装点完整实现（P1–P20）。
 *
 * 安装点分母核对（共 20 个安装点）：
 * - P1 : PowerKeeperApplication#onCreate() (before) —— 建立跨进程 Binder 双向链路
 * - P2 : MilletPolicy#<init>(Context) (after) —— 捕获实例、清 musicApp、union 重放 6 张内部白名单
 * - P3 : MilletPolicy#dealCloudWhiteList() (replace) —— 整方法替换直接返回 null（云白名单第一刀）
 * - P4 : MilletPolicy#onCloudWhiteListChanged() (before, 仅 OS1.0) —— 置 null 拦截热更新（云白名单第二刀）
 * - P5 : MilletPolicy#dealNoRestrictApp() (after) —— 重放白名单 + 推送 MILLET_NO_RESTRICT_APP 给 system_server
 * - P6 : MilletPolicy#dealSleepModeWhiteList(List, boolean) (before) —— 无条件置 null（真机与 PA:111 实为 MilletPolicy）
 * - P7 : CommonAdapter#addPowerSaveWhitelistApps(IDeviceIdleController, String[]) (before) —— 只拦不改
 * - P8 : CommonAdapter#removePowerSaveWhitelistApps(IDeviceIdleController, String[]) (before) —— 只拦不改
 * - P9 : MilletHandler#handleMessage(Message) (before) —— what∈{13,20} 拦同步唤醒与音量键解冻
 * - P10: MilletBrightHandler#handleMessage(Message) (before) —— what==13 拦同步、what==16 拦充电全解冻
 * - P11: PowerCheckerController#clearApp() (before) —— disableKill 置 null
 * - P12: PowerStateMachine#clearAppWhenScreenOffTimeOut() (before) —— disableKill 置 null
 * - P13: PowerStateMachine#clearAppWhenScreenOffTimeOutInNight() (before) —— disableKill 置 null
 * - P14: PowerStateMachine#clearUnactiveApps(Context) (before) —— disableKill 置 null
 * - P15: BluetoothEvent#<init>(Context, Handler) (before) —— 缓存反射句柄
 * - P16: BrightController#<init>(Context, FrozenAppController) (before) —— 缓存反射句柄
 * - P17: FreezeBinder#<init>() (before) —— 缓存反射句柄
 * - P18: PowerKeeperManager#<init>(Context) (before) —— 缓存反射句柄
 * - P19: ProcessObserver#<init>(Context, EventsAggregator) (before) —— 缓存反射句柄
 * - P20: BrightScenarioBase#<init>(MilletPolicy, Context, boolean, BrightController) (after) —— 缓存句柄并重建 preloadWhiteApps
 */
object PowerKeeperHooks {

    private const val CLASS_PK_APPLICATION = "com.miui.powerkeeper.PowerKeeperApplication"
    private const val CLASS_MILLET_POLICY = "com.miui.powerkeeper.millet.MilletPolicy"
    private const val CLASS_COMMON_ADAPTER = "com.miui.powerkeeper.utils.CommonAdapter"
    private const val CLASS_IDEVICE_IDLE_CONTROLLER = "android.os.IDeviceIdleController"
    private const val CLASS_MILLET_HANDLER = "com.miui.powerkeeper.millet.MilletHandler"
    private const val CLASS_MILLET_BRIGHT_HANDLER = "com.miui.powerkeeper.millet.bright.MilletBrightHandler"
    private const val CLASS_POWER_CHECKER_CONTROLLER = "com.miui.powerkeeper.powerchecker.PowerCheckerController"
    private const val CLASS_POWER_STATE_MACHINE = "com.miui.powerkeeper.statemachine.PowerStateMachine"
    private const val CLASS_BLUETOOTH_EVENT = "com.miui.powerkeeper.millet.BluetoothEvent"
    private const val CLASS_BRIGHT_CONTROLLER = "com.miui.powerkeeper.millet.bright.BrightController"
    private const val CLASS_FROZEN_APP_CONTROLLER = "com.miui.powerkeeper.controller.FrozenAppController"
    private const val CLASS_FREEZE_BINDER = "com.miui.powerkeeper.millet.FreezeBinder"
    private const val CLASS_POWER_KEEPER_MANAGER = "com.miui.powerkeeper.PowerKeeperManager"
    private const val CLASS_PROCESS_OBSERVER = "com.miui.powerkeeper.utils.ProcessObserver"
    private const val CLASS_EVENTS_AGGREGATOR = "com.miui.powerkeeper.event.EventsAggregator"
    private const val CLASS_BRIGHT_SCENARIO_BASE = "com.miui.powerkeeper.millet.bright.BrightScenarioBase"
    private const val CLASS_PROCESS_MANAGER = "miui.process.ProcessManager"
    private const val CLASS_PROCESS_CONFIG = "miui.process.ProcessConfig"
    private const val CLASS_KILL_PROCESS_CONTROLLER = "com.miui.powerkeeper.controller.KillProcessController"
    private const val CLASS_ACTIVE_STATE_CONTROLLER = "com.miui.powerkeeper.controller.ActiveStateController"
    private const val CLASS_AUDIO_DISGUISE_CONTROLLER = "com.miui.powerkeeper.controller.AudioDisguiseController"
    private const val CLASS_GLOBAL_FEATURE_HELPER = "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper"

    /**
     * 云端名单里需要「只拦不改」的键。
     *
     * 澎湃 3.0 的 `DeviceIdleController#getDozeWhiteListAppFromNewCloud()` 会把 `doze_whitelist_apps`
     * 塞进 Bundle 调 `GlobalFeatureConfigureHelper#updateGlobalConfigure`，`frozenNew_whitelist`
     * 与 `bright_frozen` 走同一条路 —— 这三个键合起来就是旧版 P3/P4/P6 拦下的那批名单。
     */
    private val CLOUD_WHITE_LIST_KEYS = setOf(
        "dozeWhiteListApps",
        "doze_whitelist_apps",
        "frozenNew_whitelist",
        "bright_frozen"
    )

    // MilletHandler / MilletBrightHandler 消息码（05-powerkeeper-truth.md §4）
    private const val MSG_WHAT_QUERY_FROZEN_UID = 13
    private const val MSG_WHAT_CHARGING_CHANGED = 16

    /**
     * 在 `com.miui.powerkeeper` 进程按原规格顺序安装全部 P1–P20 Hook，并注册热更新回调。
     */
    private fun registerPrefReloadCallback() {
        PrefReader.onPowerKeeperGroupReloaded = { _, group, _ ->
            if (group == PrefGroup.APP_SETS) {
                PowerKeeperState.applyMessageAndWhiteLists()
                PowerKeeperState.applyPreloadWhiteApps()
            }
        }
    }

    private fun buildAllSpecs(classLoader: ClassLoader): List<HookSpec> {
        val candidates = listOf(
            buildP1ApplicationOnCreateHook(),
            buildP6DealSleepModeWhiteListHook(),
            buildP27CloudWhiteListWriteHook(),
            buildP7AddPowerSaveWhitelistHook(),
            buildP8RemovePowerSaveWhitelistHook(),
            buildP2MilletPolicyInitHook(),
            buildP3DealCloudWhiteListHook(),
            buildP4OnCloudWhiteListChangedHook(),
            buildP9MilletHandlerHook(),
            buildP10MilletBrightHandlerHook(),
            buildP5DealNoRestrictAppHook(),
            buildP28ActiveNoRestrictHook(),
            buildP11ClearAppHook(),
            buildP13ClearAppInNightHook(),
            buildP12ClearAppScreenOffHook(),
            buildP14ClearUnactiveAppsHook(),
            buildProcessManagerKillHook(),
            buildP22KillPolicyHook(),
            buildP23FrozenDecisionHook(),
            buildP24NoChargeThawHook(),
            buildP25AutoKillGuardHook(),
            buildP26AudioKillGuardHook(),
            buildP15BluetoothEventInitHook(),
            buildP19ProcessObserverInitHook(),
            buildP20BrightScenarioBaseInitHook(),
            buildP17FreezeBinderInitHook(),
            buildP18PowerKeeperManagerInitHook(),
            buildP16BrightControllerInitHook()
        )
        // 按当前 ROM 版本的能力过滤点位：目标类/方法在本版本不存在时跳过，
        // 既不会让安装器抛 ClassNotFound/NoSuchMethod，也不会把「本版本没有的功能」误报成 Hook 失败。
        // filterInstallable 内部会把被跳过的点位记账到 HookInstallRegistry，供界面展示真实覆盖。
        val installable = RomCapabilities.filterInstallable(candidates, classLoader, "电量与性能侧")
        Logger.alwaysWarn(RomCapabilities.describeHost(PowerKeeperState.applicationContext, classLoader))
        return installable
    }

    /**
     * 在 com.miui.powerkeeper 进程通过 Modern LibXposed (API 102) 安装全部 P1–P20 Hook。
     */
    fun installAllModern(xposed: XposedInterface, classLoader: ClassLoader) {
        registerPrefReloadCallback()
        ModernHookInstaller.installAll(xposed, classLoader, buildAllSpecs(classLoader))
        logInstallSummary()
    }

    /** 安装结果落一条日志：既方便跨版本排查「少了哪几条」，也是界面徽标的数据来源。 */
    private fun logInstallSummary() {
        Logger.alwaysWarn("Hook 安装结果[电量与性能侧]: ${HookInstallRegistry.summary()}")
    }

    /**
     * 把到 system_server 的 Binder 链路补回来。
     *
     * P1 只在 `PowerKeeperApplication#onCreate` 触发一次，所以热重载后旧链路会随旧句柄一起
     * 消失、而 P1 又不会再跑；不补的话新一代电量侧 Hook 全部照常工作，但 App 会一直显示
     * 「未连接电量与性能」。`bindFromPowerKeeper` 按客户端名覆盖注册，重复调用安全。
     */
    fun ensureBinderLink() {
        val context = PowerKeeperState.applicationContext
            ?: PowerKeeperState.reacquireApplicationContext()
            ?: return
        if (PowerKeeperBinderClient.bindFromPowerKeeper(context)) {
            Logger.alwaysInfo("电量与性能侧：Binder 链路已重新注册")
        }
    }

    /**
     * P1（MSH(4)）：PowerKeeperApplication#onCreate()
     * 建立到 system_server 的 Binder 双向链路，上报无限制应用列表与设备码。
     */
    private fun buildP1ApplicationOnCreateHook(): HookSpec = HookSpec(
        id = "P1",
        name = "PowerKeeperApplication#onCreate",
        targetClass = CLASS_PK_APPLICATION,
        methodName = "onCreate",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        before = { param ->
            Logger.alwaysWarn(VersionGate.buildDeviceEnvSummary())
            val appCtx = param.thisObject as? Context ?: return@HookSpec
            PowerKeeperState.applicationContext = appCtx

            if (!PowerKeeperBinderClient.bindFromPowerKeeper(appCtx)) {
                return@HookSpec
            }

            // 读取并上报 MILLET_NO_RESTRICT_APP（只读不回写 Settings.System，避免竞态）
            try {
                val noRestrict = Settings.System.getString(
                    appCtx.contentResolver,
                    MilletIpcContract.SETTINGS_KEY_NO_RESTRICT_APP
                )
                PowerKeeperBinderClient.reportNoRestrictApps(noRestrict)
            } catch (t: Throwable) {
                Logger.error("PK获取无限制应用异常", t)
            }
        }
    )

    /**
     * P2（PA(22)）：MilletPolicy#<init>(Context)
     * - before：提前缓存实例、清空 `musicApp` 静态列表并预设静态 `MESSAGE_APPS` / `BRIGHT_WHITE_APPS`（供构造器内 `checkMessageApp` 与 `getExternalApps` 读取）
     * - after：在构造器填充完内置集合后，以 union 方式合并 6 组白/黑名单（修正缺陷 #3）。
     */
    private fun buildP2MilletPolicyInitHook(): HookSpec = HookSpec(
        id = "P2",
        name = "MilletPolicy#<init>",
        targetClass = CLASS_MILLET_POLICY,
        methodName = null,
        paramTypes = listOf(Context::class.java),
        mode = HookMode.BEFORE_AND_AFTER,
        before = { param ->
            val policy = param.thisObject ?: return@HookSpec
            if (PowerKeeperState.milletPolicyInstance == null) {
                PowerKeeperState.milletPolicyInstance = policy
            }
            PowerKeeperState.clearMusicApps(policy)
            PowerKeeperState.applyMessageAndWhiteLists()
        },
        after = { param ->
            val policy = param.thisObject ?: return@HookSpec
            if (PowerKeeperState.milletPolicyInstance == null) {
                PowerKeeperState.milletPolicyInstance = policy
            }
            PowerKeeperState.clearMusicApps(policy)
            PowerKeeperState.applyMessageAndWhiteLists()
        }
    )

    /**
     * P3（FreezeDecision(0)）：MilletPolicy#dealCloudWhiteList()
     * 【云白名单第一刀】：整方法替换（REPLACE），不调用原实现，直接返回 null，使 `mCloudWhiteList` 永远为空。
     */
    private fun buildP3DealCloudWhiteListHook(): HookSpec = HookSpec(
        id = "P3",
        name = "MilletPolicy#dealCloudWhiteList",
        targetClass = CLASS_MILLET_POLICY,
        methodName = "dealCloudWhiteList",
        paramTypes = emptyList(),
        mode = HookMode.REPLACE,
        replace = {
            if (PrefReader.powerKeeperPrefs.isLog) {
                Logger.warn("拦截云白名单更新")
            }
            null
        }
    )

    /**
     * P4（FreezeDecision(1)）：MilletPolicy#onCloudWhiteListChanged()
     * 【云白名单第二刀】：仅在 HyperOS 1.0（`ro.mi.os.version.name == "OS1.0"`）安装，拦截运行期云控热更新回调。
     */
    private fun buildP4OnCloudWhiteListChangedHook(): HookSpec = HookSpec(
        id = "P4",
        name = "MilletPolicy#onCloudWhiteListChanged",
        targetClass = CLASS_MILLET_POLICY,
        methodName = "onCloudWhiteListChanged",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        gate = { VersionGate.isHyperOs1 },
        before = { param ->
            Logger.alwaysInfo("拦截云名单变化")
            param.result = null
        }
    )

    private fun reportCurrentNoRestrictApps() {
        val ctx = PowerKeeperState.applicationContext ?: PowerKeeperState.reacquireApplicationContext() ?: return
        try {
            val noRestrict = Settings.System.getString(
                ctx.contentResolver,
                MilletIpcContract.SETTINGS_KEY_NO_RESTRICT_APP
            )
            PowerKeeperBinderClient.reportNoRestrictApps(noRestrict)
        } catch (t: Throwable) {
            Logger.error("dealNoRestrictApp 同步无限制应用失败", t)
        }
    }

    /**
     * P5（MilletBridge）：MilletPolicy#dealNoRestrictApp()
     * 当用户在系统设置中调整「省电策略 -> 无限制」应用后，重放模块白名单并同步 `MILLET_NO_RESTRICT_APP` 到 `system_server`。
     */
    private fun buildP5DealNoRestrictAppHook(): HookSpec = HookSpec(
        id = "P5",
        name = "MilletPolicy#dealNoRestrictApp",
        targetClass = CLASS_MILLET_POLICY,
        methodName = "dealNoRestrictApp",
        paramTypes = emptyList(),
        mode = HookMode.AFTER,
        after = {
            PowerKeeperState.applyMessageAndWhiteLists()
            reportCurrentNoRestrictApps()
        }
    )

    /**
     * P28（澎湃 3.0 / Android 16 起）：ActiveStateController#dealNoRestrictApp()
     *
     * 澎湃 3.0 移除 `MilletPolicy` 后，系统改由 `ActiveStateController` 根据
     * `UserConfigureHelper.getNoRestrictApps` 直接覆写 `MILLET_NO_RESTRICT_APP`。
     * 每次宿主重建完成后把快照报回 system_server，由 system_server 补回模块白名单并更新缓存。
     */
    private fun buildP28ActiveNoRestrictHook(): HookSpec = HookSpec(
        id = "P28_NO_RESTRICT",
        name = "ActiveStateController#dealNoRestrictApp",
        targetClass = CLASS_ACTIVE_STATE_CONTROLLER,
        methodName = "dealNoRestrictApp",
        paramTypes = emptyList(),
        mode = HookMode.AFTER,
        after = { param ->
            if (PowerKeeperState.activeStateControllerInstance == null && param.thisObject != null) {
                PowerKeeperState.activeStateControllerInstance = param.thisObject
            }
            reportCurrentNoRestrictApps()
        }
    )

    /**
     * P6（PA(26)）：MilletPolicy#dealSleepModeWhiteList(List, boolean)
     * 【Doze / 睡眠模式白名单只拦不改 #1】：
     * 真机核验（PrefsAccessor.java:111 与 MilletPolicy.java:329）：该方法位于 `MilletPolicy` 而非 `CommonAdapter`。
     * 无条件置 null，阻止睡眠模式改写免冻结与省电名单。
     */
    private fun buildP6DealSleepModeWhiteListHook(): HookSpec = HookSpec(
        id = "P6",
        name = "MilletPolicy#dealSleepModeWhiteList",
        targetClass = CLASS_MILLET_POLICY,
        methodName = "dealSleepModeWhiteList",
        paramTypes = listOf(List::class.java, Boolean::class.javaPrimitiveType!!),
        mode = HookMode.BEFORE,
        before = { param ->
            param.result = null
            if (PrefReader.powerKeeperPrefs.isLog) {
                Logger.warn("屏蔽millet电池优化名单调整")
            }
        }
    )

    /**
     * P7（PA(25)）：CommonAdapter#addPowerSaveWhitelistApps(IDeviceIdleController, String[])
     * 【Doze 省电白名单只拦不改 #2】：`blockAddPowerSaveWhitelistApps` 为真时直接置 null。
     */
    private fun buildP7AddPowerSaveWhitelistHook(): HookSpec = HookSpec(
        id = "P7",
        name = "CommonAdapter#addPowerSaveWhitelistApps",
        targetClass = CLASS_COMMON_ADAPTER,
        methodName = "addPowerSaveWhitelistApps",
        paramTypes = listOf(CLASS_IDEVICE_IDLE_CONTROLLER, Array<String>::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.blockAddPowerSaveWhitelistApps) {
                Logger.warn("禁止电量与性能添加电池未优化名单")
                param.result = null
            }
        }
    )

    /**
     * P8（PA(27)）：CommonAdapter#removePowerSaveWhitelistApps(IDeviceIdleController, String[])
     * 【Doze 省电白名单只拦不改 #3】：与 P7 共用 `blockAddPowerSaveWhitelistApps` 开关，为真时直接置 null。
     */
    private fun buildP8RemovePowerSaveWhitelistHook(): HookSpec = HookSpec(
        id = "P8",
        name = "CommonAdapter#removePowerSaveWhitelistApps",
        targetClass = CLASS_COMMON_ADAPTER,
        methodName = "removePowerSaveWhitelistApps",
        paramTypes = listOf(CLASS_IDEVICE_IDLE_CONTROLLER, Array<String>::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.blockAddPowerSaveWhitelistApps) {
                Logger.warn("禁止电量与性能移除电池未优化名单")
                param.result = null
            }
        }
    )

    /**
     * P9（PA(29)）：MilletHandler#handleMessage(Message)
     * `what == 13`（冻结状态查询入队）：若目标包名在 `forbidSyncBinderSet` 中，从 `queryList` 移除该 uid 并置 null。
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildP9MilletHandlerHook(): HookSpec = HookSpec(
        id = "P9",
        name = "MilletHandler#handleMessage",
        targetClass = CLASS_MILLET_HANDLER,
        methodName = "handleMessage",
        paramTypes = listOf(Message::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            val msg = param.args.getOrNull(0) as? Message ?: return@HookSpec
            val what = msg.what
            if (what != MSG_WHAT_QUERY_FROZEN_UID) {
                return@HookSpec
            }

            val data = msg.data
            val uid = if (msg.arg1 != 0) msg.arg1 else (data?.getInt("uid") ?: 0)
            val prefs = PrefReader.powerKeeperPrefs

            val pkg = PowerKeeperState.getPackageNameForUid(uid)
            if (pkg != null && pkg in prefs.forbidSyncBinderSet) {
                try {
                    val queryList = XReflect.getObjectField(param.thisObject, "queryList") as? MutableSet<Int>
                    queryList?.remove(uid)
                } catch (_: Throwable) {
                }
                param.result = null
                return@HookSpec
            }
        }
    )

    /**
     * P10（PA(28)）：MilletBrightHandler#handleMessage(Message)
     * 1. `what == 16`（插上充电器全量解冻）：若 `disableCharge` 为真，置 null。
     * 2. `what == 13`（冻结状态查询入队）：若目标包名在 `forbidSyncBinderSet` 中，从 `queryList` 移除该 uid 并置 null。
     */
    @Suppress("UNCHECKED_CAST")
    private fun buildP10MilletBrightHandlerHook(): HookSpec = HookSpec(
        id = "P10",
        name = "MilletBrightHandler#handleMessage",
        targetClass = CLASS_MILLET_BRIGHT_HANDLER,
        methodName = "handleMessage",
        paramTypes = listOf(Message::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            val msg = param.args.getOrNull(0) as? Message ?: return@HookSpec
            val what = msg.what
            val prefs = PrefReader.powerKeeperPrefs

            when (what) {
                MSG_WHAT_CHARGING_CHANGED -> {
                    if (prefs.disableCharge) {
                        param.result = null
                    }
                }

                MSG_WHAT_QUERY_FROZEN_UID -> {
                    val uid = if (msg.arg1 != 0) msg.arg1 else (msg.data?.getInt("uid") ?: 0)
                    val pkg = PowerKeeperState.getPackageNameForUid(uid)
                    if (pkg != null && pkg in prefs.forbidSyncBinderSet) {
                        try {
                            val queryList = XReflect.getObjectField(
                                param.thisObject,
                                "queryList"
                            ) as? MutableSet<Int>
                            queryList?.remove(uid)
                        } catch (_: Throwable) {
                        }
                        param.result = null
                    }
                }
            }
        }
    )

    // ==================== P11–P14：禁止息屏清后台 4 处拦截 ====================

    /**
     * P11（PA(14)）：PowerCheckerController#clearApp()
     */
    private fun buildP11ClearAppHook(): HookSpec = HookSpec(
        id = "P11",
        name = "PowerCheckerController#clearApp",
        targetClass = CLASS_POWER_CHECKER_CONTROLLER,
        methodName = "clearApp",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.disableKill) {
                param.result = null
                Logger.warn("阻止执行清理后台CA")
            }
        }
    )

    /**
     * P12（PA(15)）：PowerStateMachine#clearAppWhenScreenOffTimeOut()
     */
    private fun buildP12ClearAppScreenOffHook(): HookSpec = HookSpec(
        id = "P12",
        name = "PowerStateMachine#clearAppWhenScreenOffTimeOut",
        targetClass = CLASS_POWER_STATE_MACHINE,
        methodName = "clearAppWhenScreenOffTimeOut",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.disableKill) {
                param.result = null
                Logger.warn("阻止执行清理后台CAWSOTO")
            }
        }
    )

    /**
     * P13（PA(16)）：PowerStateMachine#clearAppWhenScreenOffTimeOutInNight()
     */
    private fun buildP13ClearAppInNightHook(): HookSpec = HookSpec(
        id = "P13",
        name = "PowerStateMachine#clearAppWhenScreenOffTimeOutInNight",
        targetClass = CLASS_POWER_STATE_MACHINE,
        methodName = "clearAppWhenScreenOffTimeOutInNight",
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.disableKill) {
                param.result = null
                Logger.warn("阻止执行清理后台CAWSOTOIN")
            }
        }
    )

    /**
     * P14（PA(17)）：PowerStateMachine#clearUnactiveApps(Context)
     */
    private fun buildP14ClearUnactiveAppsHook(): HookSpec = HookSpec(
        id = "P14",
        name = "PowerStateMachine#clearUnactiveApps",
        targetClass = CLASS_POWER_STATE_MACHINE,
        methodName = "clearUnactiveApps",
        paramTypes = listOf(Context::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.disableKill) {
                param.result = null
                Logger.warn("阻止执行清理后台CUA")
            }
        }
    )

    /**
     * 拦截 miui.process.ProcessManager#kill(ProcessConfig)
     * 当开启 disableKill 时直接返回 false，阻断 PowerKeeper 清除后台应用。
     */
    private fun buildProcessManagerKillHook(): HookSpec = HookSpec(
        id = "P21_KILL",
        name = "ProcessManager#kill",
        targetClass = CLASS_PROCESS_MANAGER,
        methodName = "kill",
        paramTypes = listOf(CLASS_PROCESS_CONFIG),
        paramResolver = { _, clazz ->
            val killMethods = clazz.declaredMethods.filter { it.name == "kill" }
            val configMethod = killMethods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].name == CLASS_PROCESS_CONFIG
            }
            (configMethod ?: killMethods.firstOrNull())?.parameterTypes?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.disableKill) {
                param.result = false
                Logger.warn("阻止 PowerKeeper 执行 ProcessManager.kill 杀后台")
            }
        }
    )

    /**
     * P22（澎湃 3.0 / Android 16 起）：KillProcessController#shouldKillByCheckerPolicy(int uid)
     * 澎湃 3.0 删掉了 `com.miui.powerkeeper.millet.*` 整套查杀链路，查杀决策改由控制器承担：
     * 返回 true 才会真正下发 kill，因此在这里拦成 false 即可保住受保护的应用。
     * 命中条件：`disableKill` 总开关打开，或该 uid 的包名在 `whiteAppSet` 里。
     */
    private fun buildP22KillPolicyHook(): HookSpec = HookSpec(
        id = "P22_KILL_POLICY",
        name = "KillProcessController#shouldKillByCheckerPolicy",
        targetClass = CLASS_KILL_PROCESS_CONTROLLER,
        methodName = "shouldKillByCheckerPolicy",
        paramTypes = listOf(Int::class.javaPrimitiveType!!),
        mode = HookMode.BEFORE,
        before = { param ->
            val uid = param.args.getOrNull(0) as? Int ?: return@HookSpec
            if (uid < 10000) return@HookSpec
            val prefs = PrefReader.powerKeeperPrefs
            val pkg = PowerKeeperState.getPackageNameForUid(uid)
            val hit = prefs.disableKill || (pkg != null && pkg in prefs.whiteAppSet)
            if (!hit) return@HookSpec
            param.result = false
            if (prefs.isLog) {
                Logger.warn("阻止电量与性能查杀 ${pkg ?: uid} (UID=$uid)")
            }
        }
    )

    /**
     * P23（澎湃 3.0 / Android 16 起）：FrozenAppController#appIsAllowToFrozen(int uid)
     * 冻结资格判定：返回 true 才允许 Greeze 冻结该 uid。
     * 白名单内的包在此直接返回 false，避免「白名单应用仍被冻结」。
     * 强制冻结名单（`forceFreezeSet`）不受影响，保持原样放行。
     */
    private fun buildP23FrozenDecisionHook(): HookSpec = HookSpec(
        id = "P23_FROZEN_DECISION",
        name = "FrozenAppController#appIsAllowToFrozen",
        targetClass = CLASS_FROZEN_APP_CONTROLLER,
        methodName = "appIsAllowToFrozen",
        paramTypes = listOf(Int::class.javaPrimitiveType!!),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PowerKeeperState.frozenAppControllerInstance == null && param.thisObject != null) {
                PowerKeeperState.frozenAppControllerInstance = param.thisObject
            }
            val uid = param.args.getOrNull(0) as? Int ?: return@HookSpec
            if (uid < 10000) return@HookSpec
            val prefs = PrefReader.powerKeeperPrefs
            val pkg = PowerKeeperState.getPackageNameForUid(uid) ?: return@HookSpec
            if (pkg in prefs.forceFreezeSet) return@HookSpec
            if (pkg !in prefs.whiteAppSet) return@HookSpec
            param.result = false
            if (prefs.isLog) {
                Logger.warn("阻止冻结白名单应用 $pkg (UID=$uid)")
            }
        }
    )

    /**
     * P24（澎湃 3.0 / Android 16 起）：EventsAggregator#notifyForCharging(boolean)
     * 1.0/2.0 的「禁止插电解冻」挂在上游 `MilletHandler` 的充电消息（what=16）上，
     * 3.0 随 `millet.*` 一起删除；充电事件改由 `EventsAggregator` 广播给各控制器，
     * 因此在事件源头拦掉即可保持同样的行为：`disableCharge` 打开时不再触发批量解冻。
     */
    private fun buildP24NoChargeThawHook(): HookSpec = HookSpec(
        id = "P24_NO_CHARGE_THAW",
        name = "EventsAggregator#notifyForCharging",
        targetClass = CLASS_EVENTS_AGGREGATOR,
        methodName = "notifyForCharging",
        paramTypes = listOf(Boolean::class.javaPrimitiveType!!),
        mode = HookMode.BEFORE,
        before = { param ->
            if (!PrefReader.powerKeeperPrefs.disableCharge) return@HookSpec
            param.result = null
            if (PrefReader.powerKeeperPrefs.isLog) {
                Logger.warn("阻止插电触发的解冻事件")
            }
        }
    )

    /**
     * P25（澎湃 3.0 / Android 16 起）：PowerCheckerController#autoKillApp(int uid, String reason)
     * 真正的「自动查杀」执行入口（`shouldKillByCheckerPolicy` 只是策略判断，这里是落地）。
     * 两道都拦，才能覆盖「策略放行但执行另走一条路」的情况。
     */
    private fun buildP25AutoKillGuardHook(): HookSpec = HookSpec(
        id = "P25_AUTO_KILL",
        name = "PowerCheckerController#autoKillApp",
        targetClass = CLASS_POWER_CHECKER_CONTROLLER,
        methodName = "autoKillApp",
        paramTypes = listOf(Int::class.javaPrimitiveType!!, String::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            guardKill(param, "自动查杀")
        }
    )

    /**
     * P26（澎湃 3.0 / Android 16 起）：AudioDisguiseController#killApp(int uid, RunningProcessInfo, int)
     * 音频欺骗链路自带的查杀出口：冻结应用在播音频时会被这条路径杀掉，同样纳入保护。
     */
    private fun buildP26AudioKillGuardHook(): HookSpec = HookSpec(
        id = "P26_AUDIO_KILL",
        name = "AudioDisguiseController#killApp",
        targetClass = CLASS_AUDIO_DISGUISE_CONTROLLER,
        methodName = "killApp",
        paramTypes = listOf(Int::class.javaPrimitiveType!!, Any::class.java, Int::class.javaPrimitiveType!!),
        paramResolver = { _, clazz ->
            clazz.declaredMethods
                .firstOrNull { it.name == "killApp" && it.parameterCount == 3 }
                ?.parameterTypes
                ?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            guardKill(param, "音频欺骗")
        }
    )

    /**
     * 查杀守卫的公共判定：`disableKill` 总开关打开，或该 uid 的包名在 `whiteAppSet` 里时，
     * 把返回值为 boolean 的查杀方法直接置 false（HookMode.BEFORE 会跳过原实现）。
     */
    private fun guardKill(param: UnifiedHookParam, scene: String) {
        val uid = param.args.getOrNull(0) as? Int ?: return
        if (uid < 10000) return
        val prefs = PrefReader.powerKeeperPrefs
        val pkg = PowerKeeperState.getPackageNameForUid(uid)
        if (!prefs.disableKill && (pkg == null || pkg !in prefs.whiteAppSet)) return
        param.result = false
        if (prefs.isLog) {
            Logger.warn("阻止${scene} ${pkg ?: uid} (UID=$uid)")
        }
    }

    // ==================== P15–P20：6 个构造器反射句柄缓存 ====================

    /**
     * P15（PA(18)）：BluetoothEvent#<init>(Context, Handler)
     */
    private fun buildP15BluetoothEventInitHook(): HookSpec = HookSpec(
        id = "P15",
        name = "BluetoothEvent#<init>",
        targetClass = CLASS_BLUETOOTH_EVENT,
        methodName = null,
        paramTypes = listOf(Context::class.java, Handler::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PowerKeeperState.bluetoothEventInstance == null && param.thisObject != null) {
                PowerKeeperState.bluetoothEventInstance = param.thisObject
            }
        }
    )

    /**
     * P16（PA(19)）：BrightController#<init>(Context, FrozenAppController)
     * 真机核验（PrefsAccessor.java:319 与 PowerKeeperManager.java:32）：
     * `FrozenAppController` 位于 `com.miui.powerkeeper.controller.FrozenAppController`，同时提供构造器反射回退。
     */
    private fun buildP16BrightControllerInitHook(): HookSpec = HookSpec(
        id = "P16",
        name = "BrightController#<init>",
        targetClass = CLASS_BRIGHT_CONTROLLER,
        methodName = null,
        paramTypes = listOf(Context::class.java, CLASS_FROZEN_APP_CONTROLLER),
        paramResolver = { _, clazz ->
            clazz.declaredConstructors
                .firstOrNull { it.parameterTypes.size == 2 }
                ?.parameterTypes
                ?.toList()
        },
        mode = HookMode.BEFORE,
        before = { param ->
            if (PowerKeeperState.brightControllerInstance == null && param.thisObject != null) {
                PowerKeeperState.brightControllerInstance = param.thisObject
            }
        }
    )

    /**
     * P17（PA(21)）：FreezeBinder#<init>()
     */
    private fun buildP17FreezeBinderInitHook(): HookSpec = HookSpec(
        id = "P17",
        name = "FreezeBinder#<init>",
        targetClass = CLASS_FREEZE_BINDER,
        methodName = null,
        paramTypes = emptyList(),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PowerKeeperState.freezeBinderInstance == null && param.thisObject != null) {
                PowerKeeperState.freezeBinderInstance = param.thisObject
            }
        }
    )

    /**
     * P18（PA(23)）：PowerKeeperManager#<init>(Context)
     */
    private fun buildP18PowerKeeperManagerInitHook(): HookSpec = HookSpec(
        id = "P18",
        name = "PowerKeeperManager#<init>",
        targetClass = CLASS_POWER_KEEPER_MANAGER,
        methodName = null,
        paramTypes = listOf(Context::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PowerKeeperState.powerKeeperManagerInstance == null && param.thisObject != null) {
                PowerKeeperState.powerKeeperManagerInstance = param.thisObject
            }
        }
    )

    /**
     * P19（PA(24)）：ProcessObserver#<init>(Context, EventsAggregator)
     */
    private fun buildP19ProcessObserverInitHook(): HookSpec = HookSpec(
        id = "P19",
        name = "ProcessObserver#<init>",
        targetClass = CLASS_PROCESS_OBSERVER,
        methodName = null,
        paramTypes = listOf(Context::class.java, CLASS_EVENTS_AGGREGATOR),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PowerKeeperState.processObserverInstance == null && param.thisObject != null) {
                PowerKeeperState.processObserverInstance = param.thisObject
            }
        }
    )

    /**
     * P20（PA(20)）：BrightScenarioBase#<init>(MilletPolicy, Context, boolean, BrightController)
     */
    private fun buildP20BrightScenarioBaseInitHook(): HookSpec = HookSpec(
        id = "P20",
        name = "BrightScenarioBase#<init>",
        targetClass = CLASS_BRIGHT_SCENARIO_BASE,
        methodName = null,
        paramTypes = listOf(
            CLASS_MILLET_POLICY,
            Context::class.java,
            Boolean::class.javaPrimitiveType!!,
            CLASS_BRIGHT_CONTROLLER
        ),
        mode = HookMode.AFTER,
        after = { param ->
            if (PowerKeeperState.brightScenarioInstance == null && param.thisObject != null) {
                PowerKeeperState.brightScenarioInstance = param.thisObject
            }
            PowerKeeperState.applyPreloadWhiteApps()
        }
    )

    /**
     * P27（澎湃 3.0 专属，替代已消失的 P3/P4/P6）：GlobalFeatureConfigureHelper#updateGlobalConfigure
     *
     * 3.0 把 millet.* 整套删掉后，云白名单 / Doze 名单 / 亮屏名单的云端下发全部收敛到这一个入口：
     * `DeviceIdleController#getDozeWhiteListAppFromNewCloud()` 读 `doze_whitelist_apps` 后就是调它，
     * `frozenNew_whitelist` 与 `bright_frozen` 同理。
     *
     * 【只拦不改】：Bundle 里带受保护键时整包丢弃，等价于旧版「云白名单第一刀 / 第二刀 + 睡眠模式名单」
     * 三处拦截；其它键的云配置照常写入。开关沿用 `blockAddPowerSaveWhitelistApps`（与 P7/P8 同一把闸）。
     */
    private fun buildP27CloudWhiteListWriteHook(): HookSpec = HookSpec(
        id = "P27",
        name = "GlobalFeatureConfigureHelper#updateGlobalConfigure",
        targetClass = CLASS_GLOBAL_FEATURE_HELPER,
        methodName = "updateGlobalConfigure",
        paramTypes = listOf(Context::class.java, Bundle::class.java),
        mode = HookMode.BEFORE,
        before = { param ->
            if (PrefReader.powerKeeperPrefs.blockAddPowerSaveWhitelistApps) {
                val keys = (param.args.getOrNull(1) as? Bundle)?.keySet()?.toSet().orEmpty()
                val hit = keys.intersect(CLOUD_WHITE_LIST_KEYS)
                if (hit.isNotEmpty()) {
                    param.result = null
                    if (PrefReader.powerKeeperPrefs.isLog) {
                        Logger.warn("屏蔽云名单下发: ${hit.joinToString(",")}")
                    }
                }
            }
        }
    )
}
