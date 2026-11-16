package io.github.univers629.millet.core

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import io.github.univers629.millet.ipc.PowerKeeperBinderClient

/**
 * PowerKeeper 生成、Greezer 真正认的「无限制」名单。
 *
 * PowerKeeper 的 `ActiveStateController.dealNoRestrictApp()` 会把配置库里 `bgControl = noRestrict`
 * 的应用汇总成这个系统设置项（逗号分隔的包名），Greezer 也读它来决定是否冻结 —— 也就是说它
 * 是系统自己维护的权威投影，不需要 Root 也能读。
 */
const val SETTING_MILLET_NO_RESTRICT_APP = "MILLET_NO_RESTRICT_APP"

/**
 * HyperOS / MIUI / AOSP 省电策略（电池优化）状态查询工具。
 *
 * 真值来源按优先级依次为：
 * 1. `Settings.System["MILLET_NO_RESTRICT_APP"]`：PowerKeeper 自己生成的「无限制」名单（免 Root）；
 * 2. 模块 IPC（powerkeeper 作用域被勾选时，由模块同步的系统「无限制」名单）；
 * 3. AOSP `PowerManager.isIgnoringBatteryOptimizations`。
 *
 * 不再用 `content query content://com.miui.powerkeeper.configure/userTable` 直读配置库：那条路要
 * Root，读到的也只是同一个 `bgControl` 字段，而上面第 1 条正是它经 PowerKeeper 投影后的结果。
 */
object BatteryPolicyHelper {

    enum class Policy {
        /** 省电策略已为「无限制」。 */
        NO_RESTRICT,

        /** 仍受系统限制（智能限制 / 后台运行超过 10 分钟关闭 / 禁止后台运行等）。 */
        RESTRICTED,

        /** 无法判定（既无模块 IPC 也没有任何放行迹象）。 */
        UNKNOWN,
    }

    /** 便捷判据：是否已设为「无限制」。 */
    fun isNoRestrict(context: Context): Boolean = query(context) == Policy.NO_RESTRICT

    /**
     * 不含 Root 的快速判据（系统设置项 + IPC + AOSP），用于返回引导页后立刻刷新界面。
     */
    fun quickIsNoRestrict(context: Context): Boolean {
        if (isInNoRestrictSetting(context)) return true
        val packageName = context.packageName
        val ipcAlive = runCatching { PowerKeeperBinderClient.pingAlive() }.getOrDefault(false)
        if (ipcAlive && runCatching { PowerKeeperBinderClient.isNoRestrictApp(packageName) }.getOrDefault(false)) {
            return true
        }
        return isIgnoringAosp(context)
    }

    fun query(context: Context): Policy {
        // 1. PowerKeeper 生成的「无限制」名单（免 Root）
        if (isInNoRestrictSetting(context)) return Policy.NO_RESTRICT

        // 2. 模块 IPC：powerkeeper 作用域被勾选时由模块同步的真实名单
        val packageName = context.packageName
        val ipcAlive = runCatching { PowerKeeperBinderClient.pingAlive() }.getOrDefault(false)
        if (ipcAlive && runCatching { PowerKeeperBinderClient.isNoRestrictApp(packageName) }.getOrDefault(false)) {
            return Policy.NO_RESTRICT
        }

        // 3. AOSP 原生电池优化白名单
        if (isIgnoringAosp(context)) return Policy.NO_RESTRICT

        return if (ipcAlive) Policy.RESTRICTED else Policy.UNKNOWN
    }

    /** 读取系统「无限制」名单，命中当前包名即为无限制。 */
    fun isInNoRestrictSetting(context: Context): Boolean = isPackageNoRestrict(context, context.packageName)

    /**
     * 读取系统「无限制」名单，判断任意包名是否在其内。
     *
     * 与 [isInNoRestrictSetting] 的区别只是判断对象：那个固定看当前应用，这个用于列表里逐个应用。
     */
    fun isPackageNoRestrict(context: Context, packageName: String): Boolean = runCatching {
        val raw = Settings.System.getString(context.contentResolver, SETTING_MILLET_NO_RESTRICT_APP)
        raw?.split(',')?.any { it.trim() == packageName } == true
    }.getOrDefault(false)

    private fun isIgnoringAosp(context: Context): Boolean = runCatching {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)
}
