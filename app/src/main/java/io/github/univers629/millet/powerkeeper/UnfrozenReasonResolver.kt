package io.github.univers629.millet.powerkeeper

import android.content.Context
import android.provider.Settings
import android.util.SparseArray
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.NoRestrictSync
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.XReflect
import io.github.univers629.millet.ipc.MilletIpcContract

/**
 * 「这个应用为什么没被冻结」反射诊断判定链（对应原模块 MilletMessageDispatcher.mo498）。
 *
 * - 澎湃 1.0/2.0：结合 P2 / P15–P20 缓存的 7 个对象句柄（`MilletPolicy`、`BluetoothEvent`、`BrightController`、
 *   `FreezeBinder`、`PowerKeeperManager`、`ProcessObserver`、`BrightScenarioBase`）逐项排查 MIUI 冻结策略短路点；
 * - 澎湃 3.0+：宿主已移除 `com.miui.powerkeeper.millet.*`，改走 `FrozenAppController` / `ActiveStateController` /
 *   `ProcessObserver` / `PowerKeeperManager` / `MILLET_NO_RESTRICT_APP` 诊断链路。
 */
object UnfrozenReasonResolver {

    private const val CLASS_MILLET_POLICY = "com.miui.powerkeeper.millet.MilletPolicy"
    private const val CLASS_MILLET_UTILS = "com.miui.powerkeeper.millet.MilletUtils"
    private const val CLASS_MILLET_UID_OBSERVER = "com.miui.powerkeeper.millet.MilletUidObserver"
    private const val CLASS_BRIGHT_SCENARIO_BASE = "com.miui.powerkeeper.millet.bright.BrightScenarioBase"

