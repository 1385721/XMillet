package io.github.univers629.millet.core

/**
 * 墓碑统计的时间窗口。
 */
enum class StatsWindow(val label: String) {
    TODAY("今天"),
    WEEK("近 7 天"),
    BOOT("开机以来"),
}

/**
 * 唤醒（解冻）原因归类。
 *
 * 归类逻辑与 [FreezeReasonClassifier] 及 `FreezeGateHooks` 严格保持一致。
 */
enum class WakeCategory(val label: String) {
    USER_OPEN("前台活动"),
    NETWORK("网络活动"),
    BINDER("Binder 通信"),
    BROADCAST("广播/通知"),
    SCREEN_ON("亮屏"),
    CHARGING("充电"),
    OTHER("系统其他"),
    MODULE_OPERATION("模块操作"),
    LAUNCH_MODE("启动模式");

    /** Compatibility property: excludes foreground activity, not proof of a human tap. */
    val isBackground: Boolean
        get() = this != USER_OPEN
}

enum class StatsEventOrigin { UNKNOWN, NATIVE, MODULE, LAUNCH_MODE, SNAPSHOT }

/**
 * XMillet 拦截解冻所命中的规则。
 */
enum class InterceptRule(val label: String) {
    BLOCK_THAW("强力压制"),
    IGNORE_NETWORK("忽略网络唤醒"),
    FORBID_SYNC_BINDER("禁止同步 Binder"),
    NO_CHARGE_THAW("禁止插电解冻"),
    NO_SCREEN_ON_THAW("禁止亮屏解冻"),
    HARD_RULE("广播硬规则"),
}

/**
 * 最近动态事件类型。
 */
enum class StatsEventKind(val label: String) {
    FREEZE("冻结"),
    THAW("唤醒"),
    INTERCEPT("已拦截"),
    PROTECT("保护免冻"),
    KILL_BLOCKED("阻止查杀"),
}

/**
 * 时段分布柱：今天 = 24 根小时柱；近 7 天 = 7 根日柱。
 */
data class StatsTimeBucket(
    val label: String,
    val screenOnWakes: Int,
    val screenOffWakes: Int,
) {
    val total: Int
        get() = screenOnWakes + screenOffWakes
}

/**
 * 单个应用的墓碑统计数据（纯模型，不绑定 Android PackageManager / Compose）。
 */
data class AppFreezeStatsData(
    val uid: Int,
    val packageName: String,
    val freezeCount: Int,
    val wakeByCategory: Map<WakeCategory, Int>,
    val interceptByRule: Map<InterceptRule, Int>,
    val frozenMillis: Long,
    val observedMillis: Long,
    val protectedCount: Int = 0,
    val killBlockedCount: Int = 0,
    val topRawReasons: List<Pair<String, Int>> = emptyList(),
) {
    val backgroundWakeCount: Int
        get() = wakeByCategory.entries.sumOf { if (it.key.isBackground) it.value else 0 }

    val userOpenCount: Int
        get() = wakeByCategory[WakeCategory.USER_OPEN] ?: 0

    val interceptCount: Int
        get() = interceptByRule.values.sum()

    val frozenRatio: Float
        get() = if (observedMillis > 0L) (frozenMillis.toFloat() / observedMillis).coerceIn(0f, 1f) else 0f
}

/**
 * 最近动态环形缓冲区记录项。
 */
data class StatsRecentEventData(
    val timeMillis: Long,
    val uid: Int,
    val packageName: String,
    val kind: StatsEventKind,
    val category: WakeCategory? = null,
    val rule: InterceptRule? = null,
    val rawReason: String = "",
    val origin: StatsEventOrigin = StatsEventOrigin.UNKNOWN,
)

/**
 * 墓碑统计跨进程快照数据。
 */
data class FreezeStatsSnapshotData(
    val window: StatsWindow,
    val enabled: Boolean = true,
    val sinceMillis: Long,
    val generatedAtMillis: Long,
    val freezeCount: Int,
    val wakeByCategory: Map<WakeCategory, Int>,
    val screenOffWakeCount: Int,
    val screenOffMillis: Long,
    val interceptByRule: Map<InterceptRule, Int>,
    val protectedCount: Int,
    val killBlockedCount: Int,
    val frozenMillisTotal: Long,
    val buckets: List<StatsTimeBucket>,
    val apps: List<AppFreezeStatsData>,
    val recent: List<StatsRecentEventData>,
    val memoryBytes: Long = 0,
    val sourceNote: String? = null,
) {
    val backgroundWakeCount: Int
        get() = wakeByCategory.entries.sumOf { if (it.key.isBackground) it.value else 0 }

    val interceptCount: Int
        get() = interceptByRule.values.sum()

    val isEmpty: Boolean
        get() = freezeCount == 0 &&
            wakeByCategory.values.sum() == 0 &&
            interceptCount == 0 &&
            protectedCount == 0 &&
            killBlockedCount == 0
}

/**
 * 解冻/唤醒理由规则判定与分类器（由 Hook 闸门与统计采集器共用，保证语义完全一致）。
 */
