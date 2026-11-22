package io.github.univers629.millet.powerkeeper

import android.content.Context
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.Logger

/**
 * 在 `com.miui.powerkeeper` 进程内通过官方接口读取云端下发的三条应用名单。
 *
 * 澎湃 1.0 起 PowerKeeper 自带
 * `com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper`，静态方法
 * `getDozeWhiteListApps` / `getCloudFrozenNewWhiteList` / `getCloudLaunchRestrict`
 * 就是系统自己给这三条云名单准备的读取入口（系统设置、省电策略页都走它）。
 *
 * 在 powerkeeper 自己的进程里调它，无需 App 借 Root 复制 `cloud_configure.db`
 * 再自行解析 SQLite，也不会因为挂载命名空间、库文件位置变化而失效。
 * 反射而不是直接引用，是为了在 OS1/OS2 上缺失该类时安静跳过（那时 App 侧仍有兜底）。
 */
object PowerKeeperCloudLists {

    private const val CLASS_HELPER = "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper"

    /** 模块自身读取原始云名单上报给 UI 时置 true，使 P30 读取拦截放行本次调用。 */
    private val bypassReadFilter = ThreadLocal<Boolean>()

    internal fun isBypassingFilter(): Boolean = bypassReadFilter.get() == true

    /** 返回 null 表示官方接口不可用（旧 ROM / 反射失败），调用方应走兜底路径。 */
    fun read(context: Context?): CloudListCodec.CloudLists? {
        val ctx = context ?: return null
        val helper = runCatching { Class.forName(CLASS_HELPER, false, ctx.classLoader) }.getOrNull()
            ?: run {
                Logger.warn("云名单: 本版本没有 $CLASS_HELPER，改用 App 侧兜底")
                return null
            }

        bypassReadFilter.set(true)
        val doze: Set<String>
        val frozenRaw: String?
        val launchRaw: String?
        try {
            doze = readDozeWhiteList(helper, ctx)
            frozenRaw = invokeString(helper, "getCloudFrozenNewWhiteList", ctx)
            launchRaw = invokeString(helper, "getCloudLaunchRestrict", ctx)
        } finally {
            bypassReadFilter.remove()
        }

        if (doze.isEmpty() && frozenRaw.isNullOrEmpty() && launchRaw.isNullOrEmpty()) {
            return null
        }

        // 冻结新白名单与启动放行的原始串在历史版本里分隔符不统一，先按主分隔符解析，
        // 解析不出包名时再换一个再试，避免因为一个字符就让整条名单消失。
        val frozen = CloudListCodec.parseDelimited(frozenRaw, ';')
            .ifEmpty { CloudListCodec.parseDelimited(frozenRaw, ':') }
        val launch = CloudListCodec.parseLaunchRestrict(launchRaw)
            .ifEmpty { CloudListCodec.parseDelimited(launchRaw, ';') }

        return CloudListCodec.CloudLists(doze, frozen, launch)
    }

    private fun readDozeWhiteList(helper: Class<*>, ctx: Context): Set<String> {
        val result = invoke(helper, "getDozeWhiteListApps", ctx) ?: return emptySet()
        // 正常是 List<String>；个别版本直接给原始串，两种都接。
        return when (result) {
            is Collection<*> -> result.mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            is String -> CloudListCodec.parseDelimited(result, ':')
                .ifEmpty { CloudListCodec.parseDelimited(result, ';') }
            else -> emptySet()
        }
    }

    private fun invokeString(helper: Class<*>, name: String, ctx: Context): String? =
        (invoke(helper, name, ctx) as? String)?.takeIf { it.isNotEmpty() }

    /**
     * 调 `name(Context)` 形式的静态方法。
     *
     * 只按「参数是一个 Context 子类型」挑选，避免误撞同名但带 Bundle/Flags 的重载。
     */
    private fun invoke(helper: Class<*>, name: String, ctx: Context): Any? {
        val method = runCatching {
            helper.methods.firstOrNull {
                it.name == name &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(Context::class.java)
            }
        }.getOrNull() ?: return null
        return runCatching { method.invoke(null, ctx) }.getOrElse { t ->
            Logger.warn("云名单: 调用 $name 失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }
}
