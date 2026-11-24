package io.github.univers629.millet.ui.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import io.github.univers629.millet.core.FreezeStatsSnapshotData
import io.github.univers629.millet.core.InterceptRule
import io.github.univers629.millet.core.StatsEventKind
import io.github.univers629.millet.core.StatsTimeBucket
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.core.WakeCategory
import io.github.univers629.millet.ipc.MilletIpcContract
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.model.AppFreezeStats
import io.github.univers629.millet.ui.model.AppIconSource
import io.github.univers629.millet.ui.model.FreezeStatsSnapshot
import io.github.univers629.millet.ui.model.StatsLoadState
import io.github.univers629.millet.ui.model.StatsRecentEvent
import io.github.univers629.millet.ui.util.StatsPackageMember
import io.github.univers629.millet.ui.util.sortedStatsMemberPackages
import io.github.univers629.millet.ui.util.statsGroupLabel
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 墓碑统计数据源抽象（支持纯 JVM ViewModel 单测替换）。
 */
interface FreezeStatsDataSource {
    suspend fun load(
        window: StatsWindow,
        serviceAlive: Boolean,
        statsEnabled: Boolean,
    ): StatsLoadState

    suspend fun loadSample(window: StatsWindow): StatsLoadState

    suspend fun reset(): Boolean
}