    /**
     * 诊断指定包名当前未被冻结的原因。
     */
    fun resolve(context: Context?, packageName: String): String {
        val ctx = context ?: PowerKeeperState.applicationContext
        val uid = PowerKeeperState.getPackageUid(ctx, packageName)
        if (uid == 0) {
            Logger.info("未冻结原因 uid == 0")
            return "无法获取UID"
        }

        val policy = PowerKeeperState.milletPolicyInstance
        val classLoader = PowerKeeperState.appClassLoader ?: policy?.javaClass?.classLoader

        // 1. 是否已经处于冻结态
        if (invokeBoolean(PowerKeeperState.freezeBinderInstance, "isUidFrozen", uid)) {
            return "已冻结"
        }

        // 澎湃 3.0 起 com.miui.powerkeeper.millet.* 已整体移除，切换至控制器诊断链
        if (policy == null && !RomCapabilities.hasClass(CLASS_MILLET_POLICY, classLoader)) {
            return resolveControllerRom(ctx, packageName, uid)
        }

        // 2. 是否处于小米换机状态
        if (ctx != null && invokeStaticBoolean(CLASS_MILLET_UTILS, classLoader, "checkHuanji", ctx)) {
            Logger.info("未冻结原因 checkHuanji()")
            return "设备处于换机状态"
        }

        // 3. 是否满足 Millet VERSION_TWO
        if (!readStaticBoolean(CLASS_MILLET_UTILS, classLoader, "VERSION_TWO", defaultVal = true)) {
            Logger.info("未冻结原因 isVERSION_TWO()")
            return "非VERSION_TWO"
        }

        // 4. 游戏应用与近期解冻冷却检查
        if (ctx != null && invokeBoolean(policy, "isGameUid", uid, ctx)) {
            return "可能是游戏"
        }
        if (invokeBoolean(policy, "checkIntervalThawTime", uid)) {
            return "即将解冻,无法冻结"
        }
        if (invokeBoolean(policy, "isBrightWhite", uid)) {
            return "系统内置亮屏白名单"
        }

        // 5. Top 应用 / 当前前台 UID / CTS 包
        if (invokeStaticBoolean(CLASS_MILLET_UTILS, classLoader, "isTopApp", uid)) {
            return "Top应用"
        }
        if (invokeInt(PowerKeeperState.brightControllerInstance, "getCurUid", 0) == uid) {
            return "前台应用"
        }
        if (ctx != null && invokeStaticBoolean(CLASS_MILLET_UTILS, classLoader, "isCtsPackage", uid, packageName, ctx)) {
            return "CTS应用"
        }

        // 6. 省电策略「无限制」名单
        val noRestrictSet = readStringSetField(policy, "mNoRestrictSet")
        if (noRestrictSet != null && packageName in noRestrictSet) {
            return "省电策略无限制应用"
        }

        // 7. 系统 UID 范围检查
        if (uid <= 10000 || uid >= 19999) {
            return "非系统应用"
        }

        // 8. VPN / 当前输入法 / 蓝牙活跃 / 设备拥有者 / 无障碍服务
        if (ctx != null && invokeStaticInt(CLASS_MILLET_UTILS, classLoader, "getActiveVpnUid", 0, ctx) == uid) {
            return "VPN应用"
        }
        if (invokeInt(PowerKeeperState.powerKeeperManagerInstance, "getCurrentIME", 0, 0) == uid) {
            return "当前输入法"
        }
        if (invokeBoolean(PowerKeeperState.bluetoothEventInstance, "isBluetoothActive", uid)) {
            return "蓝牙应用"
        }
        if (readIntField(policy, "mDeviceOwner", 0) == uid) {
            return "设备拥有者"
        }
        val accessibilityUids = readIntSetField(policy, "mAccessibilityUids")
        if (accessibilityUids != null && uid in accessibilityUids) {
            return "无障碍应用"
        }

        // 9. 前台服务 / 音频或 GPS 活跃 / 音乐应用 / 同步状态 / 下载状态
        if (invokeBoolean(PowerKeeperState.processObserverInstance, "isUidForegroundService", uid)) {
            return "有前台服务"
        }
        if (invokeBoolean(policy, "isUidActive", uid)) {
            return "有活跃应用"
        }
        val musicApps = readStaticStringCollection(policy?.javaClass, "musicApp")
        if (musicApps != null && packageName in musicApps) {
            return "音乐应用"
        }
        val syncUids = readStaticIntSet(policy?.javaClass, "SYNC_UIDS")
        if (syncUids != null && uid in syncUids) {
            return "同步应用"
        }
        if (invokeStaticBoolean(CLASS_MILLET_UTILS, classLoader, "getDownload", uid, packageName)) {
            return "下载应用"
        }
        val downloadAppUids = readStaticIntSet(
            if (classLoader != null) XReflect.findClassIfExists(CLASS_MILLET_UTILS, classLoader) else null,
            "Downloadapp"
        )
        if (downloadAppUids != null && uid in downloadAppUids) {
            return "应用正在下载"
        }

        // 10. 云白名单 / 系统应用 / 亮屏白名单 / 谷歌与快搜 / 自启动 / 天气画报 / 预加载 / WebView
        if (invokeBoolean(policy, "isInWhiteList", uid)) {
            return "云白名单应用"
        }
        if (invokeStaticBoolean(CLASS_MILLET_UID_OBSERVER, classLoader, "getIsSystemApp", uid)) {
            return "系统应用"
        }
        // BRIGHT_WHITE_APPS 只在 Android 14 / 澎湃 2.0 起存在，旧版本没有这项判据
        val brightWhiteApps = if (RomCapabilities.hasField(policy?.javaClass, "BRIGHT_WHITE_APPS")) {
            readStaticStringCollection(policy?.javaClass, "BRIGHT_WHITE_APPS")
        } else {
            null
        }
        if (brightWhiteApps != null && packageName in brightWhiteApps) {
            return "系统内置亮屏白名单"
        }
        if (packageName == "com.android.vending" || packageName.contains("quicksearch")) {
            return "谷歌或快速搜索应用不可被冻结"
        }
        if (invokeBoolean(policy, "isAllowAutoStart", packageName, uid)) {
            return "自启动应用"
        }
        if (packageName == "com.miui.weather2" || packageName == "com.mfashiongallery.emag") {
            return "天气或画报不可被冻结"
        }
        val preloadWhiteApps = readPreloadWhiteApps(classLoader)
        if (preloadWhiteApps != null && packageName in preloadWhiteApps) {
            return "预加载白名单"
        }
        if (invokeStaticBoolean(CLASS_MILLET_UTILS, classLoader, "isWebviewSandboxUid", uid)) {
            return "webview应用"
        }

        // 11. 壁纸检查与最终 isAllowFreeze / canFreezePkg 兜底裁决
        if (!invokeBooleanDefaultTrue(policy, "isAllowWallpaper", uid, packageName)) {
            return "壁纸或相关应用"
        }
        if (!invokeBooleanDefaultTrue(policy, "isAllowFreeze", uid)) {
            return "NotAllowFreeze"
        }
        if (!invokeBooleanDefaultTrue(PowerKeeperState.brightScenarioInstance, "canFreezePkg", packageName, uid)) {
            return "CannotFreeze"
        }
        return "未知,应该可被冻结"
    }

