package io.github.univers629.millet.system

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.Logger
import java.io.File

/**
 * system_server 侧进程快照采集器（App 侧不再需要 root）。
 *
 * 为什么放在这边：system_server 本来就有权限读任意应用进程的 `/proc/<pid>/…`
 * （ANR 抓栈走的就是同一套权限），因此进程表、wchan、RSS、cgroup 冻结状态都能
 * 直接读到，不需要 App 起 `su`。App 通过 `TX_GET_PROCESS_SNAPSHOT` 取这份快照。
 *
 * 数据来源与判据：
 * - 进程集合：`/proc/<pid>/status` 的 `Uid:` 落在 10000–19999（应用进程）；
 * - 进程名：`/proc/<pid>/cmdline`（被截断的 `Name:` 只作兜底），包名取冒号前部分；
 * - 内存：优先读取 `/proc/<pid>/smaps_rollup` 的 `Pss:`（按比例分摊共享页的真实物理内存，
 *   避免多进程对同一份系统/框架/APK 映射页重复计算造成数倍虚高），读不到时退回 `/proc/<pid>/statm`；
 * - 运行时长：`/proc/<pid>/stat` 第 22 字段 starttime（100Hz）与 `/proc/uptime` 之差；
 * - 冻结：优先读 cgroup 真值（v2 `cgroup.freeze`、v1 `freezer.state=FROZEN`），
 *   读不到时退回 wchan 判据；
 * - 服务：`ActivityManager#getRunningServices`（system_server 以 SYSTEM_UID 调用，
 *   权限检查直接放行），按 pid 归并。
 */
object ProcessSnapshotProvider {

    /** 快照协议版本，App 侧据此拒绝不认识的格式。 */
    const val SNAPSHOT_VERSION = "v1"

    private const val MIN_APP_UID = 10000
    private const val MAX_APP_UID = 19999

    /** 同一进程内 1.5 秒内的重复请求直接复用，避免 App 刷新把 system_server 拖慢。 */
    private const val CACHE_WINDOW_MS = 1_500L

    private const val USER_HZ = 100.0

    /** 快照概要日志的最小间隔，避免 5 秒一次的刷新把日志刷爆。 */
    private const val SUMMARY_INTERVAL_MS = 5 * 60_000L

    @Volatile
    private var lastSummaryAt = 0L

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedPayload = ""

    private val lock = Any()

