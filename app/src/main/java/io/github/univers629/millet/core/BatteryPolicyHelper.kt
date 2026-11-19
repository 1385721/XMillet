package io.github.univers629.millet.core

import android.content.Context
import android.provider.Settings

/**
 * PowerKeeper 生成、Greezer 真正认的「无限制」名单。
 *
 * PowerKeeper 的 `ActiveStateController.dealNoRestrictApp()` 会把配置库里 `bgControl = noRestrict`
 * 的应用汇总成这个系统设置项（逗号分隔的包名），Greezer 也读它来决定是否冻结 —— 也就是说它
 * 是系统自己维护的权威投影，不需要 Root 也能读。
 */
const val SETTING_MILLET_NO_RESTRICT_APP = "MILLET_NO_RESTRICT_APP"

/**
 * HyperOS / MIUI 系统「省电策略 = 无限制」名单查询工具。
 */
object BatteryPolicyHelper {

    /**
     * 读取系统「无限制」名单，判断指定包名是否在其内（无需 Root）。
     */
    fun isPackageNoRestrict(context: Context, packageName: String): Boolean = runCatching {
        val raw = Settings.System.getString(context.contentResolver, SETTING_MILLET_NO_RESTRICT_APP)
        raw?.split(',')?.any { it.trim() == packageName } == true
    }.getOrDefault(false)
}