    /**
     * 澎湃 3.0+ 控制器架构（无 MilletPolicy/FreezeBinder）下的未冻结诊断链。
     */
    private fun resolveControllerRom(ctx: Context?, packageName: String, uid: Int): String {
        val prefs = PrefReader.powerKeeperPrefs
        if (packageName in prefs.whiteAppSet && packageName !in prefs.forceFreezeSet) {
            return "已加入 XMillet 保护白名单"
        }

        if (ctx != null) {
            val rawNoRestrict = runCatching {
                Settings.System.getString(ctx.contentResolver, MilletIpcContract.SETTINGS_KEY_NO_RESTRICT_APP)
            }.getOrNull()
            if (packageName in NoRestrictSync.split(rawNoRestrict)) {
                return "省电策略无限制应用"
            }
        }

        val procObs = PowerKeeperState.processObserverInstance
        if (invokeBoolean(procObs, "isUidForeground", uid)) {
            return "前台应用"
        }
        if (invokeBoolean(procObs, "isUidForegroundService", uid)) {
            return "有前台服务"
        }

        val pkMgr = PowerKeeperState.powerKeeperManagerInstance
        if (invokeBoolean(pkMgr, "isUidForeground", uid)) {
            return "前台应用"
        }

        if (uid <= 10000 || uid >= 19999) {
            return "非普通用户应用(系统或特殊 UID)"
        }

        if (invokeInt(pkMgr, "getCurrentIME", 0, 0) == uid) {
            return "当前输入法"
        }
        if (invokeBoolean(pkMgr, "getAppActiveState", uid)) {
            return "应用处于活跃冷却状态"
        }

        val activeCtrl = PowerKeeperState.resolveActiveStateController()
        if (invokeBoolean(activeCtrl, "isUidActive", uid)) {
            return "应用活跃状态保持中 (ActiveStateController)"
        }

        val frozenCtrl = PowerKeeperState.resolveFrozenAppController()
        if (frozenCtrl != null) {
            resolveFrozenFailReason(frozenCtrl, uid)?.let { return it }
            if (!invokeBooleanDefaultTrue(frozenCtrl, "appIsAllowToFrozen", uid)) {
                return "FrozenAppController 策略不允许冻结"
            }
        }

        return "尚未触发冻结（由 Greeze / FrozenAppController 调度决定；若长期未冻可开启「强制冻结」排查）"
    }

