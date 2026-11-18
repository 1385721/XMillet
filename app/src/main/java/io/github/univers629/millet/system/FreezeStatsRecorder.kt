package io.github.univers629.millet.system

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.univers629.millet.core.FreezeReasonClassifier
import io.github.univers629.millet.core.FreezeStatsCodec
import io.github.univers629.millet.core.FreezeStatsSnapshotData
import io.github.univers629.millet.core.AppFreezeStatsData
import io.github.univers629.millet.core.InterceptRule
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.RomCapabilities
import io.github.univers629.millet.core.StatsEventKind
import io.github.univers629.millet.core.StatsRecentEventData
import io.github.univers629.millet.core.StatsTimeBucket
import io.github.univers629.millet.core.StatsWindow
import io.github.univers629.millet.core.WakeCategory
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * 纯 JVM 可测的墓碑统计核心聚合引擎。
 *
 * 设计要点：
 * 1. 所有热路径方法严格零文件 IO、零 PackageManager、零 PowerManager 调用；
 * 2. 所有内存集合均设上限（7 天窗口、24 小时柱、最多 256 应用/桶、每应用最多 16 条原始理由并取 Top 5、
 *    最近动态 256 条固定环形缓冲区），确保 system_server 常驻内存可控；
 * 3. `ReentrantLock` 仅作纯内存叶锁使用，绝不在持锁期间回调外部系统服务或磁盘 IO，防止死锁。
 */