    /** 返回序列化快照（首行是版本号，其后每行一个进程，字段用 `|` 分隔）。 */
    fun snapshot(): String {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt in 0..CACHE_WINDOW_MS) return cachedPayload
        synchronized(lock) {
            val refreshedAt = SystemClock.uptimeMillis()
            if (refreshedAt - cachedAt in 0..CACHE_WINDOW_MS) return cachedPayload
            val payload = try {
                build()
            } catch (t: Throwable) {
                Logger.error("构建进程快照失败", t)
                ""
            }
            cachedPayload = payload
            cachedAt = refreshedAt
            return payload
        }
    }

    private fun build(): String {
        val pageSizeKb = (Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L).coerceAtLeast(4L)
        val uptimeSec = readUptimeSeconds()
        val servicesByPid = collectServicesByPid()
        val builder = StringBuilder(16 * 1024)
        builder.append(SNAPSHOT_VERSION).append('\n')

        val pidNames = File("/proc").list() ?: return builder.toString()

        var rowCount = 0
        var withServices = 0
        for (pidName in pidNames) {
            val pid = pidName.toIntOrNull() ?: continue
            if (pid <= 0) continue
            val status = readText("/proc/$pid/status") ?: continue
            val uid = parseUid(status) ?: continue
            if (!AppUidUtils.isManagedAppUid(uid, includeDualApp = true)) continue

            val stat = readText("/proc/$pid/stat") ?: continue
            val processName = readCmdline(pid).ifEmpty { parseName(status) }
            if (processName.isEmpty()) continue
            val (state, startSec) = parseStat(stat)
            val elapsedSec = if (uptimeSec > 0.0 && startSec > 0.0) {
                (uptimeSec - startSec).toLong().coerceAtLeast(0L)
            } else {
                0L
            }
            val (vszKb, statmRssKb) = parseStatm(readText("/proc/$pid/statm").orEmpty(), pageSizeKb)
            val memKb = readPssKb(pid) ?: statmRssKb
            val wchan = readText("/proc/$pid/wchan")?.trim().orEmpty()
            val freezerType = readFrozenType(pid).ifEmpty { freezerTypeFromWchan(wchan) }
            val services = servicesByPid[pid].orEmpty()

            builder.append(pid).append('|')
                .append(uid).append('|')
                .append(memKb).append('|')
                .append(vszKb).append('|')
                .append(sanitize(wchan)).append('|')
                .append(elapsedSec).append('|')
                .append(sanitize(processName)).append('|')
                .append(sanitize(processName.substringBefore(':'))).append('|')
                .append(sanitize(state)).append('|')
                .append(freezerType).append('|')
                .append(services.joinToString(";") { sanitize(it) })
                .append('\n')
            rowCount++
            if (services.isNotEmpty()) withServices++
        }
        logSummary(rowCount, withServices)
        return builder.toString()
    }

    /**
     * 每 5 分钟打一条快照概要。
     *
     * 快照是 App 唯一的数据来源，出问题时（比如服务列一直为空）只能靠这条日志判断是
     * 「快照没内容」还是「UI 没显示」；用 alwaysInfo 是因为 debug 日志受 isLog 门控。
     */
    private fun logSummary(rows: Int, withServices: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSummaryAt in 0..SUMMARY_INTERVAL_MS) return
        lastSummaryAt = now
        Logger.alwaysInfo("进程快照: 应用进程 $rows 个，其中带挂载服务 $withServices 个")
    }

    // ==================== 服务归并 ====================

    /**
     * 读取系统当前运行中的服务，按进程号归并成「pid -> 服务类名列表」。
     * 调用方是 system_server 自己（SYSTEM_UID），`REAL_GET_TASKS` 权限检查直接放行；
     * 万一宿主改了这个入口，也只是服务列表为空，不影响进程/冻结状态。
     */
    @Suppress("DEPRECATION")
    private fun collectServicesByPid(): Map<Int, List<String>> {
        val context = SystemState.systemContext ?: return emptyMap()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyMap()
        // 本函数可能跑在 App 的 binder 线程上，此时 Binder.getCallingUid() 是 App 自己，
        // 骨架的 getServices 会按调用者过滤，导致「看不到任何服务」。清掉调用身份再查。
        val token = Binder.clearCallingIdentity()
        val running = try {
            manager.getRunningServices(Int.MAX_VALUE).orEmpty()
        } catch (t: Throwable) {
            Logger.warn("读取运行中服务失败: ${t.javaClass.simpleName}: ${t.message}")
            return emptyMap()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        if (running.isEmpty()) return emptyMap()
        val byPid = HashMap<Int, MutableList<String>>()
        for (info in running) {
            val pid = info.pid
            val name = info.service?.className ?: continue
            if (pid <= 0) continue
            byPid.getOrPut(pid) { ArrayList() }.add(name)
        }
        return byPid
    }

    // ==================== /proc 解析 ====================

    private fun readText(path: String): String? = try {
        val file = File(path)
        if (file.canRead()) file.readText() else null
    } catch (_: Throwable) {
        null
    }

    private fun readUptimeSeconds(): Double = try {
        File("/proc/uptime").readText().trim().substringBefore(' ').toDoubleOrNull() ?: 0.0
    } catch (_: Throwable) {
        0.0
    }

    private fun readCmdline(pid: Int): String = try {
        File("/proc/$pid/cmdline").readText().substringBefore('\u0000').trim()
    } catch (_: Throwable) {
        ""
    }

    private fun parseUid(status: String): Int? {
        for (line in status.lineSequence()) {
            if (!line.startsWith("Uid:")) continue
            return line.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
        }
        return null
    }

    private fun parseName(status: String): String {
        for (line in status.lineSequence()) {
            if (!line.startsWith("Name:")) continue
            return line.substringAfter("Name:").trim()
        }
        return ""
    }

    /** @return (state, starttime 秒)；comm 里可能有空格，所以从最后一个 `)` 之后切分。 */
    private fun parseStat(raw: String): Pair<String, Double> {
        val close = raw.lastIndexOf(')')
        if (close < 0 || close + 2 >= raw.length) return "" to 0.0
        val fields = raw.substring(close + 2).trim().split(' ')
        val state = fields.getOrNull(0).orEmpty()
        // state 是全局第 3 字段，starttime 是第 22 字段 -> 相对下标 19
        val startTicks = fields.getOrNull(19)?.toDoubleOrNull() ?: 0.0
        return state to if (startTicks > 0.0) startTicks / USER_HZ else 0.0
    }

    /** @return (vszKb, rssKb) */
    private fun parseStatm(raw: String, pageSizeKb: Long): Pair<Long, Long> {
        if (raw.isEmpty()) return 0L to 0L
        val parts = raw.trim().split(' ')
        val sizePages = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val residentPages = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return (sizePages * pageSizeKb) to (residentPages * pageSizeKb)
    }

    /** 读取内核 `/proc/<pid>/smaps_rollup` 的 `Pss:`（真实按比例分摊物理内存，KB）。 */
    internal fun parsePssKb(smapsRollup: String?): Long? {
        if (smapsRollup.isNullOrEmpty()) return null
        for (line in smapsRollup.lineSequence()) {
            if (!line.startsWith("Pss:")) continue
            val valueStr = line.substringAfter("Pss:").trim().substringBefore(' ')
            return valueStr.toLongOrNull()
        }
        return null
    }

    private fun readPssKb(pid: Int): Long? = parsePssKb(readText("/proc/$pid/smaps_rollup"))

    /**
     * 读 cgroup 冻结真值：v2 看 `cgroup.freeze` 是否为 1，v1 看 `freezer.state` 是否为 FROZEN。
     * @return "V1" / "V2"；读不到或未冻结返回空串。
     */
    private fun readFrozenType(pid: Int): String {
        val cgroup = readText("/proc/$pid/cgroup") ?: return ""
        for (line in cgroup.lineSequence()) {
            if (line.startsWith("0::")) {
                val path = line.substring(3).trim().trimEnd('/')
                if (path.isEmpty() || path == "/") continue
                val value = readText("/sys/fs/cgroup$path/cgroup.freeze")?.trim() ?: continue
                if (value == "1") return "V2"
            } else if (line.contains(":freezer:")) {
                val path = line.substringAfter(":freezer:").trim().trimEnd('/')
                val value = readText("/sys/fs/cgroup/freezer$path/freezer.state")?.trim() ?: continue
                if (value.equals("FROZEN", ignoreCase = true)) return "V1"
            }
        }
        return ""
    }

    private fun freezerTypeFromWchan(wchan: String): String {
        val lower = wchan.lowercase()
        return when {
            lower.contains("do_freezer_trap") || lower.contains("get_signal") ||
                lower.contains("freezable_schedule") -> "V2"
            lower.contains("__refrigerator") -> "V1"
            lower.contains("do_signal_stop") -> "KILL"
            else -> ""
        }
    }

    /** 协议用 `|` 分隔字段、`;` 分隔服务，字段内不允许出现这两个字符。 */
    private fun sanitize(value: String): String = value
        .replace('|', '/')
        .replace(';', ',')
        .replace('\n', ' ')
        .replace('\t', ' ')
}
