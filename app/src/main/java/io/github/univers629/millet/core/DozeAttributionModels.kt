package io.github.univers629.millet.core

/**
 * 深度睡眠（Doze）期间 UID CPU 耗时的底层采集来源。
 */
enum class CpuStatSource(val wireKey: String, val label: String) {
    NONE("none", "未采样"),
    KERNEL_READER("ebpf", "系统 UID 内核统计"),
    PROC_UID_STAT("proc_uid", "/proc UID 统计"),
    CGROUP_CPU_STAT("cgroup_v2", "CGroup CPU 统计");

    companion object {
        fun fromWireKey(key: String?): CpuStatSource =
            entries.firstOrNull { it.wireKey == key } ?: NONE
    }
}

/**
 * 深度睡眠期间单个应用的 CPU 耗时归因（纯模型）。
 */
data class DozeAppCpuData(
    val uid: Int,
    val packageName: String,
    val cpuMillis: Long,
    val sessionCount: Int,
)

/**
 * 单次连续深度睡眠（IDLE 5 <-> IDLE_MAINTENANCE 6）待机会话记录。
 */
data class DozeSessionData(
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val maintenanceCount: Int,
    val startBatteryPct: Int,
    val endBatteryPct: Int,
    val chargedDuringSession: Boolean,
    val topApps: List<DozeAppCpuData> = emptyList(),
)

/**
 * 统计窗口内的深度睡眠汇总归因。
 */
data class DozeSummaryData(
    val sessionCount: Int = 0,
    val totalDurationMillis: Long = 0L,
    val maintenanceCount: Int = 0,
    val netBatteryDropPct: Int = 0,
    val unpluggedDurationMillis: Long = 0L,
    val cpuSource: CpuStatSource = CpuStatSource.NONE,
    val apps: List<DozeAppCpuData> = emptyList(),
    val recentSessions: List<DozeSessionData> = emptyList(),
)

/**
 * `/proc/uid_cputime/show_uid_stat` 与 CGroup v2 `cpu.stat` 纯解析工具。
 */
object UidCpuTimeParser {

    /**
     * 解析 `/proc/uid_cputime/show_uid_stat` 文本（格式：`uid: user_us sys_us`，单位微秒 -> 毫秒）。
     */
    fun parseProcUidStat(content: String): Map<Int, Long> {
        if (content.isBlank()) return emptyMap()
        val result = LinkedHashMap<Int, Long>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val uid = line.substring(0, colon).trim().toIntOrNull() ?: return@forEach
            if (!AppUidUtils.isManagedAppUid(uid)) return@forEach
            val parts = line.substring(colon + 1).trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val userUs = parts[0].toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
            val sysUs = parts[1].toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
            val totalMs = (userUs + sysUs) / 1000L
            if (totalMs > 0L) result[uid] = totalMs
        }
        return result
    }

    /**
     * 解析 CGroup v2 `cpu.stat`（优先 `usage_usec`，回退 `user_usec + system_usec`，单位微秒 -> 毫秒）。
     */
    fun parseCgroupCpuStat(content: String): Long? {
        if (content.isBlank()) return null
        var usageUs: Long? = null
        var userUs: Long? = null
        var sysUs: Long? = null
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val space = line.indexOf(' ')
            if (space <= 0) return@forEach
            val key = line.substring(0, space)
            val value = line.substring(space + 1).trim().toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
            when (key) {
                "usage_usec" -> usageUs = value
                "user_usec" -> userUs = value
                "system_usec" -> sysUs = value
            }
        }
        val totalUs = usageUs ?: if (userUs != null || sysUs != null) (userUs ?: 0L) + (sysUs ?: 0L) else null
        return totalUs?.div(1000L)
    }
}
