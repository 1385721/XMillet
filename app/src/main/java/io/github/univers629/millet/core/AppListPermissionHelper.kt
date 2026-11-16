package io.github.univers629.millet.core

import android.content.Context
import android.content.pm.PackageManager

/**
 * 「获取应用列表」权限（HyperOS / MIUI 在 QUERY_ALL_PACKAGES 之外叠加的运行时权限）的检测与申请。
 *
 * 依据小米官方《获取应用列表权限的适配说明》：
 * 1. 清单声明 `com.android.permission.GET_INSTALLED_APPS`；
 * 2. 通过 `PackageManager.getPermissionInfo` 判断该权限由 `com.lbe.security.miui` 提供（即 MIUI 支持动态申请）；
 * 3. 用标准 `requestPermissions` 申请，系统会弹出「获取应用列表」对话框，结果在 `onRequestPermissionsResult` 中返回。
 *
 * 因此本模块不再依赖「枚举包名触发系统弹窗」的猜测做法。
 */
object AppListPermissionHelper {

    /** 小米定义的权限名。 */
    const val PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

    /** MIUI 权限的声明方包名，用于判断系统是否支持动态申请。 */
    private const val MIUI_PERMISSION_OWNER = "com.lbe.security.miui"

    /** 未被拦截时手机上的可见应用条目通常远超该值（非 MIUI 环境的兜底判据）。 */
    private const val VISIBLE_APPS_THRESHOLD = 50

    /** 系统是否支持该权限的动态申请（仅 MIUI / HyperOS 支持）。 */
    fun isSupported(context: Context): Boolean = runCatching {
        context.packageManager
            .getPermissionInfo(PERMISSION, 0)
            .packageName == MIUI_PERMISSION_OWNER
    }.getOrDefault(false)

    /** 权限是否已授予。 */
    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 判定能否看到本应用之外的已安装应用：优先看权限状态，非 MIUI 环境退回枚举数量判据。 */
    fun canReadInstalledApps(context: Context): Boolean {
        if (isGranted(context)) return true
        if (isSupported(context)) return false
        return runCatching {
            context.packageManager.getInstalledApplications(0).size >= VISIBLE_APPS_THRESHOLD
        }.getOrDefault(false)
    }
}
