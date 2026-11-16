package io.github.univers629.millet.system

import io.github.univers629.millet.core.Logger
import java.io.File

/**
 * 在 system_server 内判定内核给墓碑提供的冻结器类型（cgroup v2 / v1 / 无）。
 *
 * App 进程读不到 `/sys/fs/cgroup` 下的控制文件与 `/proc/<pid>/wchan`（SELinux `app_data_file`
 * 之外还有 cgroup 的访问限制），原来只能靠 root 跑一次 wchan 采样，于是每开一次检测弹窗
 * 就可能弹一次超级用户提示。system_server 自己有权限，这里改成在模块里判定、经 Binder 回传。
 */
object KernelFreezerDetector {

    private const val SAMPLE_LIMIT = 400

    /** 返回 "V2" / "V1" / "NONE" / "UNKNOWN"，与 App 侧 RootHelper.KernelFreezer 一一对应。 */
    fun detect(): String {
        val mode = runCatching { detectUncached() }.getOrElse {
            Logger.warn("内核冻结器探测失败: ${it.javaClass.simpleName}: ${it.message}")
            "UNKNOWN"
        }
        // 用 alwaysInfo 而不是 debug：App 侧要以模块日志证明「这次判定来自 system_server，没走 root」。
        Logger.alwaysInfo("内核冻结器: $mode")
        return mode
    }

    private fun detectUncached(): String {
        // 1) 运行期铁证：抽一批 pid 看 wchan（真冻结时是 __refrigerator / do_freezer_trap）
        sampleWchan()?.let { return it }

        // 2) cgroup v1 freezer 挂载（Android 13 及更早 + 4.14/4.19 内核）
        val mountInfo = runCatching { File("/proc/self/mountinfo").readText() }.getOrDefault("")
        if (mountInfo.lineSequence().any { it.contains(" - cgroup ") && it.contains("freezer") }) {
            return "V1"
        }

        // 3) cgroup v2 的 freezer 控制文件
        val v2Files = listOf(
            "/sys/fs/cgroup/apps/cgroup.freeze",
            "/sys/fs/cgroup/system/cgroup.freeze",
            "/sys/fs/cgroup/cgroup.freeze",
            "/sys/fs/cgroup/uid_0/cgroup.freeze",
            "/sys/fs/cgroup/frozen"
        )
        if (v2Files.any(::exists)) return "V2"

        // 4) 旧版 v1 freezer
        if (exists("/sys/fs/cgroup/freezer") || exists("/dev/freezer")) return "V1"

        // 5) 挂了 cgroup2 就说明内核具备 v2 冻结能力
        if (mountInfo.lineSequence().any { it.contains(" cgroup2 ") }) return "V2"

        return "NONE"
    }

    private fun sampleWchan(): String? {
        val pids = runCatching {
            File("/proc").list()?.filter { it.all(Char::isDigit) }?.take(SAMPLE_LIMIT)
        }.getOrNull() ?: return null
        for (pid in pids) {
            val wchan = runCatching {
                File("/proc/$pid/wchan").readText().trim()
            }.getOrNull().orEmpty()
            when {
                wchan.contains("__refrigerator") -> return "V1"
                wchan.contains("do_freezer_trap") || wchan.contains("freezable_schedule") -> return "V2"
            }
        }
        return null
    }

    private fun exists(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)
}