class FreezeStatsEngine(
    private val storageFile: File? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val bootIdProvider: () -> String = { "" },
    private val enabledProvider: () -> Boolean = { true },
    private val packageResolver: (Int) -> String? = { null },
    private val frozenUidsProvider: () -> Set<Int>? = { null },
    private val asyncIoExecutor: ((() -> Unit) -> Unit)? = null,
    private val dispatchThawEventActiveProvider: () -> Boolean = { false },
) {

    companion object {
        const val MAX_DAYS = 7
        const val MAX_RECENT_EVENTS = 256
        const val MAX_APPS_PER_SCOPE = 256
        const val MAX_RAW_REASONS_PER_APP = 16
        const val TOP_RAW_REASONS_LIMIT = 5
        const val MAX_UID_PKG_CACHE = 512
        const val PERIODIC_SAVE_INTERVAL_MS = 30L * 60_000L
        const val SOURCE_NOTE_FALLBACK = "本机缺少 dispatchThawEvent，唤醒次数由 thawUid / thawUids 推断"

        private val WAKE_CATEGORY_COUNT = WakeCategory.entries.size
        private val INTERCEPT_RULE_COUNT = InterceptRule.entries.size
        private const val ONE_WEEK_MILLIS = 7L * 86_400_000L
    }

    private class AppAccumulator(val uid: Int) {
        var freezeCount: Int = 0
        val wakeByCategory = IntArray(WAKE_CATEGORY_COUNT)
        val interceptByRule = IntArray(INTERCEPT_RULE_COUNT)
        var frozenMillis: Long = 0L
        var protectedCount: Int = 0
        var killBlockedCount: Int = 0
        val rawReasons = HashMap<String, Int>()

        fun totalActivity(): Int {
            var sum = freezeCount + protectedCount + killBlockedCount
            for (v in wakeByCategory) sum += v
            for (v in interceptByRule) sum += v
            return sum
        }

        fun recordRawReason(reason: String) {
            val clean = reason.trim()
            if (clean.isEmpty()) return
            val existing = rawReasons[clean]
            if (existing != null) {
                rawReasons[clean] = existing + 1
            } else if (rawReasons.size < MAX_RAW_REASONS_PER_APP) {
                rawReasons[clean] = 1
            } else {
                var minKey: String? = null
                var minVal = Int.MAX_VALUE
                for ((k, v) in rawReasons) {
                    if (v < minVal) {
                        minVal = v
                        minKey = k
                    }
                }
                if (minKey != null && minVal <= 1) {
                    rawReasons.remove(minKey)
                    rawReasons[clean] = 1
                }
            }
        }

        fun toWakeMap(): Map<WakeCategory, Int> {
            val map = LinkedHashMap<WakeCategory, Int>()
            for (cat in WakeCategory.entries) {
                val count = wakeByCategory[cat.ordinal]
                if (count > 0) map[cat] = count
            }
            return map
        }

        fun toInterceptMap(): Map<InterceptRule, Int> {
            val map = LinkedHashMap<InterceptRule, Int>()
            for (rule in InterceptRule.entries) {
                val count = interceptByRule[rule.ordinal]
                if (count > 0) map[rule] = count
            }
            return map
        }

        fun toTopRawReasons(): List<Pair<String, Int>> {
            if (rawReasons.isEmpty()) return emptyList()
            return rawReasons.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(TOP_RAW_REASONS_LIMIT)
                .map { it.key to it.value }
        }
    }

    private open class ScopeBucket {
        var freezeCount: Int = 0
        val wakeByCategory = IntArray(WAKE_CATEGORY_COUNT)
        var screenOffWakeCount: Int = 0
        var screenOffMillis: Long = 0L
        val interceptByRule = IntArray(INTERCEPT_RULE_COUNT)
        var protectedCount: Int = 0
        var killBlockedCount: Int = 0
        var frozenMillisTotal: Long = 0L
        val apps = HashMap<Int, AppAccumulator>()

        fun getOrCreateApp(uid: Int): AppAccumulator {
            apps[uid]?.let { return it }
            if (apps.size >= MAX_APPS_PER_SCOPE) {
                var victimUid = -1
                var minScore = Int.MAX_VALUE
                for ((candidateUid, acc) in apps) {
                    val score = acc.totalActivity()
                    if (score < minScore) {
                        minScore = score
                        victimUid = candidateUid
                    }
                }
                if (victimUid != -1) {
                    apps.remove(victimUid)
                }
            }
            return AppAccumulator(uid).also { apps[uid] = it }
        }
    }

    private class DayBucket(val epochDay: Long) : ScopeBucket() {
        val hourlyScreenOnWakes = IntArray(24)
        val hourlyScreenOffWakes = IntArray(24)
    }

    private class BootBucket : ScopeBucket()

    private val lock = ReentrantLock()
    private val ioLock = Any()

    @Volatile
    var isScreenOn: Boolean = true
        private set

    private var screenOffSinceMillis: Long = 0L
    private val activeFrozenSinceByUid = HashMap<Int, Long>()
    private val uidToPackage = ConcurrentHashMap<Int, String>()
    private val packageToUid = ConcurrentHashMap<String, Int>()

    private val days = HashMap<Long, DayBucket>()
    private var bootBucket = BootBucket()

    private var baselineSinceMillis: Long
    private var bootSinceMillis: Long
    private var lastDayKey: Long

    private val recentRing = arrayOfNulls<StatsRecentEventData>(MAX_RECENT_EVENTS)
    private var recentNextIdx = 0
    private var recentCount = 0

    private var dirty = false
    private var mutationSeq = 0L

    init {
        val now = clock()
        baselineSinceMillis = now
        bootSinceMillis = now
        lastDayKey = epochDay(now)
        days[lastDayKey] = DayBucket(lastDayKey)
    }

    // ==================== 屏幕状态 ====================

    fun initScreenState(screenOn: Boolean) {
        val now = clock()
        lock.withLock {
            ensureCurrentDayLocked(now)
            isScreenOn = screenOn
            screenOffSinceMillis = if (!screenOn && enabledProvider()) now else 0L
        }
    }

    fun onScreenStateChanged(screenOn: Boolean) {
        val now = clock()
        var shouldSaveOnScreenOff = false
        lock.withLock {
            ensureCurrentDayLocked(now)
            val prev = isScreenOn
            if (prev == screenOn) return
            if (!prev && screenOn) {
                flushScreenOffLocked(now)
                screenOffSinceMillis = 0L
                isScreenOn = true
            } else if (prev && !screenOn) {
                isScreenOn = false
                screenOffSinceMillis = if (enabledProvider()) now else 0L
                shouldSaveOnScreenOff = dirty
            }
        }
        if (shouldSaveOnScreenOff) {
            triggerAsyncSaveIfDirty()
        }
    }

    fun onEnabledChanged(enabled: Boolean) {
        val now = clock()
        var shouldSave = false
        lock.withLock {
            ensureCurrentDayLocked(now)
            if (!enabled) {
                flushOngoingDurationsLocked(now, null)
                activeFrozenSinceByUid.clear()
                screenOffSinceMillis = 0L
                shouldSave = dirty
            } else {
                if (!isScreenOn && screenOffSinceMillis == 0L) {
                    screenOffSinceMillis = now
                }
            }
        }
        if (shouldSave) {
            triggerAsyncSaveIfDirty()
        }
    }

    // ==================== 事件采集热路径（零 IO / 零 PM / 零 PowerManager） ====================

    /** 仅统计普通/双开用户应用 UID（排除 <10000 系统/root，以及 90000..99999 沙盒隔离进程） */
    private fun isTrackableAppUid(uid: Int): Boolean {
        if (uid < 10000) return false
        val appId = uid % 100_000
        return appId in 10000..19999
    }

    fun isUidTrackedFrozen(uid: Int): Boolean {
        if (!isTrackableAppUid(uid)) return false
        lock.withLock {
            return activeFrozenSinceByUid.containsKey(uid)
        }
    }

    fun cachePackageHint(uid: Int, packageName: String) {
        cachePackageName(uid, packageName)
    }

    fun getCachedPackageName(uid: Int): String? = uidToPackage[uid]

    fun getCachedUidForPackage(packageName: String): Int = packageToUid[packageName] ?: -1

    fun recordFreeze(
        uid: Int,
        reason: String,
        packageNameHint: String? = null,
        onlyIfNotFrozen: Boolean = false,
    ) {
        if (!isTrackableAppUid(uid) || !enabledProvider()) return
        if (!packageNameHint.isNullOrEmpty()) {
            cachePackageName(uid, packageNameHint)
        }
        val now = clock()
        lock.withLock {
            if (onlyIfNotFrozen && activeFrozenSinceByUid.containsKey(uid)) return
            val today = ensureCurrentDayLocked(now)
            recordFreezeSingleLocked(today, uid, reason, now)
        }
    }

    fun recordFreezeBatch(
        uids: List<Int>,
        reason: String,
        onlyIfNotFrozen: Boolean = true,
    ) {
        if (uids.isEmpty() || !enabledProvider()) return
        val now = clock()
        lock.withLock {
            val today = ensureCurrentDayLocked(now)
            var prevUid = Int.MIN_VALUE
            for (i in uids.indices) {
                val uid = uids[i]
                if (!isTrackableAppUid(uid)) continue
                // 快速去重相邻/已处理 uid（多数情况下 uids 只有 1~3 个）
                if (uid == prevUid) continue
                var duplicate = false
                for (j in 0 until i) {
                    if (uids[j] == uid) {
                        duplicate = true
                        break
                    }
                }
                if (duplicate) continue
                if (onlyIfNotFrozen && activeFrozenSinceByUid.containsKey(uid)) continue
                prevUid = uid
                recordFreezeSingleLocked(today, uid, reason, now)
            }
        }
    }

    private fun recordFreezeSingleLocked(
        today: DayBucket,
        uid: Int,
        reason: String,
        now: Long,
    ) {
        if (!activeFrozenSinceByUid.containsKey(uid)) {
            activeFrozenSinceByUid[uid] = now
        }
        today.freezeCount++
        today.getOrCreateApp(uid).freezeCount++
        bootBucket.freezeCount++
        bootBucket.getOrCreateApp(uid).freezeCount++

        appendRecentLocked(
            StatsRecentEventData(
                timeMillis = now,
                uid = uid,
                packageName = uidToPackage[uid].orEmpty(),
                kind = StatsEventKind.FREEZE,
                rawReason = reason,
            )
        )
        markDirtyLocked()
    }

    fun recordThaw(uid: Int, reason: String, packageNameHint: String? = null) {
        if (!isTrackableAppUid(uid) || !enabledProvider()) return
        if (!packageNameHint.isNullOrEmpty()) {
            cachePackageName(uid, packageNameHint)
        }
        val category = FreezeReasonClassifier.classifyWakeCategory(reason)
        val screenOnNow = isScreenOn
        val now = clock()
        val hour = hourOfDay(now)

        lock.withLock {
            val today = ensureCurrentDayLocked(now)
            val frozenSince = activeFrozenSinceByUid.remove(uid)
            if (frozenSince != null && now > frozenSince) {
                addFrozenDurationLocked(uid, frozenSince, now)
            }

            today.wakeByCategory[category.ordinal]++
            if (screenOnNow) {
                today.hourlyScreenOnWakes[hour]++
            } else {
                today.screenOffWakeCount++
                today.hourlyScreenOffWakes[hour]++
            }
            val todayApp = today.getOrCreateApp(uid)
            todayApp.wakeByCategory[category.ordinal]++
            todayApp.recordRawReason(reason)

            bootBucket.wakeByCategory[category.ordinal]++
            if (!screenOnNow) {
                bootBucket.screenOffWakeCount++
            }
            val bootApp = bootBucket.getOrCreateApp(uid)
            bootApp.wakeByCategory[category.ordinal]++
            bootApp.recordRawReason(reason)

            appendRecentLocked(
                StatsRecentEventData(
                    timeMillis = now,
                    uid = uid,
                    packageName = uidToPackage[uid].orEmpty(),
                    kind = StatsEventKind.THAW,
                    category = category,
                    rawReason = reason,
                )
            )
            markDirtyLocked()
        }
    }

    fun recordIntercept(
        uid: Int,
        rule: InterceptRule,
        reason: String,
        packageNameHint: String? = null,
    ) {
        if (!isTrackableAppUid(uid) || !enabledProvider()) return
        if (!packageNameHint.isNullOrEmpty()) {
            cachePackageName(uid, packageNameHint)
        }
        val now = clock()
        lock.withLock {
            val today = ensureCurrentDayLocked(now)
            today.interceptByRule[rule.ordinal]++
            val todayApp = today.getOrCreateApp(uid)
            todayApp.interceptByRule[rule.ordinal]++
            todayApp.recordRawReason(reason)

            bootBucket.interceptByRule[rule.ordinal]++
            val bootApp = bootBucket.getOrCreateApp(uid)
            bootApp.interceptByRule[rule.ordinal]++
            bootApp.recordRawReason(reason)

            appendRecentLocked(
                StatsRecentEventData(
                    timeMillis = now,
                    uid = uid,
                    packageName = uidToPackage[uid].orEmpty(),
                    kind = StatsEventKind.INTERCEPT,
                    rule = rule,
                    rawReason = reason,
                )
            )
            markDirtyLocked()
        }
    }

    fun recordSystemIntercept(rule: InterceptRule, reason: String) {
        if (!enabledProvider()) return
        val now = clock()
        lock.withLock {
            val today = ensureCurrentDayLocked(now)
            today.interceptByRule[rule.ordinal]++
            bootBucket.interceptByRule[rule.ordinal]++
            appendRecentLocked(
                StatsRecentEventData(
                    timeMillis = now,
                    uid = 1000,
                    packageName = "android",
                    kind = StatsEventKind.INTERCEPT,
                    rule = rule,
                    rawReason = reason,
                )
            )
            markDirtyLocked()
        }
    }

    fun recordProtect(uid: Int, reason: String, packageNameHint: String? = null) {
        if (!isTrackableAppUid(uid) || !enabledProvider()) return
        if (!packageNameHint.isNullOrEmpty()) {
            cachePackageName(uid, packageNameHint)
        }
        val now = clock()
        lock.withLock {
            val today = ensureCurrentDayLocked(now)
            today.protectedCount++
            today.getOrCreateApp(uid).protectedCount++

            bootBucket.protectedCount++
            bootBucket.getOrCreateApp(uid).protectedCount++

            appendRecentLocked(
                StatsRecentEventData(
                    timeMillis = now,
                    uid = uid,
                    packageName = uidToPackage[uid].orEmpty(),
                    kind = StatsEventKind.PROTECT,
                    rawReason = reason,
                )
            )
            markDirtyLocked()
        }
    }

    fun recordKillBlocked(uid: Int, reason: String, packageNameHint: String? = null) {
        if (!isTrackableAppUid(uid) || !enabledProvider()) return
        if (!packageNameHint.isNullOrEmpty()) {
            cachePackageName(uid, packageNameHint)
        }
        val now = clock()
        lock.withLock {
            val today = ensureCurrentDayLocked(now)
            today.killBlockedCount++
            today.getOrCreateApp(uid).killBlockedCount++

            bootBucket.killBlockedCount++
            bootBucket.getOrCreateApp(uid).killBlockedCount++

            appendRecentLocked(
                StatsRecentEventData(
                    timeMillis = now,
                    uid = uid,
                    packageName = uidToPackage[uid].orEmpty(),
                    kind = StatsEventKind.KILL_BLOCKED,
                    rawReason = reason,
                )
            )
            markDirtyLocked()
        }
    }

    // ==================== 快照导出与重置 ====================

    fun buildSnapshot(window: StatsWindow): FreezeStatsSnapshotData {
        val enabled = enabledProvider()
        val preFetchTime = clock()
        val liveFrozenUids = if (enabled) frozenUidsProvider() else null
        val dispatchActive = dispatchThawEventActiveProvider()

        val rawCopy = lock.withLock {
            val now = clock()
            val todayKey = epochDay(now)
            ensureCurrentDayLocked(now)
            if (enabled) {
                flushOngoingDurationsLocked(now, liveFrozenUids, reconcileCutoffTime = preFetchTime)
            }

            val cutoffMillis = when (window) {
                StatsWindow.TODAY -> maxOf(startOfDayMillis(todayKey), baselineSinceMillis)
                StatsWindow.WEEK -> maxOf(startOfDayMillis(todayKey - (MAX_DAYS - 1)), baselineSinceMillis)
                StatsWindow.BOOT -> bootSinceMillis
            }

            val buckets: List<StatsTimeBucket>
            val freezeCount: Int
            val wakeCounts = IntArray(WAKE_CATEGORY_COUNT)
            val screenOffWakeCount: Int
            val screenOffMillis: Long
            val interceptCounts = IntArray(INTERCEPT_RULE_COUNT)
            val protectedCount: Int
            val killBlockedCount: Int
            val frozenMillisTotal: Long
            val rawApps: List<RawAppExport>

            when (window) {
                StatsWindow.TODAY -> {
                    val today = days[todayKey]
                    buckets = (0 until 24).map { hour ->
                        StatsTimeBucket(
                            label = if (hour < 10) "0$hour" else "$hour",
                            screenOnWakes = today?.hourlyScreenOnWakes?.get(hour) ?: 0,
                            screenOffWakes = today?.hourlyScreenOffWakes?.get(hour) ?: 0,
                        )
                    }
                    if (today != null) {
                        freezeCount = today.freezeCount
                        today.wakeByCategory.copyInto(wakeCounts)
                        screenOffWakeCount = today.screenOffWakeCount
                        screenOffMillis = today.screenOffMillis
                        today.interceptByRule.copyInto(interceptCounts)
                        protectedCount = today.protectedCount
                        killBlockedCount = today.killBlockedCount
                        frozenMillisTotal = today.frozenMillisTotal
                        rawApps = exportScopeApps(today, now - cutoffMillis)
                    } else {
                        freezeCount = 0
                        screenOffWakeCount = 0
                        screenOffMillis = 0L
                        protectedCount = 0
                        killBlockedCount = 0
                        frozenMillisTotal = 0L
                        rawApps = emptyList()
                    }
                }

                StatsWindow.WEEK -> {
                    val firstDayKey = todayKey - (MAX_DAYS - 1)
                    buckets = (0 until MAX_DAYS).map { offset ->
                        val key = firstDayKey + offset
                        val d = days[key]
                        StatsTimeBucket(
                            label = weekdayLabel(key),
                            screenOnWakes = d?.hourlyScreenOnWakes?.sum() ?: 0,
                            screenOffWakes = d?.hourlyScreenOffWakes?.sum() ?: 0,
                        )
                    }
                    var fCount = 0
                    var offWake = 0
                    var offMs = 0L
                    var prot = 0
                    var killB = 0
                    var fzMs = 0L
                    val mergedApps = HashMap<Int, AppAccumulator>()

                    for (offset in 0 until MAX_DAYS) {
                        val d = days[firstDayKey + offset] ?: continue
                        fCount += d.freezeCount
                        for (i in 0 until WAKE_CATEGORY_COUNT) wakeCounts[i] += d.wakeByCategory[i]
                        offWake += d.screenOffWakeCount
                        offMs += d.screenOffMillis
                        for (i in 0 until INTERCEPT_RULE_COUNT) interceptCounts[i] += d.interceptByRule[i]
                        prot += d.protectedCount
                        killB += d.killBlockedCount
                        fzMs += d.frozenMillisTotal

                        for ((uid, app) in d.apps) {
                            val target = mergedApps.getOrPut(uid) { AppAccumulator(uid) }
                            target.freezeCount += app.freezeCount
                            for (i in 0 until WAKE_CATEGORY_COUNT) {
                                target.wakeByCategory[i] += app.wakeByCategory[i]
                            }
                            for (i in 0 until INTERCEPT_RULE_COUNT) {
                                target.interceptByRule[i] += app.interceptByRule[i]
                            }
                            target.frozenMillis += app.frozenMillis
                            target.protectedCount += app.protectedCount
                            target.killBlockedCount += app.killBlockedCount
                            for ((r, c) in app.rawReasons) {
                                target.rawReasons[r] = (target.rawReasons[r] ?: 0) + c
                            }
                        }
                    }

                    freezeCount = fCount
                    screenOffWakeCount = offWake
                    screenOffMillis = offMs
                    protectedCount = prot
                    killBlockedCount = killB
                    frozenMillisTotal = fzMs
                    rawApps = mergedApps.values.map { acc ->
                        RawAppExport(
                            uid = acc.uid,
                            freezeCount = acc.freezeCount,
                            wakeByCategory = acc.toWakeMap(),
                            interceptByRule = acc.toInterceptMap(),
                            frozenMillis = acc.frozenMillis,
                            observedMillis = (now - cutoffMillis).coerceAtLeast(acc.frozenMillis),
                            protectedCount = acc.protectedCount,
                            killBlockedCount = acc.killBlockedCount,
                            topRawReasons = acc.toTopRawReasons(),
                        )
                    }
                }

                StatsWindow.BOOT -> {
                    buckets = emptyList()
                    freezeCount = bootBucket.freezeCount
                    bootBucket.wakeByCategory.copyInto(wakeCounts)
                    screenOffWakeCount = bootBucket.screenOffWakeCount
                    screenOffMillis = bootBucket.screenOffMillis
                    bootBucket.interceptByRule.copyInto(interceptCounts)
                    protectedCount = bootBucket.protectedCount
                    killBlockedCount = bootBucket.killBlockedCount
                    frozenMillisTotal = bootBucket.frozenMillisTotal
                    rawApps = exportScopeApps(bootBucket, now - cutoffMillis)
                }
            }

            val recentList = ArrayList<StatsRecentEventData>(recentCount)
            for (offset in 0 until recentCount) {
                val idx = (recentNextIdx - 1 - offset + MAX_RECENT_EVENTS) % MAX_RECENT_EVENTS
                val ev = recentRing[idx] ?: continue
                if (ev.timeMillis >= cutoffMillis) {
                    recentList.add(ev)
                }
            }

            RawSnapshotCopy(
                sinceMillis = cutoffMillis,
                generatedAtMillis = now,
                freezeCount = freezeCount,
                wakeByCategory = intArrayToWakeMap(wakeCounts),
                screenOffWakeCount = screenOffWakeCount,
                screenOffMillis = screenOffMillis,
                interceptByRule = intArrayToInterceptMap(interceptCounts),
                protectedCount = protectedCount,
                killBlockedCount = killBlockedCount,
                frozenMillisTotal = frozenMillisTotal,
                buckets = buckets,
                apps = rawApps,
                recent = recentList,
                memoryBytes = estimateMemoryBytesLocked(),
            )
        }

        // 在锁外延迟解析包名（零死锁风险、零热路径开销）
        val resolvedApps = rawCopy.apps
            .map { raw ->
                AppFreezeStatsData(
                    uid = raw.uid,
                    packageName = resolvePackageAtExport(raw.uid),
                    freezeCount = raw.freezeCount,
                    wakeByCategory = raw.wakeByCategory,
                    interceptByRule = raw.interceptByRule,
                    frozenMillis = raw.frozenMillis,
                    observedMillis = raw.observedMillis,
                    protectedCount = raw.protectedCount,
                    killBlockedCount = raw.killBlockedCount,
                    topRawReasons = raw.topRawReasons,
                )
            }
            .sortedWith(
                compareByDescending<AppFreezeStatsData> {
                    it.backgroundWakeCount + it.freezeCount + it.interceptCount
                }.thenByDescending { it.frozenMillis }.thenBy { it.uid }
            )

        val resolvedRecent = rawCopy.recent.map { ev ->
            if (ev.packageName.isNotEmpty() || !isTrackableAppUid(ev.uid)) {
                ev
            } else {
                ev.copy(packageName = resolvePackageAtExport(ev.uid))
            }
        }

        val sourceNote = if (dispatchActive) null else SOURCE_NOTE_FALLBACK

        return FreezeStatsSnapshotData(
            window = window,
            enabled = enabled,
            sinceMillis = rawCopy.sinceMillis,
            generatedAtMillis = rawCopy.generatedAtMillis,
            freezeCount = rawCopy.freezeCount,
            wakeByCategory = rawCopy.wakeByCategory,
            screenOffWakeCount = rawCopy.screenOffWakeCount,
            screenOffMillis = rawCopy.screenOffMillis,
            interceptByRule = rawCopy.interceptByRule,
            protectedCount = rawCopy.protectedCount,
            killBlockedCount = rawCopy.killBlockedCount,
            frozenMillisTotal = rawCopy.frozenMillisTotal,
            buckets = rawCopy.buckets,
            apps = resolvedApps,
            recent = resolvedRecent,
            memoryBytes = rawCopy.memoryBytes,
            sourceNote = sourceNote,
        )
    }

    fun reset(): Boolean {
        val now = clock()
        lock.withLock {
            days.clear()
            lastDayKey = epochDay(now)
            days[lastDayKey] = DayBucket(lastDayKey)
            bootBucket = BootBucket()
            activeFrozenSinceByUid.clear()
            screenOffSinceMillis = if (!isScreenOn && enabledProvider()) now else 0L
            for (i in recentRing.indices) {
                recentRing[i] = null
            }
            recentNextIdx = 0
            recentCount = 0
            baselineSinceMillis = now
            bootSinceMillis = now
            markDirtyLocked()
        }
        val executor = asyncIoExecutor
        if (executor != null) {
            executor { saveIfDirty() }
        } else {
            saveIfDirty()
        }
        return true
    }

    // ==================== 持久化与恢复 ====================

    fun saveIfDirty(forceFlush: Boolean = false): Boolean {
        val snapshotPair = lock.withLock {
            val now = clock()
            ensureCurrentDayLocked(now)
            if (forceFlush) {
                flushOngoingDurationsLocked(now, null)
            }
            if (!dirty) return false
            if (!forceFlush) {
                flushOngoingDurationsLocked(now, null)
            }
            mutationSeq to exportPersistedStateLocked(now)
        }
        val written = writeStoragePayload(FreezeStatsCodec.encodeStorage(snapshotPair.second))
        if (written) {
            lock.withLock {
                if (mutationSeq == snapshotPair.first) {
                    dirty = false
                }
            }
        }
        return written
    }

    fun saveSync(): Boolean = saveIfDirty(forceFlush = true)

    fun restoreFromDisk(): Boolean {
        val file = storageFile ?: return false
        val raw = synchronized(ioLock) {
            if (!file.exists() || !file.canRead()) return false
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } ?: return false
        val parsed = FreezeStatsCodec.decodeStorage(raw) ?: return false
        val now = clock()
        val nowElapsed = elapsedClock()
        val currentBootId = bootIdProvider()
        lock.withLock {
            applyRestoredStateLocked(parsed, now, nowElapsed, currentBootId)
        }
        return true
    }

    // ==================== 内部工具方法 ====================

    private fun triggerAsyncSaveIfDirty() {
        val hasDirty = lock.withLock { dirty }
        if (!hasDirty) return
        val executor = asyncIoExecutor
        if (executor != null) {
            executor { saveIfDirty() }
        } else {
            saveIfDirty()
        }
    }

    private fun writeStoragePayload(payload: String): Boolean {
        val file = storageFile ?: return false
        return synchronized(ioLock) {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tmpFile = File(parent, "${file.name}.${System.nanoTime()}.tmp")
            try {
                tmpFile.writeText(payload, Charsets.UTF_8)
                if (!tmpFile.renameTo(file)) {
                    if (!file.exists() || !file.delete() || !tmpFile.renameTo(file)) {
                        file.writeText(payload, Charsets.UTF_8)
                    }
                }
                true
            } catch (_: Throwable) {
                false
            } finally {
                if (tmpFile.exists()) {
                    runCatching { tmpFile.delete() }
                }
            }
        }
    }

    private fun exportPersistedStateLocked(now: Long): FreezeStatsCodec.PersistedStatsState {
        val nowElapsed = elapsedClock()
        val orderedRecent = ArrayList<StatsRecentEventData>(recentCount)
        val startOffset = recentCount
        for (offset in startOffset downTo 1) {
            val idx = (recentNextIdx - offset + MAX_RECENT_EVENTS) % MAX_RECENT_EVENTS
            recentRing[idx]?.let { orderedRecent.add(it) }
        }

        val sortedDays = days.values.sortedBy { it.epochDay }.map { d ->
            FreezeStatsCodec.PersistedDayData(
                epochDay = d.epochDay,
                freezeCount = d.freezeCount,
                screenOffWakeCount = d.screenOffWakeCount,
                screenOffMillis = d.screenOffMillis,
                protectedCount = d.protectedCount,
                killBlockedCount = d.killBlockedCount,
                frozenMillisTotal = d.frozenMillisTotal,
                wakeByCategory = intArrayToWakeMap(d.wakeByCategory),
                interceptByRule = intArrayToInterceptMap(d.interceptByRule),
                hourlyScreenOnWakes = d.hourlyScreenOnWakes.copyOf(),
                hourlyScreenOffWakes = d.hourlyScreenOffWakes.copyOf(),
                apps = d.apps.values.map { toPersistedApp(it) },
            )
        }

        val bootData = FreezeStatsCodec.PersistedBootData(
            freezeCount = bootBucket.freezeCount,
            screenOffWakeCount = bootBucket.screenOffWakeCount,
            screenOffMillis = bootBucket.screenOffMillis,
            protectedCount = bootBucket.protectedCount,
            killBlockedCount = bootBucket.killBlockedCount,
            frozenMillisTotal = bootBucket.frozenMillisTotal,
            wakeByCategory = intArrayToWakeMap(bootBucket.wakeByCategory),
            interceptByRule = intArrayToInterceptMap(bootBucket.interceptByRule),
            apps = bootBucket.apps.values.map { toPersistedApp(it) },
        )

        return FreezeStatsCodec.PersistedStatsState(
            baselineSinceMillis = baselineSinceMillis,
            bootSinceMillis = bootSinceMillis,
            bootWallClockMillis = now - nowElapsed,
            bootElapsedMillis = nowElapsed,
            savedAtMillis = now,
            bootId = bootIdProvider(),
            boot = bootData,
            days = sortedDays,
            activeFrozenSinceByUid = LinkedHashMap(activeFrozenSinceByUid),
            recent = orderedRecent,
        )
    }

    private fun toPersistedApp(app: AppAccumulator): FreezeStatsCodec.PersistedAppData {
        return FreezeStatsCodec.PersistedAppData(
            uid = app.uid,
            packageName = uidToPackage[app.uid].orEmpty(),
            freezeCount = app.freezeCount,
            frozenMillis = app.frozenMillis,
            protectedCount = app.protectedCount,
            killBlockedCount = app.killBlockedCount,
            wakeByCategory = app.toWakeMap(),
            interceptByRule = app.toInterceptMap(),
            rawReasons = LinkedHashMap(app.rawReasons),
        )
    }

    private fun applyRestoredStateLocked(
        parsed: FreezeStatsCodec.PersistedStatsState,
        now: Long,
        nowElapsed: Long,
        currentBootId: String,
    ) {
        val sameBoot = if (currentBootId.isNotEmpty() && parsed.bootId.isNotEmpty()) {
            currentBootId == parsed.bootId
        } else {
            val currentBootWall = now - nowElapsed
            val wallDiff = abs(currentBootWall - parsed.bootWallClockMillis)
            parsed.bootElapsedMillis >= 0L &&
                nowElapsed >= parsed.bootElapsedMillis &&
                wallDiff <= 10_000L
        }

        val currentDayKey = epochDay(now)
        val minDayKey = currentDayKey - (MAX_DAYS - 1)
        var droppedUntrackable = false

        days.clear()
        for (d in parsed.days) {
            if (d.epochDay < minDayKey || d.epochDay > currentDayKey) continue
            val bucket = DayBucket(d.epochDay)
            bucket.freezeCount = d.freezeCount
            bucket.screenOffWakeCount = d.screenOffWakeCount
            bucket.screenOffMillis = d.screenOffMillis
            bucket.protectedCount = d.protectedCount
            bucket.killBlockedCount = d.killBlockedCount
            bucket.frozenMillisTotal = d.frozenMillisTotal
            fillWakeArray(bucket.wakeByCategory, d.wakeByCategory)
            fillInterceptArray(bucket.interceptByRule, d.interceptByRule)
            d.hourlyScreenOnWakes.copyInto(bucket.hourlyScreenOnWakes, endIndex = minOf(24, d.hourlyScreenOnWakes.size))
            d.hourlyScreenOffWakes.copyInto(bucket.hourlyScreenOffWakes, endIndex = minOf(24, d.hourlyScreenOffWakes.size))
            for (app in d.apps) {
                if (!populateRestoredApp(bucket, app)) droppedUntrackable = true
            }
            days[d.epochDay] = bucket
        }
        days.getOrPut(currentDayKey) { DayBucket(currentDayKey) }
        lastDayKey = currentDayKey

        baselineSinceMillis = parsed.baselineSinceMillis.coerceAtMost(now)

        bootBucket = BootBucket()
        activeFrozenSinceByUid.clear()
        if (sameBoot) {
            bootSinceMillis = parsed.bootSinceMillis.coerceAtMost(now)
            val parsedBoot = parsed.boot
            if (parsedBoot != null) {
                bootBucket.freezeCount = parsedBoot.freezeCount
                bootBucket.screenOffWakeCount = parsedBoot.screenOffWakeCount
                bootBucket.screenOffMillis = parsedBoot.screenOffMillis
                bootBucket.protectedCount = parsedBoot.protectedCount
                bootBucket.killBlockedCount = parsedBoot.killBlockedCount
                bootBucket.frozenMillisTotal = parsedBoot.frozenMillisTotal
                fillWakeArray(bootBucket.wakeByCategory, parsedBoot.wakeByCategory)
                fillInterceptArray(bootBucket.interceptByRule, parsedBoot.interceptByRule)
                for (app in parsedBoot.apps) {
                    if (!populateRestoredApp(bootBucket, app)) droppedUntrackable = true
                }
            }
            for ((uid, since) in parsed.activeFrozenSinceByUid) {
                if (isTrackableAppUid(uid) && since <= now) {
                    activeFrozenSinceByUid[uid] = since
                } else if (!isTrackableAppUid(uid)) {
                    droppedUntrackable = true
                }
            }
        } else {
            bootSinceMillis = now
        }

        for (i in recentRing.indices) recentRing[i] = null
        recentNextIdx = 0
        recentCount = 0
        val minRecentTime = startOfDayMillis(minDayKey)
        val filteredRecent = parsed.recent
            .filter {
                val keep = it.timeMillis in minRecentTime..now &&
                    (isTrackableAppUid(it.uid) || (it.kind == StatsEventKind.INTERCEPT && it.uid == 1000))
                if (it.timeMillis in minRecentTime..now && !keep) droppedUntrackable = true
                keep
            }
            .takeLast(MAX_RECENT_EVENTS)
        for (ev in filteredRecent) {
            if (isTrackableAppUid(ev.uid) && ev.packageName.isNotEmpty()) {
                cachePackageName(ev.uid, ev.packageName)
            }
            appendRecentLocked(ev)
        }

        dirty = droppedUntrackable
    }

    private fun populateRestoredApp(scope: ScopeBucket, data: FreezeStatsCodec.PersistedAppData): Boolean {
        if (!isTrackableAppUid(data.uid)) return false
        if (data.packageName.isNotEmpty()) {
            cachePackageName(data.uid, data.packageName)
        }
        val acc = scope.getOrCreateApp(data.uid)
        acc.freezeCount = data.freezeCount
        acc.frozenMillis = data.frozenMillis
        acc.protectedCount = data.protectedCount
        acc.killBlockedCount = data.killBlockedCount
        fillWakeArray(acc.wakeByCategory, data.wakeByCategory)
        fillInterceptArray(acc.interceptByRule, data.interceptByRule)
        for ((r, c) in data.rawReasons) {
            if (c > 0 && acc.rawReasons.size < MAX_RAW_REASONS_PER_APP) {
                acc.rawReasons[r] = c
            }
        }
        return true
    }

    private fun ensureCurrentDayLocked(now: Long): DayBucket {
        val targetDayKey = epochDay(now)
        if (targetDayKey > lastDayKey) {
            // 先补齐本次跨度覆盖的近 7 天桶，再分段结算进行中的息屏/冻结时长，避免新一天片段被丢弃
            val minKeepKey = targetDayKey - (MAX_DAYS - 1)
            var dayCursor = maxOf(lastDayKey, minKeepKey)
            while (dayCursor <= targetDayKey) {
                days.getOrPut(dayCursor) { DayBucket(dayCursor) }
                dayCursor++
            }
            flushOngoingDurationsLocked(now, null)
            pruneOldDaysLocked(targetDayKey)
            lastDayKey = targetDayKey
            markDirtyLocked()
        }
        return days.getOrPut(targetDayKey) { DayBucket(targetDayKey) }
    }

    private fun pruneOldDaysLocked(currentDayKey: Long) {
        val minDayKey = currentDayKey - (MAX_DAYS - 1)
        val it = days.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key < minDayKey) {
                it.remove()
            }
        }
    }

    private fun flushOngoingDurationsLocked(
        now: Long,
        liveFrozenUids: Set<Int>?,
        reconcileCutoffTime: Long = now,
    ) {
        flushScreenOffLocked(now)

        if (activeFrozenSinceByUid.isEmpty()) return
        val it = activeFrozenSinceByUid.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val uid = entry.key
            val since = entry.value
            if (liveFrozenUids != null && since < reconcileCutoffTime && uid !in liveFrozenUids) {
                // 该应用已在未拦截的路径中解冻或退出，停止继续累计冻结时长
                it.remove()
                continue
            }
            if (now > since) {
                addFrozenDurationLocked(uid, since, now)
                entry.setValue(now)
                markDirtyLocked()
            }
        }
    }

    private fun flushScreenOffLocked(now: Long) {
        val since = screenOffSinceMillis
        if (isScreenOn || since <= 0L || now <= since) return
        val clampedStart = clampSpanStart(since, now)
        if (now > clampedStart) {
            allocateSpanAcrossDays(clampedStart, now) { dayKey, slice ->
                days[dayKey]?.let { it.screenOffMillis += slice }
            }
            bootBucket.screenOffMillis += (now - clampedStart)
            markDirtyLocked()
        }
        screenOffSinceMillis = now
    }

    private fun addFrozenDurationLocked(uid: Int, startMs: Long, endMs: Long) {
        val clampedStart = clampSpanStart(startMs, endMs)
        if (endMs <= clampedStart) return
        allocateSpanAcrossDays(clampedStart, endMs) { dayKey, slice ->
            val day = days[dayKey] ?: return@allocateSpanAcrossDays
            day.frozenMillisTotal += slice
            day.getOrCreateApp(uid).frozenMillis += slice
        }
        val totalSlice = endMs - clampedStart
        bootBucket.frozenMillisTotal += totalSlice
        bootBucket.getOrCreateApp(uid).frozenMillis += totalSlice
    }

    private fun clampSpanStart(startMs: Long, endMs: Long): Long {
        if (startMs >= endMs) return endMs
        val sevenDaysAgo = startOfDayMillis(epochDay(endMs) - (MAX_DAYS - 1))
        val minBound = maxOf(sevenDaysAgo, endMs - ONE_WEEK_MILLIS)
        return maxOf(startMs, minBound)
    }

    private inline fun allocateSpanAcrossDays(
        startMs: Long,
        endMs: Long,
        onSlice: (dayKey: Long, sliceMs: Long) -> Unit,
    ) {
        var cursor = startMs
        while (cursor < endMs) {
            val zdt = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val dayKey = zdt.toLocalDate().toEpochDay()
            val nextDayStart = zdt.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val segmentEnd = minOf(endMs, nextDayStart)
            val slice = (segmentEnd - cursor).coerceAtLeast(0L)
            if (slice > 0L) {
                onSlice(dayKey, slice)
            }
            cursor = if (segmentEnd > cursor) segmentEnd else endMs
        }
    }

    private fun appendRecentLocked(event: StatsRecentEventData) {
        recentRing[recentNextIdx] = event
        recentNextIdx = (recentNextIdx + 1) % MAX_RECENT_EVENTS
        if (recentCount < MAX_RECENT_EVENTS) {
            recentCount++
        }
    }

    private fun markDirtyLocked() {
        dirty = true
        mutationSeq++
    }

    private fun cachePackageName(uid: Int, pkg: String) {
        if (!isTrackableAppUid(uid) || pkg.isEmpty()) return
        if (uidToPackage.size >= MAX_UID_PKG_CACHE && !uidToPackage.containsKey(uid)) {
            val firstKey = uidToPackage.keys.firstOrNull()
            if (firstKey != null) {
                uidToPackage.remove(firstKey)?.let { oldPkg ->
                    packageToUid.remove(oldPkg, firstKey)
                }
            }
        }
        val prevPkg = uidToPackage.put(uid, pkg)
        if (prevPkg != null && prevPkg != pkg) {
            packageToUid.remove(prevPkg, uid)
        }
        packageToUid[pkg] = uid
    }

    private fun resolvePackageAtExport(uid: Int): String {
        if (!isTrackableAppUid(uid)) return "uid:$uid"
        uidToPackage[uid]?.let { return it }
        val resolved = runCatching {
            packageResolver(uid)?.takeIf { it.isNotEmpty() }
                ?: if (uid >= 100_000) packageResolver(uid % 100_000)?.takeIf { it.isNotEmpty() } else null
        }.getOrNull()
        if (resolved != null) {
            cachePackageName(uid, resolved)
            return resolved
        }
        return "uid:$uid"
    }

    private fun exportScopeApps(scope: ScopeBucket, windowElapsedMs: Long): List<RawAppExport> {
        if (scope.apps.isEmpty()) return emptyList()
        return scope.apps.values.map { acc ->
            RawAppExport(
                uid = acc.uid,
                freezeCount = acc.freezeCount,
                wakeByCategory = acc.toWakeMap(),
                interceptByRule = acc.toInterceptMap(),
                frozenMillis = acc.frozenMillis,
                observedMillis = windowElapsedMs.coerceAtLeast(acc.frozenMillis),
                protectedCount = acc.protectedCount,
                killBlockedCount = acc.killBlockedCount,
                topRawReasons = acc.toTopRawReasons(),
            )
        }
    }

    private fun estimateMemoryBytesLocked(): Long {
        var bytes = 4096L + MAX_RECENT_EVENTS * 72L
        for (d in days.values) {
            bytes += 512L
            for (acc in d.apps.values) {
                bytes += 256L + acc.rawReasons.size * 48L
            }
        }
        bytes += 256L
        for (acc in bootBucket.apps.values) {
            bytes += 256L + acc.rawReasons.size * 48L
        }
        bytes += uidToPackage.size * 64L
        bytes += activeFrozenSinceByUid.size * 32L
        return bytes
    }

    private fun epochDay(timeMillis: Long): Long =
        Instant.ofEpochMilli(timeMillis).atZone(zoneId).toLocalDate().toEpochDay()

    private fun hourOfDay(timeMillis: Long): Int =
        Instant.ofEpochMilli(timeMillis).atZone(zoneId).hour.coerceIn(0, 23)

    private fun startOfDayMillis(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()

    private fun weekdayLabel(epochDay: Long): String {
        return when (LocalDate.ofEpochDay(epochDay).dayOfWeek) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
        }
    }

    private fun intArrayToWakeMap(arr: IntArray): Map<WakeCategory, Int> {
        val map = LinkedHashMap<WakeCategory, Int>()
        for (cat in WakeCategory.entries) {
            val v = arr[cat.ordinal]
            if (v > 0) map[cat] = v
        }
        return map
    }

    private fun intArrayToInterceptMap(arr: IntArray): Map<InterceptRule, Int> {
        val map = LinkedHashMap<InterceptRule, Int>()
        for (rule in InterceptRule.entries) {
            val v = arr[rule.ordinal]
            if (v > 0) map[rule] = v
        }
        return map
    }

    private fun fillWakeArray(target: IntArray, source: Map<WakeCategory, Int>) {
        for ((cat, count) in source) {
            if (count > 0) target[cat.ordinal] = count
        }
    }

    private fun fillInterceptArray(target: IntArray, source: Map<InterceptRule, Int>) {
        for ((rule, count) in source) {
            if (count > 0) target[rule.ordinal] = count
        }
    }

    private data class RawAppExport(
        val uid: Int,
        val freezeCount: Int,
        val wakeByCategory: Map<WakeCategory, Int>,
        val interceptByRule: Map<InterceptRule, Int>,
        val frozenMillis: Long,
        val observedMillis: Long,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val topRawReasons: List<Pair<String, Int>>,
    )

    private data class RawSnapshotCopy(
        val sinceMillis: Long,
        val generatedAtMillis: Long,
        val freezeCount: Int,
        val wakeByCategory: Map<WakeCategory, Int>,
        val screenOffWakeCount: Int,
        val screenOffMillis: Long,
        val interceptByRule: Map<InterceptRule, Int>,
        val protectedCount: Int,
        val killBlockedCount: Int,
        val frozenMillisTotal: Long,
        val buckets: List<StatsTimeBucket>,
        val apps: List<RawAppExport>,
        val recent: List<StatsRecentEventData>,
        val memoryBytes: Long,
    )
}

