package io.github.univers629.millet.system

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.CpuStatSource
import io.github.univers629.millet.core.UidCpuTimeParser
import java.io.File
import java.lang.reflect.Proxy

/**
 * 深度睡眠（Doze）期间的三级 UID CPU 耗时与电池状态只读采集器。
 *
 * 注意：仅可在 `Logger.postIoTask` 后台 IO 线程中调用，严禁在持有 `DeviceIdleController`
 * 或 `Greeze` 监视器锁的 Hook 回调线程内同步执行。
 */
object UidCpuTimeReader {

    data class Snapshot(
        val source: CpuStatSource,
        val cpuMillisByUid: Map<Int, Long>,
    )

    data class BatteryState(
        val levelPct: Int,
        val isPlugged: Boolean,
    )

    private const val CLASS_USER_SYS_READER =
        "com.android.internal.os.KernelCpuUidTimeReader\$KernelCpuUidUserSysTimeReader"
    private const val CLASS_CALLBACK =
        "com.android.internal.os.KernelCpuUidTimeReader\$Callback"
    private const val PROC_UID_CPUTIME = "/proc/uid_cputime/show_uid_stat"

    @Volatile
    private var readerInstance: Any? = null

    @Volatile
    private var readAbsoluteMethod: java.lang.reflect.Method? = null

    @Volatile
    private var callbackInterface: Class<*>? = null

    @Volatile
    private var kernelReaderUnavailable = false

    fun read(): Snapshot {
        readViaProcUidStat()?.let { if (it.isNotEmpty()) return Snapshot(CpuStatSource.PROC_UID_STAT, it) }
        readViaCgroupCpuStat()?.let { if (it.isNotEmpty()) return Snapshot(CpuStatSource.CGROUP_CPU_STAT, it) }
        readViaKernelReader()?.let { if (it.isNotEmpty()) return Snapshot(CpuStatSource.KERNEL_READER, it) }
        return Snapshot(CpuStatSource.NONE, emptyMap())
    }

    fun readBatteryState(): BatteryState {
        val ctx = SystemState.systemContext ?: return BatteryState(-1, false)
        return runCatching {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return@runCatching BatteryState(-1, false)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) ((level * 100) / scale).coerceIn(0, 100) else -1
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
            BatteryState(pct, plugged)
        }.getOrDefault(BatteryState(-1, false))
    }

    private fun readViaKernelReader(): Map<Int, Long>? {
        if (kernelReaderUnavailable) return null
        return runCatching {
            val reader = readerInstance ?: run {
                val clazz = Class.forName(CLASS_USER_SYS_READER)
                val ctor = clazz.getDeclaredConstructor(Boolean::class.javaPrimitiveType)
                ctor.isAccessible = true
                ctor.newInstance(false).also { readerInstance = it }
            }
            val cbClass = callbackInterface ?: Class.forName(CLASS_CALLBACK).also { callbackInterface = it }
            val method = readAbsoluteMethod ?: reader.javaClass.getMethod("readAbsolute", cbClass)
                .also { readAbsoluteMethod = it }

            val result = LinkedHashMap<Int, Long>()
            val loader = cbClass.classLoader ?: UidCpuTimeReader::class.java.classLoader
            val proxy = Proxy.newProxyInstance(loader, arrayOf(cbClass)) { _, m, args ->
                if (m.name == "onUidCpuTime" && args != null && args.size >= 2) {
                    val uid = args[0] as? Int ?: return@newProxyInstance null
                    if (AppUidUtils.isManagedAppUid(uid)) {
                        val times = args[1] as? LongArray
                        if (times != null && times.isNotEmpty()) {
                            val totalUs = times.fold(0L) { acc, v -> acc + v.coerceAtLeast(0L) }
                            val totalMs = totalUs / 1000L
                            if (totalMs > 0L) result[uid] = totalMs
                        }
                    }
                }
                null
            }
            method.invoke(reader, proxy)
            result
        }.onFailure {
            kernelReaderUnavailable = true
        }.getOrNull()
    }

    private fun readViaProcUidStat(): Map<Int, Long>? {
        val file = File(PROC_UID_CPUTIME)
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            UidCpuTimeParser.parseProcUidStat(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun readViaCgroupCpuStat(): Map<Int, Long>? {
        val roots = listOf(File("/sys/fs/cgroup/apps"), File("/sys/fs/cgroup"))
        val result = LinkedHashMap<Int, Long>()
        for (root in roots) {
            if (!root.isDirectory || !root.canRead()) continue
            val children = runCatching { root.listFiles() }.getOrNull() ?: continue
            for (dir in children) {
                if (!dir.isDirectory) continue
                val name = dir.name
                if (!name.startsWith("uid_")) continue
                val uid = name.substring(4).toIntOrNull() ?: continue
                if (!AppUidUtils.isManagedAppUid(uid) || uid in result) continue
                val statFile = File(dir, "cpu.stat")
                if (!statFile.isFile || !statFile.canRead()) continue
                val ms = runCatching {
                    UidCpuTimeParser.parseCgroupCpuStat(statFile.readText(Charsets.UTF_8))
                }.getOrNull()
                if (ms != null && ms > 0L) {
                    result[uid] = ms
                }
            }
            if (result.isNotEmpty()) break
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