    private fun resolveFrozenFailReason(frozenCtrl: Any, uid: Int): String? {
        val controls = runCatching {
            XReflect.getObjectField(frozenCtrl, "mAppFrozenControls") as? SparseArray<*>
        }.getOrNull() ?: return null
        val control = controls.get(uid) ?: return null
        val code = runCatching {
            XReflect.getIntField(control, "mFrozenFailedReason")
        }.getOrNull() ?: return null
        return when (code) {
            1 -> "正在执行后台服务 (excutingServices)"
            2 -> "正在接收广播 (receivBroadcast)"
            3 -> "进程 OOM 优先级过高/受保护 (tooLowOom)"
            4 -> "亮屏下存在通知 (hasNotifitionSceenOn)"
            5 -> "存在活跃运行进程 (hasRunningProcess)"
            6 -> "当前无运行进程 (hasNoPids)"
            7 -> "应用仍处于活跃状态 (isActive)"
            8 -> "策略规则不允许冻结 (notAllowFrozen)"
            9 -> "当前处于前台 (appIsForeground)"
            else -> null
        }
    }

    // ==================== 反射安全调用小工具（全判空兜底） ====================

    private fun invokeBoolean(host: Any?, method: String, vararg args: Any?): Boolean {
        if (host == null) return false
        return try {
            (XReflect.callMethod(host, method, *args) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeBooleanDefaultTrue(host: Any?, method: String, vararg args: Any?): Boolean {
        if (host == null) return true
        return try {
            (XReflect.callMethod(host, method, *args) as? Boolean) ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun invokeStaticBoolean(
        className: String,
        classLoader: ClassLoader?,
        method: String,
        vararg args: Any?
    ): Boolean {
        if (classLoader == null) return false
        return try {
            val clazz = XReflect.findClassIfExists(className, classLoader) ?: return false
            (XReflect.callStaticMethod(clazz, method, *args) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeInt(host: Any?, method: String, defaultVal: Int, vararg args: Any?): Int {
        if (host == null) return defaultVal
        return try {
            (XReflect.callMethod(host, method, *args) as? Int) ?: defaultVal
        } catch (_: Throwable) {
            defaultVal
        }
    }

    private fun invokeStaticInt(
        className: String,
        classLoader: ClassLoader?,
        method: String,
        defaultVal: Int,
        vararg args: Any?
    ): Int {
        if (classLoader == null) return defaultVal
        return try {
            val clazz = XReflect.findClassIfExists(className, classLoader) ?: return defaultVal
            (XReflect.callStaticMethod(clazz, method, *args) as? Int) ?: defaultVal
        } catch (_: Throwable) {
            defaultVal
        }
    }

    private fun readIntField(host: Any?, field: String, defaultVal: Int): Int {
        if (host == null) return defaultVal
        return try {
            XReflect.getIntField(host, field)
        } catch (_: Throwable) {
            defaultVal
        }
    }

    private fun readStaticBoolean(
        className: String,
        classLoader: ClassLoader?,
        field: String,
        defaultVal: Boolean
    ): Boolean {
        if (classLoader == null) return defaultVal
        return try {
            val clazz = XReflect.findClassIfExists(className, classLoader) ?: return defaultVal
            XReflect.getStaticBooleanField(clazz, field)
        } catch (_: Throwable) {
            defaultVal
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStringSetField(host: Any?, field: String): Set<String>? {
        if (host == null) return null
        return try {
            XReflect.getObjectField(host, field) as? Set<String>
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readIntSetField(host: Any?, field: String): Set<Int>? {
        if (host == null) return null
        return try {
            XReflect.getObjectField(host, field) as? Set<Int>
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStaticStringCollection(clazz: Class<*>?, field: String): Collection<String>? {
        if (clazz == null) return null
        return try {
            XReflect.getStaticObjectField(clazz, field) as? Collection<String>
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStaticIntSet(clazz: Class<*>?, field: String): Set<Int>? {
        if (clazz == null) return null
        return try {
            XReflect.getStaticObjectField(clazz, field) as? Set<Int>
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPreloadWhiteApps(classLoader: ClassLoader?): Collection<String>? {
        if (classLoader == null) return null
        return try {
            val clazz = XReflect.findClassIfExists(CLASS_BRIGHT_SCENARIO_BASE, classLoader) ?: return null
            XReflect.getStaticObjectField(clazz, "preloadWhiteApps") as? Collection<String>
        } catch (_: Throwable) {
            null
        }
    }
}