/**
 * system_server 侧墓碑统计采集单例（封装生命周期、亮灭屏监听、定时落盘与热重载同步）。
 */
object FreezeStatsRecorder {

    const val STATS_FILE_PATH = "/data/system/XMillet/stats.v1"

    @Volatile
    var isDispatchThawEventActive: Boolean = false
        private set

    private val cachedBootId: String by lazy {
        runCatching {
            val f = File("/proc/sys/kernel/random/boot_id")
            if (f.canRead()) f.readText().trim() else ""
        }.getOrDefault("")
    }

    private val engine = FreezeStatsEngine(
        storageFile = File(STATS_FILE_PATH),
        clock = { System.currentTimeMillis() },
        elapsedClock = { SystemClock.elapsedRealtime() },
        bootIdProvider = { cachedBootId },
        enabledProvider = { PrefReader.systemPrefs.freezeStats },
        packageResolver = { uid -> SystemState.getPackageNameForUid(uid) },
        frozenUidsProvider = {
            val service = SystemState.greezeService
            if (SystemState.isGreezeReady && service != null &&
                RomCapabilities.hasMethod(service.javaClass.name, "getFrozenUids", service.javaClass.classLoader)
            ) {
                SystemState.getFrozenUidsArray().toHashSet()
            } else {
                null
            }
        },
        asyncIoExecutor = { task -> Logger.postIoTask(task) },
        dispatchThawEventActiveProvider = { isDispatchThawEventActive },
    )