object FreezeReasonClassifier {

    private val ALLOWED_THAW_EXACT_REASONS: Set<String> = setOf(
        // 真机逐字命中的 13 项
        "Activity Front",
        "Check Binder",
        "FG activity",
        "IMMOBULUS",
        "LAUNCH_MODE",
        "Net Change",
        "PACKET",
        "Signal iso",
        "Signal other",
        "Sync Binder",
        "broadcast",
        "cmd: thuid",
        "notifi",
        // 澎湃 3.0 实机额外命中的解冻理由
        "Activity Resume",
        "PendingNotify",
        "UI bar",
        "network connect",
        // 真机 Activity 启动与切换至前台的实际字符串
        "Activity Start",
        " Activity Start!",
        "toFrontActivity",
        "moveToFront",
        "notifyMovetoFront",
        // 兼容旧版字面量
        "activity start",
        "tofront",
        "MediaButton",
        "receiver pkg",
    )

    private val NETWORK_WAKE_REASONS: Set<String> = setOf(
        "PACKET",
        "Net Change",
        "network connect",
        "Signal other",
        "Signal iso",
    )

    fun isSyncBinderThawReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return trimmed.startsWith("Sync Binder", ignoreCase = true) ||
            trimmed.startsWith("Check Binder", ignoreCase = true)
    }

    fun isAllowedThawReason(reason: String): Boolean {
        if (reason in ALLOWED_THAW_EXACT_REASONS) return true
        val trimmed = reason.trim()
        if (trimmed in ALLOWED_THAW_EXACT_REASONS) return true
        return isSyncBinderThawReason(trimmed) ||
            trimmed.startsWith("broadcast", ignoreCase = true) ||
            trimmed.startsWith("Signal ", ignoreCase = true) ||
            trimmed.startsWith("Activity Start", ignoreCase = true) ||
            trimmed.contains("toFront", ignoreCase = true) ||
            trimmed.contains("MediaButton", ignoreCase = true) ||
            trimmed.startsWith("receiver", ignoreCase = true)
    }

    fun isToFrontReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return trimmed.equals("tofront", ignoreCase = true) ||
            trimmed.contains("toFront", ignoreCase = true) ||
            trimmed.startsWith("Activity Front", ignoreCase = true) ||
            trimmed.startsWith("Activity Start", ignoreCase = true) ||
            trimmed.equals("Activity Resume", ignoreCase = true)
    }

    fun isNetworkWakeReason(reason: String): Boolean {
        if (reason in NETWORK_WAKE_REASONS) return true
        val trimmed = reason.trim()
        if (trimmed in NETWORK_WAKE_REASONS) return true
        return trimmed.startsWith("Signal ", ignoreCase = true) ||
            trimmed.equals("network connect", ignoreCase = true)
    }

    fun isChargeThawReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return trimmed.contains("charg", ignoreCase = true) ||
            trimmed.contains("充电") ||
            trimmed.equals("CHARGE_CHANGE", ignoreCase = true)
    }

    fun isScreenOnThawReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return trimmed.startsWith("screen on", ignoreCase = true) ||
            trimmed.startsWith("screen_on", ignoreCase = true)
    }

    fun isUserOpenReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return isToFrontReason(trimmed) ||
            trimmed.startsWith("Activity Start", ignoreCase = true) ||
            trimmed.equals("FG activity", ignoreCase = true) ||
            trimmed.equals("UI bar", ignoreCase = true)
    }

    fun isBroadcastReason(reason: String): Boolean {
        val trimmed = reason.trim()
        return trimmed.equals("broadcast", ignoreCase = true) ||
            trimmed.equals("notifi", ignoreCase = true) ||
            trimmed.equals("PendingNotify", ignoreCase = true) ||
            trimmed.startsWith("receiver", ignoreCase = true) ||
            trimmed.contains("notify", ignoreCase = true) ||
            trimmed.contains("notification", ignoreCase = true) ||
            trimmed.contains("broadcast", ignoreCase = true)
    }

    fun classifyWakeCategory(reason: String): WakeCategory {
        val trimmed = reason.trim()
        if (trimmed.isEmpty()) return WakeCategory.OTHER
        return when {
            trimmed.equals("LAUNCH_MODE", ignoreCase = true) -> WakeCategory.LAUNCH_MODE
            isUserOpenReason(trimmed) -> WakeCategory.USER_OPEN
            isChargeThawReason(trimmed) -> WakeCategory.CHARGING
            isScreenOnThawReason(trimmed) -> WakeCategory.SCREEN_ON
            isSyncBinderThawReason(trimmed) || trimmed.contains("binder", ignoreCase = true) -> WakeCategory.BINDER
            isBroadcastReason(trimmed) -> WakeCategory.BROADCAST
            trimmed.equals("PACKET", ignoreCase = true) ||
                trimmed.equals("Net Change", ignoreCase = true) ||
                trimmed.equals("network connect", ignoreCase = true) ||
                trimmed.startsWith("Signal ", ignoreCase = true) -> WakeCategory.NETWORK
            else -> WakeCategory.OTHER
        }
    }
}
