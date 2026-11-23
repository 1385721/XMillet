package io.github.univers629.millet.core

import java.util.Base64
import java.util.EnumSet

/**
 * 应用级七项策略的统一描述。
 *
 * 每一项直接对应 [PrefsKeys] 里的一个集合键；模板与批量套用都只读写这七个集合，
 * 不引入新的存储结构，Hook 侧无需感知「模板」这个概念。
 */
enum class PolicyFlag(
    val key: String,
    val label: String,
    val summary: String,
) {
    WHITE(
        PrefsKeys.WHITE_APP_SET,
        "免冻白名单",
        "永不冻结；主用户应用会同步把系统省电策略设为无限制",
    ),
    MESSAGE(
        PrefsKeys.MESSAGE_APP_SET,
        "消息应用",
        "收到网络推送包后短暂解冻处理消息",
    ),
    NOT_FREEZE_FGS(
        PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET,
        "前台服务时不冻结",
        "检测到活跃前台服务（播放、导航、下载）时阻止冻结",
    ),
    FORCE_FREEZE(
        PrefsKeys.FORCE_FREEZE_SET,
        "强制冻结",
        "向官方链路请求冻结并限制已适配的后台豁免",
    ),
    BLOCK_THAW(
        PrefsKeys.BLOCK_THAW_SET,
        "禁止后台解冻",
        "仅放行 Activity 启动与切换前台解冻（依附强制冻结）",
    ),
    FORBID_SYNC(
        PrefsKeys.FORBID_SYNC_BINDER_SET,
        "阻止同步 Binder 唤醒",
        "拦截 Sync / Check Binder 与 MilletHandler 唤醒（依附强制冻结）",
    ),
    IGNORE_NETWORK_WAKE(
        PrefsKeys.IGNORE_NETWORK_WAKE_SET,
        "忽略网络唤醒",
        "推送与网络变化不再唤醒，消息会延迟到手动打开（依附强制冻结）",
    );

    val bit: Int
        get() = 1 shl ordinal

    /** 是否只能在强制冻结开启时出现；与配置页「强力冻结」卡片内的折叠区一致。 */
    val dependsOnForceFreeze: Boolean
        get() = this == BLOCK_THAW || this == FORBID_SYNC || this == IGNORE_NETWORK_WAKE

    companion object {
        fun fromMask(mask: Int): Set<PolicyFlag> =
            entries.filterTo(EnumSet.noneOf(PolicyFlag::class.java)) { (mask and it.bit) != 0 }

        fun toMask(flags: Set<PolicyFlag>): Int = flags.fold(0) { acc, flag -> acc or flag.bit }

        /** 按枚举顺序输出中文标签，供摘要行使用。 */
        fun describe(flags: Set<PolicyFlag>): String =
            if (flags.isEmpty()) "未配置" else entries.filter { it in flags }.joinToString(" · ") { it.label }
    }
}

/**
 * 策略模板：七项策略的一个合法组合。
 *
 * 套用时会把目标应用在七个集合中的成员关系一次性写成 [flags]，
 * 不在 [flags] 里的策略一律关闭，因此模板之间天然互斥，一个应用同一时间最多匹配一种组合。
 */
data class PolicyTemplate(
    val id: String,
    val name: String,
    val flags: Set<PolicyFlag>,
    val description: String,
    val builtIn: Boolean,
) {
    /** 应用当前状态与模板完全一致才算「在用」，部分重合不算。 */
    fun matches(current: Set<PolicyFlag>): Boolean = current == flags
}

/**
 * 七项策略的互斥与依附规则。
 *
 * 以 `AppProfileScreenMiuix` 中各开关的 `onCheckedChange` 为权威实现逐条复刻，
 * 模板编辑器只能通过 [toggle] 改变组合，所以模板能到达的状态与配置页完全一致，
 * 套用模板与在配置页逐个拨动开关的落盘结果等价。
 */
object PolicyRules {

    /** 模拟在配置页上把 [flag] 拨到 [checked] 后的完整状态。 */
    fun toggle(current: Set<PolicyFlag>, flag: PolicyFlag, checked: Boolean): Set<PolicyFlag> {
        val next = EnumSet.noneOf(PolicyFlag::class.java).apply { addAll(current) }
        if (!checked) {
            next.remove(flag)
            if (flag == PolicyFlag.FORCE_FREEZE) {
                // 关闭强制冻结时，依附项随折叠区一起清空。
                next.remove(PolicyFlag.BLOCK_THAW)
                next.remove(PolicyFlag.FORBID_SYNC)
                next.remove(PolicyFlag.IGNORE_NETWORK_WAKE)
            }
            return next
        }
        when (flag) {
            PolicyFlag.WHITE -> {
                next.clear()
                next.add(PolicyFlag.WHITE)
            }

            PolicyFlag.MESSAGE -> {
                next.add(PolicyFlag.MESSAGE)
                next.remove(PolicyFlag.WHITE)
                next.remove(PolicyFlag.IGNORE_NETWORK_WAKE)
            }

            PolicyFlag.NOT_FREEZE_FGS -> {
                next.add(PolicyFlag.NOT_FREEZE_FGS)
                next.remove(PolicyFlag.WHITE)
                if (PolicyFlag.FORCE_FREEZE in current) {
                    next.remove(PolicyFlag.FORCE_FREEZE)
                    next.remove(PolicyFlag.BLOCK_THAW)
                    next.remove(PolicyFlag.FORBID_SYNC)
                    next.remove(PolicyFlag.IGNORE_NETWORK_WAKE)
                }
            }

            PolicyFlag.FORCE_FREEZE -> {
                next.add(PolicyFlag.FORCE_FREEZE)
                next.remove(PolicyFlag.WHITE)
                next.remove(PolicyFlag.NOT_FREEZE_FGS)
            }

            PolicyFlag.BLOCK_THAW, PolicyFlag.FORBID_SYNC -> {
                // 配置页上这两个开关只在强制冻结展开后可见。
                if (PolicyFlag.FORCE_FREEZE in next) next.add(flag)
            }

            PolicyFlag.IGNORE_NETWORK_WAKE -> {
                if (PolicyFlag.FORCE_FREEZE in next) {
                    next.add(PolicyFlag.IGNORE_NETWORK_WAKE)
                    next.remove(PolicyFlag.MESSAGE)
                }
            }
        }
        return next
    }