    val isScreenOn: Boolean
        get() = engine.isScreenOn

    private val lifecycleLock = Any()

    @Volatile
    private var periodicExecutor: ScheduledExecutorService? = null

    @Volatile
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var registeredScreenContext: Context? = null

    private const val MAX_SCREEN_RECEIVER_RETRY = 3
    private const val BASE_SCREEN_RECEIVER_RETRY_DELAY_MS = 1_000L
    private var screenReceiverRetryAttempt = 0
    private var screenRetryHandler: Handler? = null

    /**
     * 启动 / 热重载后初始化统计引擎：恢复本地持久化、启动 30 分钟定时落盘并尽量挂上亮灭屏监听。
     */
    fun init() {
        runCatching { cachedBootId }
        val restored = runCatching { engine.restoreFromDisk() }.getOrDefault(false)
        if (restored) {
            Logger.alwaysInfo("墓碑统计已从 $STATS_FILE_PATH 恢复")
        }
        startPeriodicFlush()
        // 冷启动初期 AMS 尚未发布时由 S2_Gate 延迟挂载；仅在热重载（服务已就绪）时立即注册
        if (SystemState.isGreezeReady) {
            SystemState.systemContext?.let { registerScreenReceiver(it) }
        }
    }

    /**
     * 热重载卸载前同步落盘，并释放定时器与广播接收器，避免旧一代泄漏。
     */
    fun onHotReloading() {
        stopPeriodicFlush()
        unregisterScreenReceiver()
        runCatching { engine.saveSync() }
    }

