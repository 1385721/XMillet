package io.github.univers629.millet.core

import android.content.Context

/**
 * 首次开箱引导配置向导（OOBE / Provision）状态管理器。
 *
 * 对齐 HyperCeiler OobeUtils 规范：
 * - 维护 `is_provisioned` 引导完成标记；
 * - 首次启动时引导用户完成核心权限配置；
 * - 支持在设置中心随时重新唤起或重置引导流程。
 */
object OobeManager {
    private const val PREF_OOBE = "pref_oobe_state"
    private const val KEY_IS_PROVISIONED = "is_provisioned"

    fun isProvisioned(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_OOBE, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_IS_PROVISIONED, false)
    }

    fun setProvisioned(context: Context, provisioned: Boolean) {
        context.getSharedPreferences(PREF_OOBE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_PROVISIONED, provisioned)
            .apply()
    }

    fun resetOobe(context: Context) {
        context.getSharedPreferences(PREF_OOBE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
