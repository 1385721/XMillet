package io.github.univers629.millet.core

/**
 * 冻结方式的统一词表。
 *
 * 同一个概念此前在三个页面各写了一份，口径不一致：
 * - 首页列表：`V2` / `V1` / `Kill` / `墓碑`
 * - 应用详情：`❄️ V2 墓碑` / `❄️ V1 墓碑` / `🛑 SIGSTOP` / `❄️ 已冻结` / `🟢 活跃等待`
 * - 关于页：`CGroup v2`（内核能力，不是单个应用的状态）
 *
 * 这里收敛成两层：
 * 1. **进程冻结方式**（[processLabel] / [shortLabel] / [colorHex]）：由 `wchan` 判定，
 *    判不出来但确实处于冻结记账里时统一叫「墓碑」，不再冒充 V2；
 * 2. **内核冻结器能力**（[kernelLabel]）：只能说明这台设备用哪套冻结机制，与具体应用无关。
 */
object FreezeModeLabels {

    /** 判不出内核标记、但系统记账里已冻结时的中性叫法。 */
    const val TOMBSTONE = "墓碑"

    /** 进程冻结方式的完整文案（详情页用）。 */
    fun processLabel(type: RootHelper.FreezerType): String = when (type) {
        RootHelper.FreezerType.V2 -> "V2 墓碑"
        RootHelper.FreezerType.V1 -> "V1 墓碑"
        RootHelper.FreezerType.KILL -> "SIGSTOP 挂起"
        RootHelper.FreezerType.UNKNOWN -> TOMBSTONE
    }

    /** 进程冻结方式的紧凑文案（列表徽章用，长度受限）。 */
    fun shortLabel(type: RootHelper.FreezerType): String = when (type) {
        RootHelper.FreezerType.V2 -> "V2"
        RootHelper.FreezerType.V1 -> "V1"
        RootHelper.FreezerType.KILL -> "Kill"
        RootHelper.FreezerType.UNKNOWN -> TOMBSTONE
    }

    /** 方式对应的主题色；`UNKNOWN` 交给调用方用中性配色。 */
    fun colorHex(type: RootHelper.FreezerType): Long? = when (type) {
        RootHelper.FreezerType.V2 -> 0xFFFF8C00
        RootHelper.FreezerType.V1 -> 0xFF0C82FD
        RootHelper.FreezerType.KILL -> 0xFF3CB371
        RootHelper.FreezerType.UNKNOWN -> null
    }

    /** 内核冻结器能力的完整文案（关于页用），与进程状态区分开。 */
    fun kernelLabel(mode: RootHelper.KernelFreezer): String = "内核冻结器 ${mode.label}"
}
