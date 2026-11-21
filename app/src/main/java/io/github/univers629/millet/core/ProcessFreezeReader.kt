package io.github.univers629.millet.core

import java.io.File

/** 按当前挂载与进程状态读取冻结证据，不写入内核控制文件。 */
object ProcessFreezeReader {
    data class Evidence(
        val state: RootHelper.FreezeState,
        val type: RootHelper.FreezerType = RootHelper.FreezerType.UNKNOWN
    )

    data class Mount(val root: String, val path: String, val v2: Boolean) {
        fun resolve(cgroupPath: String): String? {
            if (!cgroupPath.startsWith('/') || cgroupPath.split('/').any { it == ".." }) return null
            val base = root.trimEnd('/').ifEmpty { "/" }
            val relative = when {
                base == "/" -> cgroupPath
                cgroupPath == base -> ""
                cgroupPath.startsWith("$base/") -> cgroupPath.removePrefix(base)
                else -> return null
            }
            return path.trimEnd('/') + relative.trimEnd('/')
        }
    }

    fun mounts(mountInfo: String): List<Mount> = mountInfo.lineSequence().mapNotNull { line ->
        val halves = line.split(" - ", limit = 2)
        if (halves.size != 2) return@mapNotNull null
        val left = halves[0].split(' ')
        val right = halves[1].split(' ')
        if (left.size < 5 || right.size < 3) return@mapNotNull null
        val v2 = right[0] == "cgroup2"
        if (!v2 && (right[0] != "cgroup" || "freezer" !in right[2].split(','))) {
            return@mapNotNull null
        }
        Mount(unescape(left[3]), unescape(left[4]), v2)
    }.toList()

    fun read(pid: Int, state: String, wchan: String, mountInfo: String): Evidence =
        inspect(readText("/proc/$pid/cgroup"), state, wchan, mounts(mountInfo), ::readText)

    fun inspect(
        cgroup: String?,
        state: String,
        wchan: String,
        mounts: List<Mount>,
        readText: (String) -> String?
    ): Evidence {
        var running = false
        var transitioning = false
        for (line in cgroup.orEmpty().lineSequence()) {
            val fields = line.split(':', limit = 3)
            if (fields.size != 3) continue
            val v2 = fields[0] == "0" && fields[1].isEmpty()
            if (!v2 && "freezer" !in fields[1].split(',')) continue
            for (mount in mounts.filter { it.v2 == v2 }) {
                val leaf = mount.resolve(fields[2]) ?: continue
                if (v2) {
                    var directory = leaf
                    var leafUnfrozen = false
                    var pending = false
                    while (directory.startsWith(mount.path.trimEnd('/'))) {
                        val frozen = readText("$directory/cgroup.events")?.lineSequence()
                            ?.firstOrNull { it.startsWith("frozen ") }?.substringAfter(' ')?.trim()
                        if (frozen == "1") return Evidence(RootHelper.FreezeState.FROZEN, RootHelper.FreezerType.V2)
                        if (directory == leaf && frozen == "0") leafUnfrozen = true
                        if (readText("$directory/cgroup.freeze")?.trim() == "1") pending = true
                        if (directory == mount.path.trimEnd('/')) break
                        directory = directory.substringBeforeLast('/', "")
                        if (directory.isEmpty()) break
                    }
                    if (pending) transitioning = true
                    if (leafUnfrozen && !pending) running = true
                } else {
                    when (readText("$leaf/freezer.state")?.trim()) {
                        "FROZEN" -> return Evidence(RootHelper.FreezeState.FROZEN, RootHelper.FreezerType.V1)
                        "THAWED" -> running = true
                        "FREEZING" -> transitioning = true
                    }
                }
            }
        }
        // 普通 D 睡眠、get_signal 与 freezable_schedule 均不能独立证明冻结。
        if (state == "T") return Evidence(RootHelper.FreezeState.FROZEN, RootHelper.FreezerType.KILL)
        if (transitioning) return Evidence(RootHelper.FreezeState.UNKNOWN)
        if (running) return Evidence(RootHelper.FreezeState.RUNNING)
        if (wchan.contains("do_freezer_trap")) return Evidence(RootHelper.FreezeState.FROZEN, RootHelper.FreezerType.V2)
        if (wchan.contains("__refrigerator")) return Evidence(RootHelper.FreezeState.FROZEN, RootHelper.FreezerType.V1)
        return Evidence(RootHelper.FreezeState.UNKNOWN)
    }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private fun unescape(path: String): String = path
        .replace("\\040", " ").replace("\\011", "\t")
        .replace("\\012", "\n").replace("\\134", "\\")
}
