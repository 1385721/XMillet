package io.github.univers629.millet.core

/**
 * 安装结果账本：记录本进程内每一条 [HookSpec] 的最终归宿。
 *
 * 动机：界面上的安装点数量曾长期写死（"40 Hooks"），而实际规格数随版本增长到 51 条以上，
 * 且不同 ROM 会因宿主类/方法缺失、门控不满足而跳过若干点位。把「装了什么」记账下来，
 * 才能让首页徽标与右上角检测弹窗显示真实的覆盖情况，而不是一个固定常量。
 *
 * 每个进程各持一份：system_server 只记 S* 点位，com.miui.powerkeeper 只记 P* 点位，
 * 两侧结果通过 Binder 汇总到 App 侧展示。
 */
object HookInstallRegistry {

    /** 单条点位的归宿 */
    enum class State {
        /** 已成功拦截 */
        INSTALLED,

        /** 本 ROM 版本没有该目标类/方法，按能力矩阵跳过 */
        SKIPPED_UNSUPPORTED,

        /** 目标存在，但版本门控（例如仅 OS1.0 生效）不满足 */
        SKIPPED_GATED,

        /** 目标存在且门控通过，但安装时抛异常 */
        FAILED
    }

    data class Record(
        val id: String,
        val name: String,
        val state: State,
        val detail: String = ""
    )

    private val records = LinkedHashMap<String, Record>()

    /** 记录一条点位的最终状态（同 id 覆盖，便于重复安装时取最后一次结果）。 */
    @Synchronized
    fun record(id: String, name: String, state: State, detail: String = "") {
        records[id] = Record(id, name, state, detail)
    }

    /**
     * 清空账本。
     *
     * 仅用于热重载：旧一代的 Hook 已被卸载，新一代码会重新装机，
     * 不清空就会把上一代的结果（含失败/跳过）混进本轮统计。
     */
    @Synchronized
    fun clear() {
        records.clear()
    }

    /** 已安装条数 */
    @Synchronized
    fun installedCount(): Int = records.values.count { it.state == State.INSTALLED }

    /** 已记账的规格总数（安装 + 各类跳过 + 失败） */
    @Synchronized
    fun totalCount(): Int = records.size

    /** 本版本不支持而跳过的点位 id */
    @Synchronized
    fun unsupportedIds(): List<String> =
        records.values.filter { it.state == State.SKIPPED_UNSUPPORTED }.map { it.id }

    /** 门控不满足而跳过的点位 id */
    @Synchronized
    fun gatedIds(): List<String> =
        records.values.filter { it.state == State.SKIPPED_GATED }.map { it.id }

    /** 安装失败的点位 id */
    @Synchronized
    fun failedIds(): List<String> =
        records.values.filter { it.state == State.FAILED }.map { it.id }

    /** 全部记账快照（用于诊断展示） */
    @Synchronized
    fun snapshot(): List<Record> = records.values.toList()

    /** 形如 `42/48` 的覆盖比 */
    @Synchronized
    fun ratio(): String = "${installedCount()}/${totalCount()}"

    /**
     * 一条可直接写进日志/回传给 App 的摘要：
     * `installed=42/48 skipped=S10,S14 failed=-`
     */
    @Synchronized
    fun summary(): String {
        val unsupported = unsupportedIds()
        val gated = gatedIds()
        val failed = failedIds()
        return buildString {
            append("installed=").append(ratio())
            append(" unsupported=").append(if (unsupported.isEmpty()) "-" else unsupported.joinToString(","))
            if (gated.isNotEmpty()) {
                append(" gated=").append(gated.joinToString(","))
            }
            append(" failed=").append(if (failed.isEmpty()) "-" else failed.joinToString(","))
        }
    }

    /** 把记账结果写成固定格式的键值对（跨进程回传用，见 MilletIpcContract）。 */
    @Synchronized
    fun toWireFormat(): String {
        return buildString {
            append("i=").append(installedCount())
            append(';').append("t=").append(totalCount())
            append(';').append("u=").append(unsupportedIds().joinToString(","))
            append(';').append("g=").append(gatedIds().joinToString(","))
            append(';').append("f=").append(failedIds().joinToString(","))
        }
    }

    /**
     * [toWireFormat] 的解码结果。
     *
     * 字段为 -1 / 空列表表示「对端没有上报」——用于区分「确实一个都没装」与
     * 「对端模块版本较老、没有这个字段」，避免把未知显示成 0。
     */
    data class Wire(
        val installed: Int = -1,
        val total: Int = -1,
        val unsupported: List<String> = emptyList(),
        val gated: List<String> = emptyList(),
        val failed: List<String> = emptyList()
    ) {
        /** 对端是否真的上报了数据 */
        val hasData: Boolean get() = total >= 0
    }

    /** 解码 [toWireFormat] 的输出；格式不认识时返回空 [Wire] 而不是抛异常。 */
    fun decodeWireFormat(raw: String?): Wire {
        if (raw.isNullOrBlank()) return Wire()
        val fields = raw.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()
        fun ids(key: String): List<String> =
            fields[key].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return Wire(
            installed = fields["i"]?.toIntOrNull() ?: -1,
            total = fields["t"]?.toIntOrNull() ?: -1,
            unsupported = ids("u"),
            gated = ids("g"),
            failed = ids("f")
        )
    }
}
