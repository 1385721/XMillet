package io.github.univers629.millet.ui.model

import androidx.compose.runtime.Immutable
import io.github.univers629.millet.core.CpuStatSource
import io.github.univers629.millet.core.InterceptRule
import io.github.univers629.millet.core.StatsEventKind
import io.github.univers629.millet.core.StatsEventOrigin
import io.github.univers629.millet.core.StatsTimeBucket
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.core.WakeCategory
import io.github.univers629.millet.ui.util.StatsPackageMember

@Immutable
data class AppFreezeStats(
    val uid: Int,
    val packageName: String,
    val freezeCount: Int,
    val wakeByCategory: Map<WakeCategory, Int>,
    val interceptByRule: Map<InterceptRule, Int>,
    val frozenMillis: Long,
    /** 该应用在窗口内被观测的时长（用于计算冻结率），后端给不出时为 0 */
    val observedMillis: Long,
    val protectedCount: Int = 0,
    val killBlockedCount: Int = 0,
    /** 原始 reason Top N（排查新理由用） */
    val topRawReasons: List<Pair<String, Int>> = emptyList(),
    val jitterCount: Int = 0,
    val topJitterReason: String = "",
    // ---- 以下由 App 侧 PackageManager 解析填充 ----
    val label: String = packageName,
    val iconSource: AppIconSource? = null,
    val isSystem: Boolean = false,
    val isDualApp: Boolean = false,
    val members: List<StatsPackageMember> = emptyList(),
) {
    val backgroundWakeCount: Int get() = wakeByCategory.entries.sumOf { if (it.key.isBackground) it.value else 0 }
    val userOpenCount: Int get() = wakeByCategory[WakeCategory.USER_OPEN] ?: 0
    val interceptCount: Int get() = interceptByRule.values.sum()
    val frozenRatio: Float get() = if (observedMillis > 0) (frozenMillis.toFloat() / observedMillis).coerceIn(0f, 1f) else 0f
}

@Immutable
data class StatsRecentEvent(
    val timeMillis: Long,
    val uid: Int,
    val packageName: String,
    val kind: StatsEventKind,
    val category: WakeCategory? = null,
    val rule: InterceptRule? = null,
    val rawReason: String = "",
    val label: String = packageName,
    val iconSource: AppIconSource? = null,
    val isSystem: Boolean = false,
    val isDualApp: Boolean = false,
    val members: List<StatsPackageMember> = emptyList(),
    val origin: StatsEventOrigin = StatsEventOrigin.UNKNOWN,
)

@Immutable
data class DozeAppCpuUiModel(
    val uid: Int,
    val packageName: String,
    val cpuMillis: Long,
    val sessionCount: Int,
    val label: String = packageName,
    val iconSource: AppIconSource? = null,
    val isSystem: Boolean = false,
    val isDualApp: Boolean = false,
    val members: List<StatsPackageMember> = emptyList(),
)

@Immutable
data class DozeSessionUiModel(
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val maintenanceCount: Int,
    val startBatteryPct: Int,
    val endBatteryPct: Int,
    val chargedDuringSession: Boolean,
    val topApps: List<DozeAppCpuUiModel> = emptyList(),
)

@Immutable
data class DozeSummaryUiModel(
    val sessionCount: Int = 0,
    val totalDurationMillis: Long = 0L,
    val maintenanceCount: Int = 0,
    val netBatteryDropPct: Int = 0,
    val unpluggedDurationMillis: Long = 0L,
    val cpuSource: CpuStatSource = CpuStatSource.NONE,
    val apps: List<DozeAppCpuUiModel> = emptyList(),
    val recentSessions: List<DozeSessionUiModel> = emptyList(),
)

@Immutable
data class FreezeStatsSnapshot(
    val window: StatsWindow,
    /** 统计起点（墙钟毫秒） */
    val sinceMillis: Long,
    val generatedAtMillis: Long,
    val freezeCount: Int,
    val wakeByCategory: Map<WakeCategory, Int>,
    val screenOffWakeCount: Int,
    /** 窗口内息屏总时长，用于计算「平均每 X 分钟一次」 */
    val screenOffMillis: Long,
    val interceptByRule: Map<InterceptRule, Int>,
    val protectedCount: Int,
    val killBlockedCount: Int,
    val frozenMillisTotal: Long,
    val buckets: List<StatsTimeBucket>,
    val apps: List<AppFreezeStats>,
    val recent: List<StatsRecentEvent>,
    /** system_server 内统计占用（字节），用于页脚透明度展示 */
    val memoryBytes: Long = 0,
    /** 降级说明：例如「本机缺少 dispatchThawEvent，唤醒次数由 thawUid 推断」 */
    val sourceNote: String? = null,
    val isSample: Boolean = false,
    val dozeSummary: DozeSummaryUiModel = DozeSummaryUiModel(),
) {
    val backgroundWakeCount: Int get() = wakeByCategory.entries.sumOf { if (it.key.isBackground) it.value else 0 }
    val totalWakeCount: Int get() = wakeByCategory.values.sum()
    val interceptCount: Int get() = interceptByRule.values.sum()
    val isEmpty: Boolean
        get() = freezeCount == 0 &&
            totalWakeCount == 0 &&
            interceptCount == 0 &&
            protectedCount == 0 &&
            killBlockedCount == 0
}

internal fun originLabel(origin: StatsEventOrigin): String = when (origin) {
    StatsEventOrigin.UNKNOWN -> "来源未确认"
    StatsEventOrigin.NATIVE -> "系统记录"
    StatsEventOrigin.MODULE -> "模块操作"
    StatsEventOrigin.LAUNCH_MODE -> "系统另一入口"
    StatsEventOrigin.SNAPSHOT -> "状态对照"
}

sealed interface StatsLoadState {
    data object Loading : StatsLoadState
    /** 模块未激活 */
    data object ServiceUnavailable : StatsLoadState
    /** 模块版本不支持统计事务码（后端未就绪 / 旧模块） */
    data object BackendUnsupported : StatsLoadState
    /** 墓碑统计开关已关闭 */
    data object Disabled : StatsLoadState
    data class Ready(val snapshot: FreezeStatsSnapshot) : StatsLoadState
}

internal enum class RankMode(val label: String) {
    WAKE("后台唤醒"),
    FROZEN("冻结时间"),
    INTERCEPT("拦截最多"),
    SYSTEM_RECORDS("系统记录"),
}