class AndroidFreezeStatsDataSource(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FreezeStatsDataSource {

    private val appContext: Context = context.applicationContext ?: context

    override suspend fun load(
        window: StatsWindow,
        serviceAlive: Boolean,
        statsEnabled: Boolean,
    ): StatsLoadState = FreezeStatsRepository.load(
        context = appContext,
        window = window,
        serviceAlive = serviceAlive,
        statsEnabled = statsEnabled,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun loadSample(window: StatsWindow): StatsLoadState =
        FreezeStatsRepository.loadSample(appContext, window, ioDispatcher)

    override suspend fun reset(): Boolean = FreezeStatsRepository.reset(ioDispatcher)
}

object FreezeStatsRepository {

    private data class ResolvedAppMeta(
        val packageName: String,
        val label: String,
        val iconSource: AppIconSource?,
        val isSystem: Boolean = false,
        val isDualApp: Boolean = false,
        val members: List<StatsPackageMember> = emptyList(),
    )

    suspend fun load(
        context: Context,
        window: StatsWindow,
        serviceAlive: Boolean,
        statsEnabled: Boolean,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): StatsLoadState = withContext(ioDispatcher) {
        if (!statsEnabled) return@withContext StatsLoadState.Disabled
        if (!serviceAlive) return@withContext StatsLoadState.ServiceUnavailable
        val raw = PowerKeeperBinderClient.queryFreezeStats(window)
            ?: return@withContext StatsLoadState.BackendUnsupported
        if (!raw.enabled) return@withContext StatsLoadState.Disabled
        StatsLoadState.Ready(resolveFromData(context, raw))
    }

    suspend fun reset(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean = withContext(ioDispatcher) {
        PowerKeeperBinderClient.resetFreezeStats()
    }

    suspend fun loadSample(
        context: Context,
        window: StatsWindow,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): StatsLoadState = withContext(ioDispatcher) {
        StatsLoadState.Ready(resolveApps(context, FreezeStatsSampleData.build(context, window)))
    }

    private val labelCache = LruCache<String, Triple<String, AppIconSource?, Boolean>>(512)

    private fun resolvePackage(
        pm: PackageManager,
        uid: Int,
        rawPkg: String,
        memberPackages: List<String>? = null,
    ): ResolvedAppMeta {
        val userId = if (uid >= 0) uid / 100_000 else 0
        val appId = if (uid >= 100_000) uid % 100_000 else uid
        val isDualApp = userId == 999
        val packages = sortedStatsMemberPackages(memberPackages)
        if (packages.size > 1) {
            val members = packages.map { pkg ->
                val cacheKey = "$uid:$pkg"
                val cached = labelCache.get(cacheKey) ?: run {
                    val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                    val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                        ?.trim()?.ifEmpty { null } ?: pkg
                    val isSys = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    Triple(label, AppIconSource.fromOrNull(info), isSys)
                        .also { labelCache.put(cacheKey, it) }
                }
                StatsPackageMember(pkg, cached.first, cached.second, cached.third)
            }
            val label = statsGroupLabel(members)
            return ResolvedAppMeta(
                packageName = "uid:$uid",
                label = if (userId > 0 && !isDualApp) "$label (用户$userId)" else label,
                iconSource = null,
                isSystem = members.all { it.isSystem },
                isDualApp = isDualApp,
                members = members,
            )
        }
        val trimmedPkg = rawPkg.trim()
        val effectivePkg = if (trimmedPkg.isBlank() || trimmedPkg.startsWith("uid:")) {
            ""
        } else {
            trimmedPkg
        }
        if (effectivePkg.isEmpty()) {
            val fallbackLabel = when {
                appId in 90_000..99_999 -> "沙盒进程 · $uid"
                uid >= 0 -> "UID $uid"
                else -> "未知应用"
            }
            return ResolvedAppMeta(
                packageName = "uid:$uid",
                label = fallbackLabel,
                iconSource = null,
                isSystem = false,
                isDualApp = isDualApp,
            )
        }
        labelCache.get(effectivePkg)?.let { (baseLabel, iconSrc, isSys) ->
            val finalLabel = if (userId > 0 && !isDualApp) "$baseLabel (用户$userId)" else baseLabel
            return ResolvedAppMeta(effectivePkg, finalLabel, iconSrc, isSys, isDualApp)
        }
        val info = runCatching { pm.getApplicationInfo(effectivePkg, 0) }.getOrNull()
        val baseLabel = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?.trim()?.ifEmpty { null } ?: effectivePkg
        val isSys = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val iconSrc = AppIconSource.fromOrNull(info)
        labelCache.put(effectivePkg, Triple(baseLabel, iconSrc, isSys))
        val finalLabel = if (userId > 0 && !isDualApp) "$baseLabel (用户$userId)" else baseLabel
        return ResolvedAppMeta(effectivePkg, finalLabel, iconSrc, isSys, isDualApp)
    }

    private fun queryMemberPackages(uids: List<Int>): Map<Int, List<String>> = buildMap {
        for (batch in uids.filter { it >= 0 }.distinct().chunked(MilletIpcContract.MAX_UID_PACKAGES_QUERY)) {
            putAll(PowerKeeperBinderClient.queryUidPackages(batch).orEmpty())
        }
    }

    /** 将系统服务返回的纯 Kotlin 快照映射为 UI 模型并解析包名/应用名/图标 */
    fun resolveFromData(context: Context, raw: FreezeStatsSnapshotData): FreezeStatsSnapshot {
        val pm = context.packageManager
        val packagesByUid = queryMemberPackages(raw.apps.map { it.uid } + raw.recent.map { it.uid })
        val metadata = HashMap<Int, ResolvedAppMeta>()
        fun metaFor(uid: Int, pkg: String): ResolvedAppMeta = metadata.getOrPut(uid) {
            resolvePackage(pm, uid, pkg, packagesByUid[uid])
        }
        return FreezeStatsSnapshot(
            window = raw.window,
            sinceMillis = raw.sinceMillis,
            generatedAtMillis = raw.generatedAtMillis,
            freezeCount = raw.freezeCount,
            wakeByCategory = raw.wakeByCategory,
            screenOffWakeCount = raw.screenOffWakeCount,
            screenOffMillis = raw.screenOffMillis,
            interceptByRule = raw.interceptByRule,
            protectedCount = raw.protectedCount,
            killBlockedCount = raw.killBlockedCount,
            frozenMillisTotal = raw.frozenMillisTotal,
            buckets = raw.buckets,
            apps = raw.apps.map { a ->
                val meta = metaFor(a.uid, a.packageName)
                AppFreezeStats(
                    uid = a.uid,
                    packageName = meta.packageName,
                    freezeCount = a.freezeCount,
                    wakeByCategory = a.wakeByCategory,
                    interceptByRule = a.interceptByRule,
                    frozenMillis = a.frozenMillis,
                    observedMillis = a.observedMillis,
                    protectedCount = a.protectedCount,
                    killBlockedCount = a.killBlockedCount,
                    topRawReasons = a.topRawReasons,
                    label = meta.label,
                    iconSource = meta.iconSource,
                    isSystem = meta.isSystem,
                    isDualApp = meta.isDualApp,
                    members = meta.members,
                )
            },
            recent = raw.recent.map { e ->
                val meta = metaFor(e.uid, e.packageName)
                StatsRecentEvent(
                    timeMillis = e.timeMillis,
                    uid = e.uid,
                    packageName = meta.packageName,
                    kind = e.kind,
                    category = e.category,
                    rule = e.rule,
                    rawReason = e.rawReason,
                    origin = e.origin,
                    label = meta.label,
                    iconSource = meta.iconSource,
                    isSystem = meta.isSystem,
                    isDualApp = meta.isDualApp,
                    members = meta.members,
                )
            },
            memoryBytes = raw.memoryBytes,
            sourceNote = raw.sourceNote,
            isSample = false,
        )
    }

    /** 包名 → 应用名 + AppIconSource（线程安全 LRU 缓存，仅在 IO 线程调用） */
    fun resolveApps(context: Context, snapshot: FreezeStatsSnapshot): FreezeStatsSnapshot {
        val pm = context.packageManager
        val packagesByUid = if (snapshot.isSample) emptyMap() else {
            queryMemberPackages(snapshot.apps.map { it.uid } + snapshot.recent.map { it.uid })
        }
        val metadata = HashMap<Int, ResolvedAppMeta>()
        fun metaFor(uid: Int, pkg: String): ResolvedAppMeta = metadata.getOrPut(uid) {
            resolvePackage(pm, uid, pkg, packagesByUid[uid])
        }
        return snapshot.copy(
            apps = snapshot.apps.map { a ->
                val meta = metaFor(a.uid, a.packageName)
                a.copy(
                    packageName = meta.packageName,
                    label = meta.label,
                    iconSource = meta.iconSource,
                    isSystem = meta.isSystem,
                    isDualApp = meta.isDualApp,
                    members = meta.members,
                )
            },
            recent = snapshot.recent.map { e ->
                val meta = metaFor(e.uid, e.packageName)
                e.copy(
                    packageName = meta.packageName,
                    label = meta.label,
                    iconSource = meta.iconSource,
                    isSystem = meta.isSystem,
                    isDualApp = meta.isDualApp,
                    members = meta.members,
                )
            },
        )
    }
}

/** 仅用于开发期预览界面的示例数据（Debug 包可见，页面上有明显「示例数据」标识） */
private object FreezeStatsSampleData {
    fun build(context: Context, window: StatsWindow): FreezeStatsSnapshot {
        val rnd = Random(window.ordinal * 7919 + 17)
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pkgs = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
            .map { it.packageName }
            .sortedBy { it.hashCode() }
            .take(14)
            .ifEmpty { listOf("com.tencent.mm", "com.tencent.mobileqq", "com.taobao.taobao", "com.eg.android.AlipayGphone") }

        val scale = when (window) { StatsWindow.TODAY -> 1; StatsWindow.WEEK -> 6; StatsWindow.BOOT -> 2 }
        val windowMillis = when (window) {
            StatsWindow.TODAY -> 12 * 3_600_000L
            StatsWindow.WEEK -> 7 * 24 * 3_600_000L
            StatsWindow.BOOT -> 30 * 3_600_000L
        }
        val apps = pkgs.mapIndexed { i, pkg ->
            val heat = (pkgs.size - i).toFloat() / pkgs.size
            val cats = WakeCategory.entries.associateWith { c ->
                val base = when (c) {
                    WakeCategory.USER_OPEN -> 12; WakeCategory.NETWORK -> 90; WakeCategory.BINDER -> 40
                    WakeCategory.BROADCAST -> 25; WakeCategory.SCREEN_ON -> 10; WakeCategory.CHARGING -> 3
                    WakeCategory.OTHER -> 8
                    WakeCategory.MODULE_OPERATION -> 2; WakeCategory.LAUNCH_MODE -> 2
                }
                (base * heat * scale * rnd.nextFloat()).toInt()
            }
            val rules = InterceptRule.entries.associateWith { (20 * heat * scale * rnd.nextFloat() * rnd.nextFloat()).toInt() }
                .filterValues { it > 0 }
            AppFreezeStats(
                uid = 10100 + i,
                packageName = pkg,
                freezeCount = (60 * heat * scale * (0.5f + rnd.nextFloat())).toInt(),
                wakeByCategory = cats,
                interceptByRule = rules,
                frozenMillis = (windowMillis * (0.35f + 0.6f * rnd.nextFloat())).toLong(),
                observedMillis = windowMillis,
                protectedCount = if (rnd.nextInt(5) == 0) rnd.nextInt(1, 9) else 0,
                killBlockedCount = rnd.nextInt(0, 4),
                topRawReasons = listOf("PACKET" to rnd.nextInt(10, 90), "Sync Binder4" to rnd.nextInt(5, 40), "broadcast" to rnd.nextInt(1, 20)),
            )
        }
        val wake = WakeCategory.entries.associateWith { c -> apps.sumOf { it.wakeByCategory[c] ?: 0 } }
        val rules = InterceptRule.entries.associateWith { r -> apps.sumOf { it.interceptByRule[r] ?: 0 } }
        val buckets = when (window) {
            StatsWindow.TODAY -> (0 until 24).map { h ->
                val night = h < 7
                StatsTimeBucket(
                    label = "%02d".format(Locale.ROOT, h),
                    screenOnWakes = if (night) rnd.nextInt(0, 3) else rnd.nextInt(5, 40),
                    screenOffWakes = if (night) rnd.nextInt(3, 18) else rnd.nextInt(0, 10),
                )
            }
            StatsWindow.WEEK -> listOf("一", "二", "三", "四", "五", "六", "日").map {
                StatsTimeBucket("周$it", rnd.nextInt(150, 420), rnd.nextInt(40, 160))
            }
            StatsWindow.BOOT -> emptyList()
        }
        val now = System.currentTimeMillis()
        val recent = (0 until 30).map { i ->
            val app = apps[rnd.nextInt(apps.size)]
            val kind = listOf(StatsEventKind.FREEZE, StatsEventKind.THAW, StatsEventKind.INTERCEPT, StatsEventKind.THAW)[rnd.nextInt(4)]
            StatsRecentEvent(
                timeMillis = now - i * rnd.nextLong(20_000, 200_000),
                uid = app.uid,
                packageName = app.packageName,
                kind = kind,
                category = if (kind == StatsEventKind.THAW) WakeCategory.entries[rnd.nextInt(WakeCategory.entries.size)] else null,
                rule = if (kind == StatsEventKind.INTERCEPT) InterceptRule.entries[rnd.nextInt(3)] else null,
                rawReason = if (kind == StatsEventKind.FREEZE) "IMMOBULUS" else "PACKET",
            )
        }
        return FreezeStatsSnapshot(
            window = window,
            sinceMillis = now - windowMillis,
            generatedAtMillis = now,
            freezeCount = apps.sumOf { it.freezeCount },
            wakeByCategory = wake,
            screenOffWakeCount = buckets.sumOf { it.screenOffWakes }.takeIf { it > 0 } ?: (wake.values.sum() / 4),
            screenOffMillis = windowMillis / 2,
            interceptByRule = rules,
            protectedCount = apps.sumOf { it.protectedCount },
            killBlockedCount = apps.sumOf { it.killBlockedCount },
            frozenMillisTotal = apps.sumOf { it.frozenMillis },
            buckets = buckets,
            apps = apps,
            recent = recent,
            memoryBytes = 64 * 1024,
            isSample = true,
        )
    }
}
