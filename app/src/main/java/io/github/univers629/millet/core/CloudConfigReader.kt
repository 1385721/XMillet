package io.github.univers629.millet.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 读取 PowerKeeper 的云端配置库（cloud_configure.db）。
 *
 * 这是**兜底路径**：正常情况下三条云名单由 powerkeeper 进程通过官方接口
 * `GlobalFeatureConfigureHelper` 读出并经 Binder 上报（见 `PowerKeeperCloudLists`），
 * App 侧直接吃现成的，不需要 Root。只有模块侧还没上报时（首次开机、HotReload 期间）
 * 才退回这里自己复制数据库解析。
 *
 * - 该库属于 com.miui.powerkeeper（uid 1000 system），App 进程没有读权限；
 * - 由 Root 将它复制到本应用缓存目录再以只读方式打开，读完即删；
 * - 云端下发的是纯文本包名列表，但分隔符按 key 各不相同：
 *   dozeWhiteListApps 用冒号、FrozenNewWhiteList 用分号、launchRestrict 用下划线分段。
 */
object CloudConfigReader {

    /**
     * PowerKeeper 配置库的候选位置。
     *
     * 澎湃 OS1 / OS2 把 PowerKeeper 的数据放在设备加密目录（`/data/user_de/0`），
     * 部分版本又把 `databases` 挂在自己的 files 下；OS3 则是常规的 `/data/data`。
     * 逐个试，命中第一个存在的就停。
     */
    private val SOURCE_CANDIDATES = listOf(
        "/data/data/com.miui.powerkeeper/databases/cloud_configure.db",
        "/data/user/0/com.miui.powerkeeper/databases/cloud_configure.db",
        "/data/user_de/0/com.miui.powerkeeper/databases/cloud_configure.db",
        "/data/user_de/0/com.miui.powerkeeper/files/cloud_configure.db",
    )

    data class CloudConfig(
        val dozeWhiteList: Set<String> = emptySet(),
        val frozenNewWhiteList: Set<String> = emptySet(),
        val launchRestrict: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean
            get() = dozeWhiteList.isEmpty() && frozenNewWhiteList.isEmpty() && launchRestrict.isEmpty()
    }

    private var cache: CloudConfig? = null

    /**
     * 读取失败后的退避窗口。
     *
     * 每次失败要试 4 条候选路径，每条都是一次 `su -M cp`；若每轮列表刷新都重试，
     * 就变成持续的后台 root 调用。失败后静默 30 秒，成功即清零。
     */
    private const val FAIL_BACKOFF_MS = 30_000L

    @Volatile
    private var failedUntil = 0L

    private var failedAttempts = 0

    /**
     * 读取一次并缓存。
     *
     * 只有「真正读到库」的结果才进缓存：Root 尚未授权、库拷贝失败等情况返回空配置且不缓存，
     * 下一次列表刷新会重新尝试 —— 否则一次过于乐观的空读会把云名单永久钉成空，
     * 应用策略页的云免冻/云墓碑白标签会一直为空。
     */
    fun load(context: Context, force: Boolean = false): CloudConfig {
        if (!force) cache?.let { return it }

        // Root 未授权不算失败：授权随时可能完成，不能因此进入退避。
        if (!RootHelper.isRootGranted) {
            Logger.debug("云端配置库跳过: Root 未授权")
            return CloudConfig()
        }
        if (System.currentTimeMillis() < failedUntil) return CloudConfig()

        val config = runCatching { readNow(context) }.getOrElse { t ->
            Logger.warn("云端配置库读取异常: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (config != null) {
            cache = config
            failedAttempts = 0
            failedUntil = 0L
            Logger.alwaysInfo(
                "云端配置库: doze=${config.dozeWhiteList.size} " +
                    "frozen=${config.frozenNewWhiteList.size} launch=${config.launchRestrict.size}"
            )
            return config
        }
        failedAttempts++
        failedUntil = System.currentTimeMillis() + FAIL_BACKOFF_MS
        Logger.warn(
            "云端配置库读取失败（第 $failedAttempts 次），${FAIL_BACKOFF_MS / 1000}s 内不再尝试"
        )
        return CloudConfig()
    }

    fun invalidate() {
        cache = null
    }

    /** 返回 null 表示这次没读到库（拷贝失败 / 打开失败），调用方应稍后重试。 */
    private fun readNow(context: Context): CloudConfig? {
        // 拷到本应用私有缓存目录再以只读方式打开，读完即删。
        // 必须用 `su -M`（全局挂载命名空间）：应用自己的命名空间看不到 com.miui.powerkeeper 的数据目录。
        val target = File(context.cacheDir, "cloud_configure.db")
        var copiedFrom: String? = null
        for (source in SOURCE_CANDIDATES) {
            runCatching { if (target.exists()) target.delete() }
            RootHelper.execSu("cp \"$source\" \"${target.absolutePath}\"", mountMaster = true)
            if (target.exists() && target.length() > 0L) {
                copiedFrom = source
                break
            }
        }
        val source = copiedFrom
        if (source == null) {
            Logger.warn("云端配置库不可读：候选路径均不存在（${SOURCE_CANDIDATES.size} 个）")
            return null
        }
        Logger.debug("云端配置库来源: $source")
        runCatching { RootHelper.execSu("chmod 666 \"${target.absolutePath}\"", mountMaster = true) }

        val values = mutableMapOf<String, String>()
        try {
            val db = SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db.use { database ->
                database.rawQuery(
                    "SELECT configureName, configureParam FROM GlobalFeatureTable " +
                        "WHERE configureName IN ('dozeWhiteListApps','FrozenNewWhiteList','launchRestrict')",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        values[cursor.getString(0)] = cursor.getString(1).orEmpty()
                    }
                }
            }
        } finally {
            runCatching { target.delete() }
        }

        return CloudConfig(
            dozeWhiteList = split(values["dozeWhiteListApps"], ':'),
            frozenNewWhiteList = split(values["FrozenNewWhiteList"], ';'),
            launchRestrict = parseLaunchRestrict(values["launchRestrict"]),
        )
    }

    private fun split(raw: String?, separator: Char): Set<String> =
        raw.orEmpty()
            .split(separator)
            .map { it.trim() }
            .filter { it.contains('.') }
            .toSet()

    /**
     * launchRestrict 形如 `enable_24_allowlist_com.a.b_com.c.d_...`：
     * 前半段是策略名，后半段用下划线拼接包名（包名内部本身也有点，故只按 `_` 切分后筛出带点的片段）。
     */
    private fun parseLaunchRestrict(raw: String?): Set<String> =
        raw.orEmpty()
            .split('_')
            .map { it.trim() }
            .filter { it.contains('.') && !it.contains(' ') }
            .toSet()
}
