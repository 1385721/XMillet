package io.github.univers629.millet.system

import io.github.univers629.millet.core.ActiveImeTracker
import io.github.univers629.millet.core.AppUidUtils
import io.github.univers629.millet.core.Logger
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.ServiceRoleProtection
import io.github.univers629.millet.core.XReflect
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 让「强制冻结（forceFreezeSet）」真正生效。
 *
 * 澎湃 1.0/2.0 上，这份名单会被写进 `MilletPolicy` 的黑名单表，由电量与性能自己下发冻结；
 * 澎湃 3.0（Android 16）删掉了 `MilletPolicy`，模块名单不再被 PowerKeeper 读取，
 * 「强制冻结」退化为只影响本模块的判断。这里同时通过 `GreezeManagerService`
 * 主动把名单里的后台应用推入墓碑。
 *
 * 真机 `GreezeManagerService`（HyperOS 1/2/3）暴露的是批量接口
 * `freezeUids(int[] uids, long timeout, int fromWho, String reason, boolean checkFg)`，
 * 优先按此签名调用，并在找不到时兼容回退 `freezeUid(...)` 重载。
 * 只在 Greeze 就绪（`SystemState.isGreezeReady`）后执行；所有调用都在后台线程，
 * 任何一次失败只记日志、不打断后续 uid，避免系统侧异常扩散到 system_server 主线程。
 */
object ForceFreezeBridge {

    private const val FROM_WHO_MILLET = 1
    private const val FORCE_FREEZE_REASON = "XMillet force freeze"

    /** 已完成下发裁决的名单；前台暂缓与原生拒绝不记为完成。 */
    @Volatile
    private var lastApplied: Set<String> = emptySet()

    private val applying = AtomicBoolean(false)

    private sealed interface FreezeOutcome {
        data class Done(val accepted: Boolean) : FreezeOutcome
        data class Failed(val error: Throwable) : FreezeOutcome
    }

    /** 名单变化或 Greeze 刚就绪时触发一次异步下发。 */
    fun applyAsync(force: Boolean = false) {
        if (!SystemState.isGreezeReady) return
        val snapshot = PrefReader.systemPrefs.forceFreezeSet
        if (snapshot.isEmpty()) {
            lastApplied = emptySet()
            return
        }
        if (!force && snapshot == lastApplied) return
        if (!applying.compareAndSet(false, true)) return

        Thread({
            try {
                while (true) {
                    val target = PrefReader.systemPrefs.forceFreezeSet
                    if (target.isEmpty()) {
                        lastApplied = emptySet()
                        break
                    }
                    if (applyNow(target)) {
                        lastApplied = target
                    }
                    // 执行期间名单再次变化则追赶一轮，避免丢最后一次更新
                    if (PrefReader.systemPrefs.forceFreezeSet == target) break
                }
            } catch (t: Throwable) {
                Logger.error("强制冻结下发异常", t)
            } finally {
                applying.set(false)
            }
        }, "XMillet-ForceFreeze").start()
    }