    fun setDispatchThawEventActive(active: Boolean) {
        isDispatchThawEventActive = active
    }

    fun onEnabledChanged(enabled: Boolean) {
        engine.onEnabledChanged(enabled)
    }

    fun isUidTrackedFrozen(uid: Int): Boolean = engine.isUidTrackedFrozen(uid)

    fun cachePackageHint(uid: Int, packageName: String) {
        engine.cachePackageHint(uid, packageName)
    }

    fun getCachedPackageName(uid: Int): String? = engine.getCachedPackageName(uid)

    fun getCachedUidForPackage(packageName: String): Int = engine.getCachedUidForPackage(packageName)

    fun recordFreeze(
        uid: Int,
        reason: String,
        packageNameHint: String? = null,
        onlyIfNotFrozen: Boolean = false,
    ) {
        engine.recordFreeze(uid, reason, packageNameHint, onlyIfNotFrozen)
    }

    fun recordFreezeBatch(
        uids: List<Int>,
        reason: String,
        onlyIfNotFrozen: Boolean = true,
    ) {
        engine.recordFreezeBatch(uids, reason, onlyIfNotFrozen)
    }

    fun recordThaw(uid: Int, reason: String, packageNameHint: String? = null) {
        engine.recordThaw(uid, reason, packageNameHint)
    }

