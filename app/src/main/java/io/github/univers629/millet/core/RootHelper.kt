package io.github.univers629.millet.core

object RootHelper {

    @Volatile
    var isRootGranted: Boolean = false
        private set

    /** 上一次授权被拒绝/超时的时间戳，用于退避，避免每轮刷新都重新弹一次授权框。 */
    @Volatile
    private var nextRootAttemptAt: Long = 0L

    private const val ROOT_DENIED_BACKOFF_MS = 5 * 60_000L

    /**
     * 申请 Magisk / Superuser Root 权限。
     *
     * 通过常驻 root shell 执行一次 `id`：既触发授权（若尚未授权），也顺带把这条 shell
     * 建立起来，后续命令复用同一条进程，整个 App 生命周期只产生一次超级用户请求
     * （Magisk / KernelSU 每次请求都会弹提示，这会直接变成满屏 toast）。
     *
     * 拒绝/超时后 5 分钟内不再重试，避免用户误点拒绝后陷入连续弹窗。
     *
     * @param timeoutMs 等待用户授权的最长时间，默认 90 秒。
     */
    fun requestRoot(timeoutMs: Long = 90_000L): Boolean {
        if (System.currentTimeMillis() < nextRootAttemptAt) return false
        val lines = RootShell.exec("id", mountMaster = false, timeoutMs = timeoutMs)
        val granted = lines.any { it.contains("uid=0") }
        isRootGranted = granted
        if (!granted) nextRootAttemptAt = System.currentTimeMillis() + ROOT_DENIED_BACKOFF_MS
        return granted
    }

    /**
     * 以 Root 权限执行 shell 命令并返回全部标准输出行。
     *
     * 命令走常驻 root shell（见 [RootShell]），不再每次 fork 一个新的 `su` 进程：
     * 后者在 Magisk / KernelSU 上等于每次调用都弹一次超级用户提示。
     *
     * @param mountMaster 是否在全局挂载命名空间里执行。应用进程的挂载命名空间屏蔽了其它应用的
     *   /data/data 目录，读 com.miui.powerkeeper 这类外部数据库必须用 `su -M` 才看得见。
     */
    fun execSu(command: String, mountMaster: Boolean = false): List<String> {
        return RootShell.exec(command, mountMaster)
    }

    /**
     * 冻结方式与内核等待通道（WCHAN）枚举（1:1 复刻原版 SMillet EnumC0222 与 EnumC0697）。
     */
    enum class FreezerType(val label: String, val colorHex: Long) {
        /** cgroup v2 freezer（橙色 #FF8C00，内核 wchan 命中 do_freezer_trap / get_signal / freezable_schedule） */
        V2("V2", 0xFFFF8C00),

        /** cgroup v1 freezer（蓝色 #0C82FD，内核 wchan 命中 __refrigerator） */
        V1("V1", 0xFF0C82FD),

        /** do_signal_stop 暂停/终止（绿色 #3CB371，内核 wchan 命中 do_signal_stop） */
        KILL("Kill", 0xFF3CB371),

        UNKNOWN("", 0L)
    }

    data class ProcessRawEntry(
        val uid: Int,
        val pid: Int,
        val rssKb: Long,
        val vszKb: Long = 0L,
        val wchan: String,
        val elapsedStr: String = "",
        val uptime: String = "",
        val processName: String,
        val packageName: String,
        val status: String,
        val freezerType: FreezerType,
        val services: List<String> = emptyList()
    )

    /**
     * 格式化物理内存（RSS）占用文本（对齐原版 smillet 换算规范）。
     */
    fun formatMemSize(kb: Long): String {
        return when {
            kb <= 0L -> ""
            kb < 1000L -> "${kb}KB"
            kb < 1024000L -> "${kb / 1024}MB"
            else -> String.format(java.util.Locale.US, "%.2fGB", kb.toDouble() / 1048576.0)
        }
    }

    /**
     * 详情页统一的进程排序：冻结中的排前面，其次按内存降序，最后按进程名稳定排序。
     */
    fun sortProcessEntries(entries: List<ProcessRawEntry>): List<ProcessRawEntry> = entries.sortedWith(
        compareByDescending<ProcessRawEntry> { it.freezerType != FreezerType.UNKNOWN }
            .thenByDescending { it.rssKb }
            .thenBy { it.processName }
    )

    /**
     * 内核提供的冻结器类型（仅描述内核能力，不代表墓碑冻结一定走它）。
     */
    enum class KernelFreezer(val label: String) {
        CGROUP_V2("CGroup v2"),
        CGROUP_V1("CGroup v1"),
        NONE("未提供内核冻结器"),
        UNKNOWN("未探测")
    }
}