    private fun applyNow(target: Set<String>): Boolean {
        val service = SystemState.greezeService ?: return false
        val invoker = resolveInvoker(service.javaClass)
        if (invoker == null) {
            Logger.warn("强制冻结跳过: ${service.javaClass.name} 未找到兼容的 freezeUids/freezeUid 方法")
            return false
        }

        var frozen = 0
        var skipped = 0
        var complete = true
        val prefs = PrefReader.systemPrefs
        for (policyTarget in target) {
            val (pkg, userId) = AppUidUtils.decodeQueryTarget(policyTarget)
            if (pkg !in AppUidUtils.policyPackagesForUser(target, userId)) {
                skipped++
                continue
            }
            if (userId == AppUidUtils.DUAL_APP_USER_ID && !prefs.freezeDualApp) {
                skipped++
                continue
            }
            val uid = if (userId == AppUidUtils.DUAL_APP_USER_ID) {
                SystemState.getDualPackageUid(pkg)
            } else {
                SystemState.getPackageUid(pkg)
            }
            if (!AppUidUtils.isManagedAppUid(uid) || AppUidUtils.userIdOf(uid) != userId) {
                complete = false
                Logger.info("强制冻结 $policyTarget 未解析到目标用户UID")
                continue
            }
            if (AppUidUtils.containsPolicyTarget(prefs.whiteAppSet, pkg, uid) ||
                SystemState.isSystemAppUid(uid) ||
                SystemState.isUidFrozen(uid)
            ) {
                skipped++
                continue
            }
            val protection = ActiveImeTracker.resolveServiceRoleProtection(SystemState.systemContext, pkg, uid)
            if (protection.blocksFreeze) {
                if (protection == ServiceRoleProtection.UNKNOWN) complete = false
                skipped++
                continue
            }
            if (SystemState.isPackageCurrentlyForeground(pkg)) {
                complete = false
                skipped++
                continue
            }
            when (val outcome = invoker(service, uid, FORCE_FREEZE_REASON, false)) {
                is FreezeOutcome.Done -> if (outcome.accepted) {
                    frozen++
                } else {
                    complete = false
                    Logger.info("强制冻结 $policyTarget($uid) 未被原生接口接受")
                }
                is FreezeOutcome.Failed -> {
                    complete = false
                    val err = outcome.error
                    Logger.warn("强制冻结 $policyTarget($uid) 失败:${err.javaClass.simpleName}:${err.message}")
                }
            }
        }
        if (frozen > 0 || skipped > 0) {
            Logger.alwaysInfo("强制冻结下发: 原生确认=$frozen, 跳过=$skipped, 名单=${target.size}, 完成=$complete")
        }
        return complete
    }

    /**
     * HyperOS 1 息屏双开冻结兜底：OS1 无 `AurogonImmobulusMode#isUidValid` 与 `checkStateForScrOff`，
     * 且 PowerKeeper 旧版 `millet.*` 全链路写死 `<= 19999`，因此息屏时从系统侧主动扫描并冻结后台双开进程。
     */
    fun freezeDualAppsOnScreenOffAsync() {
        val prefs = PrefReader.systemPrefs
        if (!prefs.freezeDualApp || !SystemState.isGreezeReady) return
        val service = SystemState.greezeService ?: return
        Thread({
            try {
                val invoker = resolveInvoker(service.javaClass) ?: return@Thread
                val dualUids = collectRunningDualAppUids()
                if (dualUids.isEmpty()) return@Thread
                val latestPrefs = PrefReader.systemPrefs
                if (!latestPrefs.freezeDualApp) return@Thread
                var frozen = 0
                var skipped = 0
                val skipDetails = ArrayList<String>()
                for (uid in dualUids) {
                    if (SystemState.isUidFrozen(uid)) {
                        skipped++
                        skipDetails.add("$uid:alreadyFrozen")
                        continue
                    }
                    val pkg = SystemState.getPackageNameForUid(uid) ?: run {
                        skipped++
                        skipDetails.add("$uid:pkgNull")
                        continue
                    }
                    if (AppUidUtils.containsPolicyTarget(latestPrefs.whiteAppSet, pkg, uid) ||
                        ActiveImeTracker.isProtectedSystemServiceApp(SystemState.systemContext, pkg, latestPrefs, uid) ||
                        SystemState.isPackageCurrentlyForeground(pkg)
                    ) {
                        skipped++
                        skipDetails.add("$uid($pkg):exempt")
                        continue
                    }
                    val inForce = AppUidUtils.containsPolicyTarget(latestPrefs.forceFreezeSet, pkg, uid)
                    if (SystemState.isSystemAppUid(uid)) {
                        skipped++
                        skipDetails.add("$uid($pkg):system")
                        continue
                    }
                    if (!inForce &&
                        AppUidUtils.containsPolicyTarget(latestPrefs.notFreezeWhenHasFgsSet, pkg, uid) &&
                        SystemState.checkAppFgService(uid)
                    ) {
                        skipped++
                        skipDetails.add("$uid($pkg):fgs")
                        continue
                    }
                    when (val outcome = invoker(service, uid, "XMillet DualScrOff", !inForce)) {
                        is FreezeOutcome.Done -> {
                            if (outcome.accepted) {
                                frozen++
                            } else {
                                skipped++
                                skipDetails.add("$uid($pkg):false")
                            }
                        }
                        is FreezeOutcome.Failed -> {
                            skipped++
                            val err = outcome.error
                            skipDetails.add("$uid($pkg):err=${err.javaClass.simpleName}")
                            Logger.warn("息屏双开冻结 $pkg($uid) 失败:${err.javaClass.simpleName}:${err.message}")
                        }
                    }
                }
                if (frozen > 0) {
                    Logger.alwaysInfo("息屏双开兜底冻结完成: 已冻结=$frozen, 跳过=$skipped")
                } else {
                    Logger.info("息屏双开兜底未冻结: dual=${dualUids.size}, details=${skipDetails.joinToString(",")}")
                }
            } catch (t: Throwable) {
                Logger.error("息屏双开冻结异常", t)
            }
        }, "XMillet-DualScrOff").start()
    }

