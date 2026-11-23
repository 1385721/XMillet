package io.github.univers629.millet.core

import android.content.Context
import android.provider.Settings

/**
 * 把模块的受保护应用同步进系统侧的「不限制」名单 `MILLET_NO_RESTRICT_APP`。
 *
 * 该键是 HyperOS 中 millet 内核模块（`millet_pkg` / `millet_sig`）、PowerKeeper
 * 与 Greeze 共同读取的白名单：名单内的应用不会被冻结、不会被打断信号。
 * 写它不需要改内核，也不需要特殊权限 —— 本模块的 system 作用域运行在
 * uid 1000 的 system_server 里，天然持有 WRITE_SETTINGS。
 *
 * [TRACKING_KEY] 记录本模块新添加或历史已记账的主用户包名。
 * 同步保留未记账的系统条目，仅回收已记账且不再需要的条目。
 * 包名级设置无法识别同名条目的后续外部写入。
 */
object NoRestrictSync {

    /** 系统侧的免冻结名单键（Settings.System）。 */
    const val SYSTEM_KEY = "MILLET_NO_RESTRICT_APP"

    /** 本模块维护的记账键，用于识别哪些条目是模块写进去的。 */
    const val TRACKING_KEY = "xmillet_no_restrict_written"

    private const val SEPARATOR = ", "

    /**
     * 把系统里的原始字符串拆成包名集合，去掉空白与空串。
     */
    fun split(raw: String?): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        if (raw.isNullOrBlank()) {
            return result
        }
        for (token in raw.split(',')) {
            val name = token.trim()
            if (name.isNotEmpty()) {
                result.add(name)
            }
        }
        return result
    }

    /**
     * 读取系统名单当前内容（包名集合）。
     */
    fun readSystemList(context: Context): LinkedHashSet<String> =
        split(readSetting(context, SYSTEM_KEY))

    /**
     * 计算同步后的系统名单，纯函数，便于单测与日志预览。
     *
     * @param current 系统名单当前内容
     * @param writtenByUs 上一次由模块写入的条目
     * @param desired 本次希望保护的应用（模块白名单）
     */
    fun resolve(
        current: Set<String>,
        writtenByUs: Set<String>,
        desired: Set<String>
    ): LinkedHashSet<String> {
        val next = LinkedHashSet(current)
        next.removeAll(AppUidUtils.policyPackagesForUser(writtenByUs, AppUidUtils.PRIMARY_USER_ID))
        next.addAll(AppUidUtils.policyPackagesForUser(desired, AppUidUtils.PRIMARY_USER_ID))
        return next
    }

    /**
     * 把 [desired] 同步进系统名单。
     *
     * @return true 表示确实写入了系统设置；false 表示无写入（相同、无权限或被跳过）。
     */
    fun sync(context: Context?, desired: Collection<String>): Boolean {
        if (context == null) {
            Logger.alwaysWarn("免冻结名单同步跳过: 没有可用的 Context")
            return false
        }
        // 系统表按包名生效，双开目标不能投影为主用户包名。
        val wanted = AppUidUtils.policyPackagesForUser(desired.toSet(), AppUidUtils.PRIMARY_USER_ID)
        val (current, written) = try {
            split(Settings.System.getString(context.contentResolver, SYSTEM_KEY)) to
                split(Settings.System.getString(context.contentResolver, TRACKING_KEY))
        } catch (t: Throwable) {
            Logger.alwaysWarn("免冻结名单同步读取失败: ${t.message}")
            return false
        }
        val next = resolve(current, written, wanted)
        val owned = LinkedHashSet<String>()
        for (name in wanted) {
            if (name in written || name !in current) owned.add(name)
        }
        if (next == current && written == owned) {
            Logger.alwaysInfo(
                "免冻结名单无需变更: 系统 ${current.size} 项, 白名单 ${wanted.size} 项"
            )
            return false
        }
        return try {
            putSetting(context, SYSTEM_KEY, next.joinToString(SEPARATOR))
            putSetting(context, TRACKING_KEY, owned.joinToString(SEPARATOR))
            val added = next.count { it !in current }
            val removed = current.count { it !in next }
            Logger.alwaysInfo(
                "免冻结名单同步: 系统名单 ${next.size} 项 (新增 $added, 回收 $removed)"
            )
            true
        } catch (t: Throwable) {
            Logger.alwaysWarn("免冻结名单同步失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun readSetting(context: Context, key: String): String? = try {
        Settings.System.getString(context.contentResolver, key)
    } catch (t: Throwable) {
        Logger.warn("读取系统设置 $key 失败: ${t.message}")
        null
    }

    private fun putSetting(context: Context, key: String, value: String) {
        check(Settings.System.putString(context.contentResolver, key, value)) { "Cannot write system setting $key" }
    }
}
