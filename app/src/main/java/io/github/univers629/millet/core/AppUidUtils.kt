package io.github.univers629.millet.core

/**
 * Android 多用户与应用 UID 边界工具。
 *
 * HyperOS 双开空间固定使用 `userId = 999`，UID 形式为 `999 * 100000 + appId`（例如 `99910277`）。
 * 统一通过 [userIdOf] / [appIdOf] 处理，避免各模块把上界写死为 `19999` 而漏掉双开应用。
 */
object AppUidUtils {

    const val PER_USER_RANGE = 100_000
    const val MIN_APP_ID = 10_000
    const val MAX_APP_ID = 19_999
    const val PRIMARY_USER_ID = 0
    const val DUAL_APP_USER_ID = 999

    fun userIdOf(uid: Int): Int = if (uid >= 0) uid / PER_USER_RANGE else -1

    fun appIdOf(uid: Int): Int = if (uid >= 0) uid % PER_USER_RANGE else -1

    fun isDualAppUid(uid: Int): Boolean =
        userIdOf(uid) == DUAL_APP_USER_ID && appIdOf(uid) in MIN_APP_ID..MAX_APP_ID

    fun isManagedAppUid(uid: Int, includeDualApp: Boolean = true): Boolean {
        if (uid < MIN_APP_ID) return false
        val appId = appIdOf(uid)
        if (appId !in MIN_APP_ID..MAX_APP_ID) return false
        val userId = userIdOf(uid)
        return userId == PRIMARY_USER_ID || (includeDualApp && userId == DUAL_APP_USER_ID)
    }

    fun dualUidOf(primaryUid: Int): Int {
        if (primaryUid < MIN_APP_ID) return -1
        val appId = appIdOf(primaryUid)
        if (appId !in MIN_APP_ID..MAX_APP_ID) return -1
        return DUAL_APP_USER_ID * PER_USER_RANGE + appId
    }

    fun encodeQueryTarget(packageName: String, uid: Int): String =
        if (isDualAppUid(uid)) "$packageName#$DUAL_APP_USER_ID" else packageName

    fun decodeQueryTarget(raw: String): Pair<String, Int> {
        val suffix = "#$DUAL_APP_USER_ID"
        return if (raw.endsWith(suffix) && raw.length > suffix.length) {
            raw.removeSuffix(suffix) to DUAL_APP_USER_ID
        } else {
            raw to PRIMARY_USER_ID
        }
    }
}