    /** 判断组合能否在配置页上被拨出来；用于校验持久化的自定义模板。 */
    fun isValid(flags: Set<PolicyFlag>): Boolean {
        if (PolicyFlag.WHITE in flags && flags.size != 1) return false
        if (flags.any { it.dependsOnForceFreeze } && PolicyFlag.FORCE_FREEZE !in flags) return false
        if (PolicyFlag.FORCE_FREEZE in flags && PolicyFlag.NOT_FREEZE_FGS in flags) return false
        if (PolicyFlag.MESSAGE in flags && PolicyFlag.IGNORE_NETWORK_WAKE in flags) return false
        return true
    }
}

/** 内置预设模板，ID 固定，便于路由与未来迁移。 */
object PolicyTemplatePresets {

    val ALL: List<PolicyTemplate> = listOf(
        PolicyTemplate(
            id = "builtin_unrestricted",
            name = "完全放行",
            flags = setOf(PolicyFlag.WHITE),
            description = "永不冻结。适合输入法、桌面插件、自动化工具等必须常驻的应用；主用户应用会同步系统省电策略为无限制。",
            builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin_message",
            name = "消息保活",
            flags = setOf(PolicyFlag.MESSAGE),
            description = "正常冻结，收到推送时短暂解冻处理消息。适合聊天、邮件、待办提醒类应用。",
            builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin_fgs",
            name = "前台保活",
            flags = setOf(PolicyFlag.NOT_FREEZE_FGS),
            description = "有活跃前台服务时不冻结。适合音乐、导航、下载、运动记录等后台持续工作的应用。",
            builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin_battery",
            name = "省电优先",
            flags = setOf(PolicyFlag.FORCE_FREEZE, PolicyFlag.BLOCK_THAW, PolicyFlag.FORBID_SYNC),
            description = "强冻结并压制后台解冻与同步 Binder 唤醒，基本只在打开时运行。适合偶尔使用、爱自启的应用。",
            builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin_offline",
            name = "离线冻结",
            flags = setOf(
                PolicyFlag.FORCE_FREEZE,
                PolicyFlag.BLOCK_THAW,
                PolicyFlag.FORBID_SYNC,
                PolicyFlag.IGNORE_NETWORK_WAKE,
            ),
            description = "在省电优先的基础上忽略网络唤醒，冻结会一直保持到手动打开，代价是消息延迟送达。",
            builtIn = true,
        ),
    )

    fun find(id: String): PolicyTemplate? = ALL.firstOrNull { it.id == id }
}

/**
 * 自定义模板的持久化编码。
 *
 * 格式：首行 `v1`，之后每行 `id;mask;base64url(name)`。名称做 Base64 是为了避开分隔符与换行，
 * 不依赖 org.json，便于在 JVM 单测中直接验证。非法行（ID 冲突、组合不合法、名称为空）在解码时丢弃，
 * 保证 UI 拿到的模板一定能被配置页复现。
 */
object PolicyTemplateCodec {

    private const val HEADER = "v1"
    const val CUSTOM_ID_PREFIX = "custom_"
    const val MAX_NAME_LENGTH = 16

    fun encode(templates: List<PolicyTemplate>): String = buildString {
        append(HEADER)
        for (template in templates) {
            if (template.builtIn) continue
            append('\n')
            append(template.id)
            append(';')
            append(PolicyFlag.toMask(template.flags))
            append(';')
            append(
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(template.name.toByteArray(Charsets.UTF_8))
            )
        }
    }

    fun decode(raw: String?): List<PolicyTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        val result = ArrayList<PolicyTemplate>()
        val seenIds = HashSet<String>()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(';')
            if (parts.size != 3) continue
            val id = parts[0]
            if (!id.startsWith(CUSTOM_ID_PREFIX) || !seenIds.add(id)) continue
            val mask = parts[1].toIntOrNull() ?: continue
            val flags = PolicyFlag.fromMask(mask)
            if (flags.isEmpty() || !PolicyRules.isValid(flags)) continue
            val name = runCatching {
                String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
            }.getOrNull()?.trim().orEmpty()
            if (name.isEmpty()) continue
            result.add(custom(id, name.take(MAX_NAME_LENGTH), flags))
        }
        return result
    }

    /** 构造自定义模板；描述由组合自动生成，避免用户写的说明与实际策略不符。 */
    fun custom(id: String, name: String, flags: Set<PolicyFlag>): PolicyTemplate = PolicyTemplate(
        id = id,
        name = name,
        flags = flags,
        description = "自定义组合：${PolicyFlag.describe(flags)}",
        builtIn = false,
    )
}