    fun recordIntercept(
        uid: Int,
        rule: InterceptRule,
        reason: String,
        packageNameHint: String? = null,
    ) {
        engine.recordIntercept(uid, rule, reason, packageNameHint)
    }

    fun recordSystemIntercept(rule: InterceptRule, reason: String) {
        engine.recordSystemIntercept(rule, reason)
    }

    fun recordProtect(uid: Int, reason: String, packageNameHint: String? = null) {
        engine.recordProtect(uid, reason, packageNameHint)
    }

    fun recordKillBlocked(uid: Int, reason: String, packageNameHint: String? = null) {
        engine.recordKillBlocked(uid, reason, packageNameHint)
    }

    fun buildSnapshot(window: StatsWindow): FreezeStatsSnapshotData {
        return engine.buildSnapshot(window)
    }

    fun reset(): Boolean {
        return engine.reset()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerScreenReceiver(context: Context) {
        synchronized(lifecycleLock) {
            if (screenReceiver != null) return
            val targetCtx = context.applicationContext ?: context
            // 在非 Hook 回调的初始化时机读一次 PowerManager，准确对齐当前屏幕亮灭初值
            val initialScreenOn = runCatching {
                targetCtx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
            }.getOrDefault(true)
            engine.initScreenState(initialScreenOn)

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> engine.onScreenStateChanged(true)
                        Intent.ACTION_SCREEN_OFF -> engine.onScreenStateChanged(false)
                    }
                }
            }
            runCatching {
                targetCtx.registerReceiver(receiver, filter)
                screenReceiver = receiver
                registeredScreenContext = targetCtx
                cancelScreenReceiverRetryLocked()
            }.onFailure {
                Logger.error("FreezeStatsRecorder registerScreenReceiver failed", it)
                scheduleScreenReceiverRetryLocked(targetCtx)
            }
        }
    }

    private fun scheduleScreenReceiverRetryLocked(context: Context) {
        if (screenReceiver != null || screenReceiverRetryAttempt >= MAX_SCREEN_RECEIVER_RETRY) {
            return
        }
        val attempt = ++screenReceiverRetryAttempt
        val delayMs = BASE_SCREEN_RECEIVER_RETRY_DELAY_MS shl (attempt - 1)
        val handler = screenRetryHandler ?: Handler(Looper.getMainLooper()).also { screenRetryHandler = it }
        Logger.alwaysWarn("FreezeStatsRecorder 亮灭屏广播注册失败，${delayMs}ms 后补挂（第 $attempt/$MAX_SCREEN_RECEIVER_RETRY 次）")
        handler.postDelayed({
            registerScreenReceiver(context)
        }, delayMs)
    }

    private fun cancelScreenReceiverRetryLocked() {
        screenReceiverRetryAttempt = 0
        screenRetryHandler?.removeCallbacksAndMessages(null)
        screenRetryHandler = null
    }

    private fun unregisterScreenReceiver() {
        synchronized(lifecycleLock) {
            cancelScreenReceiverRetryLocked()
            val receiver = screenReceiver ?: return
            val ctx = registeredScreenContext
            screenReceiver = null
            registeredScreenContext = null
            if (ctx != null) {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    private fun startPeriodicFlush() {
        synchronized(lifecycleLock) {
            if (periodicExecutor != null) return
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "XMillet-StatsFlush").apply { isDaemon = true }
            }
            executor.scheduleWithFixedDelay(
                {
                    Logger.postIoTask {
                        engine.saveIfDirty()
                    }
                },
                FreezeStatsEngine.PERIODIC_SAVE_INTERVAL_MS,
                FreezeStatsEngine.PERIODIC_SAVE_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            periodicExecutor = executor
        }
    }

    private fun stopPeriodicFlush() {
        synchronized(lifecycleLock) {
            periodicExecutor?.shutdownNow()
            periodicExecutor = null
        }
    }
}
