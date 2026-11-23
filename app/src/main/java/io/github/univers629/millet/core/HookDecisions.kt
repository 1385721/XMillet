package io.github.univers629.millet.core

/**
 * 钩子里纯判定的集中处。
 *
 * Hook 主体只能在设备上跑（反射、系统字段、无 Android 单测环境），
 * 把「要不要短路、短路成什么」这类不含副作用的判断抽出来，
 * 才能在 JVM 单元测试里锁住行为，避免回归时只能靠真机复现。
 */
object HookDecisions {

    /** DeviceIdleController 的 QUICK_DOZE_DELAY 状态值（OS3/OS4 独有，AOSP 只有 0..6）。 */
    const val STATE_QUICK_DOZE_DELAY = 7

    /**
     * S34：`DeviceIdleController#isUpcomingAlarmClock` 是否该被短路。
     *
     * 只有「本模块正在强制推进深睡」且「状态卡在 QUICK_DOZE_DELAY(7)」时才短路：
     * 此时 OS4 的 `stepIdleStateLocked` 会先 `becomeActiveLocked("alarm")` 再
     * `becomeInactiveIfAppropriateLocked()`，后者再把状态推回 7，推进循环无法前进。
     *
     * 其他情形一律放行 ROM 原逻辑 —— 尤其非强制路径，短路会把用户设定的闹钟推迟。
     */
    fun bypassUpcomingAlarmWhileForced(forceIdle: Boolean?, state: Int?): Boolean =
        forceIdle == true && state == STATE_QUICK_DOZE_DELAY

    /**
     * P34：`AppClusterController#isControlApp(int)` 的接管结果。
     *
     * 返回 null 表示不接管、交回 ROM 判定（主用户应用都是这种情况）；
     * 双开 uid（999xxxxx）恒被 ROM 的 `uid <= 19999` 拒绝，这里改由偏好开关决定。
     */
    fun clusterControlResult(uid: Int, freezeDualApp: Boolean): Boolean? =
        if (AppUidUtils.isDualAppUid(uid)) freezeDualApp else null

    /**
     * powerkeeper 上报的 Hook 账本是否该覆盖服务端已缓存的那一份。
     *
     * 热重载时 `HookInstallRegistry.clear()` 之后账本是一条条重新写回的，此刻读出来的
     * [HookInstallRegistry.Wire.total] 只是「已记到第几条」，比完整账本小。而服务端
     * `restoreRegistrationSnapshot` 会把上一代的账本塞回去并触发一次重绑上报，
     * 两条上报的先后顺序不确定 —— 先到完整、后到残缺时，界面显示的点位数会偏少。
     *
     * 因此按 total 单调递增采纳：残缺账本的 total 必然小于完整账本，丢弃它即可；
     * total 相同说明规格表已经走完，此时 installed 的差异是真实的安装结果，照常采纳。
     */
    fun acceptLedgerReport(
        incoming: HookInstallRegistry.Wire,
        current: HookInstallRegistry.Wire
    ): Boolean {
        if (!incoming.hasData) return true
        if (!current.hasData) return true
        return incoming.total >= current.total
    }
}
