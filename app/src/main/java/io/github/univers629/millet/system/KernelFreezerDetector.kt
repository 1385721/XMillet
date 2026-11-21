package io.github.univers629.millet.system

import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.ProcessFreezeReader
import io.github.univers629.millet.core.RootHelper
import java.io.File

/**
 * 在 system_server 内只读官方冻结记账关联的应用进程状态，回传类型与证据来源。
 * 没有可读取的冻结证据时保持未确认，不写入控制文件或主动冻结应用。
 */
object KernelFreezerDetector {

    private const val PROC_SCAN_LIMIT = 4096
    private const val SAMPLE_LIMIT = 400

    data class Report(val mode: String, val evidence: String, val backend: String = "UNCONFIRMED")

    /** 保留旧调用方的类型字段；新调用方同时读取证据来源。 */
    fun detect(): String = detectEvidence().mode

    fun detectEvidence(): Report {
        val report = runCatching { detectUncached() }.getOrElse {
            Logger.warn("内核冻结器探测失败: ${it.javaClass.simpleName}: ${it.message}")
            Report("UNKNOWN", "UNCONFIRMED")
        }
        Logger.alwaysInfo("内核冻结器: ${report.mode}, 证据=${report.evidence}")
        return report
    }

    private fun detectUncached(): Report {
        val backend = readMilletBackend()
        val frozenUids = SystemState.getFrozenUidsArray()
            .filter { AppUidUtils.isManagedAppUid(it) }.toSet()
        if (frozenUids.isEmpty()) return Report("UNKNOWN", "UNCONFIRMED", backend)
        val mounts = ProcessFreezeReader.mounts(readText("/proc/self/mountinfo").orEmpty())
        val pids = runCatching {
            File("/proc").list()?.asSequence()
                ?.filter { (it.toIntOrNull() ?: 0) > 0 }?.take(PROC_SCAN_LIMIT)?.toList()
        }.getOrNull().orEmpty()
        var sampledCount = 0
        for (pid in pids) {
            if (sampledCount >= SAMPLE_LIMIT) break
            val uid = readUid(pid) ?: continue
            if (uid !in frozenUids) continue
            // 关联应用才占用证据采样预算；非关联 PID 只占用有上限的目录扫描预算。
            sampledCount++
            val startTime = readStartTime(pid) ?: continue
            val cgroup = readText("/proc/$pid/cgroup")
            // 先读取成员 cgroup 的完成状态。cgroup.freeze=1 仅是请求，不能确认冻结。
            val evidence = ProcessFreezeReader.inspect(cgroup, "", "", mounts, ::readText)
            var report = if (evidence.state == RootHelper.FreezeState.FROZEN) {
                when (evidence.type) {
                    RootHelper.FreezerType.V2 -> Report("V2", "CGROUP_V2_FROZEN")
                    RootHelper.FreezerType.V1 -> Report("V1", "CGROUP_V1_FROZEN")
                    else -> null
                }
            } else null
            // 与已读取的运行状态冲突时不使用 wchan；普通睡眠与近似函数名不是证据。
            if (report == null && evidence.state != RootHelper.FreezeState.RUNNING) {
                report = when (readText("/proc/$pid/wchan")?.trim()) {
                    "do_freezer_trap" -> Report("V2", "WCHAN_V2")
                    "__refrigerator" -> Report("V1", "WCHAN_V1")
                    else -> null
                }
            }
            if (report == null) continue
            // 同次读取内复核 PID 身份、成员路径和官方记账，避免退出、复用或迁移造成错配。
            if (readUid(pid) != uid || readStartTime(pid) != startTime ||
                readText("/proc/$pid/cgroup") != cgroup ||
                uid !in SystemState.getFrozenUidsArray()) continue
            // 仅说明官方记账与该进程冻结状态共现，不证明冻结原因或后续自动调度。
            return report.copy(backend = readMilletBackend())
        }
        // 无关联样本、权限受限或采样未覆盖，都不能证明内核不支持冻结。
        return Report("UNKNOWN", "UNCONFIRMED", backend)
    }

    /** 读取 ROM 后端的启用与 monitor 注册状态，不把模块构造器闸门当作后端就绪。 */
    private fun readMilletBackend(): String {
        val service = SystemState.greezeService ?: return "UNCONFIRMED"
        return runCatching {
            val serviceClass = Class.forName(
                "com.miui.server.greeze.GreezeManagerService", false, service.javaClass.classLoader
            )
            if (!serviceClass.isInstance(service)) return@runCatching "UNCONFIRMED"
            val configClass = runCatching {
                Class.forName("com.miui.server.greeze.GreezeManagerDebugConfig", false, service.javaClass.classLoader)
            }.getOrNull() ?: serviceClass
            // MIUI 14/OS1 将配置放在服务中；OS2–OS4 使用 DebugConfig 的静态字段。
            val configReceiver = if (configClass == serviceClass) service else null
            classifyBackend(
                readField(configClass, configReceiver, "milletEnable", Boolean::class.javaPrimitiveType!!) as? Boolean,
                readField(configClass, configReceiver, "sEnable", Boolean::class.javaPrimitiveType!!) as? Boolean,
                readField(configClass, configReceiver, "mPowerMilletEnable", Boolean::class.javaPrimitiveType!!) as? Boolean,
                readField(serviceClass, service, "mRegisteredMonitor", Int::class.javaPrimitiveType!!) as? Int,
                readField(serviceClass, null, "MILLET_MONITOR_ALL", Int::class.javaPrimitiveType!!) as? Int
            )
        }.getOrDefault("UNCONFIRMED")
    }

    internal fun classifyBackend(
        milletEnabled: Boolean?, greezeEnabled: Boolean?, powerEnabled: Boolean?,
        registeredMonitor: Int?, requiredMask: Int?
    ): String = when {
        milletEnabled == false || (greezeEnabled == false && powerEnabled == false) -> "DISABLED"
        milletEnabled == null || greezeEnabled == null || powerEnabled == null ||
            registeredMonitor == null || registeredMonitor < 0 || requiredMask == null || requiredMask <= 0 -> "UNCONFIRMED"
        // 传统 Greeze 开关不能单独确认官方后台策略已运行，未核实的替代路径保留未知。
        powerEnabled != true -> "UNCONFIRMED"
        (registeredMonitor and requiredMask) != requiredMask -> "MONITORS_UNREADY"
        else -> "READY"
    }

    private fun readField(owner: Class<*>, receiver: Any?, name: String, type: Class<*>): Any? =
        runCatching {
            val field = owner.getDeclaredField(name)
            if (field.type != type) return@runCatching null
            field.isAccessible = true
            field.get(receiver)
        }.getOrNull()

    private fun readUid(pid: String): Int? = readText("/proc/$pid/status")
        ?.lineSequence()?.firstOrNull { it.startsWith("Uid:") }
        ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

    private fun readStartTime(pid: String): Long? = readText("/proc/$pid/stat")
        ?.substringAfterLast(')', "")?.trim()?.split(Regex("\\s+"))?.getOrNull(19)?.toLongOrNull()

    private fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()
}