    private fun collectRunningDualAppUids(): Set<Int> {
        val result = LinkedHashSet<Int>()
        val procEntries = File("/proc").listFiles() ?: return result
        for (entry in procEntries) {
            val name = entry.name
            if (name.isEmpty() || !name[0].isDigit()) continue
            val pid = name.toIntOrNull() ?: continue
            val uid = SystemState.readUidFromProcStatus(pid)
            if (AppUidUtils.isDualAppUid(uid)) {
                result.add(uid)
            }
        }
        return result
    }

    private fun resolveInvoker(serviceClass: Class<*>): ((Any, Int, String, Boolean) -> FreezeOutcome)? {
        findMethodOrNull(
            serviceClass,
            "freezeUids",
            IntArray::class.java,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        )?.let { method ->
            return { service, uid, reason, checkFg ->
                invokeFreezeMethod {
                    freezeResult(method.invoke(
                        service,
                        intArrayOf(uid),
                        0L,
                        FROM_WHO_MILLET,
                        reason,
                        checkFg
                    ), uid)
                }
            }
        }

        val singleUidTailArgs: List<Pair<Class<*>, Any>> = listOf(
            Boolean::class.javaPrimitiveType!! to false,
            Int::class.javaPrimitiveType!! to 0
        )
        for ((tailType, tailVal) in singleUidTailArgs) {
            findMethodOrNull(
                serviceClass,
                "freezeUid",
                Int::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                tailType
            )?.let { method ->
                return { service, uid, reason, _ ->
                    invokeFreezeMethod {
                        val res = method.invoke(service, uid, 0L, FROM_WHO_MILLET, reason, tailVal)
                        freezeResult(res, uid)
                    }
                }
            }
        }
        return null
    }

    private fun freezeResult(result: Any?, uid: Int): Boolean = when (result) {
        is Boolean -> result
        is Collection<*> -> uid in result
        is IntArray -> uid in result
        else -> throw UnsupportedOperationException("Unconfirmed freeze return type: ${result?.javaClass?.name ?: "void/null"}")
    }

    private inline fun invokeFreezeMethod(block: () -> Boolean): FreezeOutcome =
        runCatching { FreezeOutcome.Done(block()) }
            .getOrElse { FreezeOutcome.Failed(it.cause ?: it) }

    private fun findMethodOrNull(
        clazz: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>
    ) = runCatching { XReflect.findMethodExact(clazz, methodName, *paramTypes) }.getOrNull()
}
