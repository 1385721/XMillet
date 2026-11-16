package io.github.univers629.millet.core

/**
 * 云名单的解析与跨进程编码。
 *
 * PowerKeeper 云端配置库里有三条与应用策略相关的名单，键名与分隔符各不相同：
 * - `dozeWhiteListApps`：冒号分隔
 * - `FrozenNewWhiteList`：分号分隔
 * - `launchRestrict`：下划线分段，形如 `enable_24_allowlist_com.a.b_com.c.d`
 *
 * 同一套解析既要给「官方接口读出来的原始字符串」用，也要给「模块缺席时直接读库」的兜底
 * 路径用，所以放在 core 里由两侧共用，避免两份实现慢慢漂移。
 */
object CloudListCodec {

    data class CloudLists(
        val dozeWhiteList: Set<String> = emptySet(),
        val frozenNewWhiteList: Set<String> = emptySet(),
        val launchRestrict: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean
            get() = dozeWhiteList.isEmpty() && frozenNewWhiteList.isEmpty() && launchRestrict.isEmpty()
    }

    /**
     * 合法包名：至少两段、只含字母数字下划线、段首不为数字。
     *
     * 比「包含点号」严格：`launchRestrict` 里混着 `enable`、`allowlist`、`24` 这类策略词，
     * 只按点号过滤会把它们带进名单。
     */
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** 按指定分隔符切分并只保留合法包名。 */
    fun parseDelimited(raw: String?, separator: Char): Set<String> =
        raw.orEmpty()
            .split(separator)
            .map { it.trim() }
            .filter { PACKAGE_NAME.matches(it) }
            .toSet()

    /** `launchRestrict` 形如 `enable_24_allowlist_com.a.b_com.c.d`：按 `_` 切开后只留包名片段。 */
    fun parseLaunchRestrict(raw: String?): Set<String> =
        raw.orEmpty()
            .split('_')
            .map { it.trim() }
            .filter { PACKAGE_NAME.matches(it) }
            .toSet()

    /** 编码为 `doze=a,b;frozen=c,d;launch=e,f`。包名不含 `,`/`;`/`=`，可安全用这三个字符分段。 */
    fun encode(lists: CloudLists): String = buildString {
        append("doze=").append(lists.dozeWhiteList.joinToString(","))
        append(";frozen=").append(lists.frozenNewWhiteList.joinToString(","))
        append(";launch=").append(lists.launchRestrict.joinToString(","))
    }

    /** 解码 [encode] 的产物；空串或畸形输入返回 null，由调用方决定是否走兜底路径。 */
    fun decode(raw: String?): CloudLists? {
        if (raw.isNullOrEmpty()) return null
        var doze: Set<String> = emptySet()
        var frozen: Set<String> = emptySet()
        var launch: Set<String> = emptySet()
        var seen = false
        for (segment in raw.split(';')) {
            val index = segment.indexOf('=')
            if (index <= 0) continue
            val value = segment.substring(index + 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            when (segment.substring(0, index)) {
                "doze" -> { doze = value; seen = true }
                "frozen" -> { frozen = value; seen = true }
                "launch" -> { launch = value; seen = true }
            }
        }
        return if (seen) CloudLists(doze, frozen, launch) else null
    }
}
