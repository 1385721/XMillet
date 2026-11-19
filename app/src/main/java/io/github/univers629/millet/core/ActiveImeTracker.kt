package io.github.univers629.millet.core

import android.content.Context
import android.database.ContentObserver
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 当前激活默认输入法追踪器（供 system_server 与 PowerKeeper 共用）。
 *
 * 仅依据 `Settings.Secure.DEFAULT_INPUT_METHOD` 识别系统当前真正启用的默认输入法包名，
 * 不放行未启用的预装冗余输入法；同时若用户主动将该输入法加入 `forceFreezeSet`，
 * 仍尊重用户的强制冻结策略。
 */
object ActiveImeTracker {

    private const val REFRESH_FALLBACK_INTERVAL_MS = 30_000L

    @Volatile
    private var cachedImePackage: String? = null

    @Volatile
    private var lastRefreshUptimeMs: Long = 0L

    private val observerRegistered = AtomicBoolean(false)

    fun parseImePackage(componentId: String?): String? {
        if (componentId.isNullOrBlank()) return null
        val pkg = componentId.substringBefore('/').trim()
        return pkg.takeIf { it.isNotEmpty() && '.' in it }
    }

    fun resolveDefaultImePackage(context: Context?): String? {
        val ctx = context ?: return cachedImePackage
        ensureObserver(ctx)
        val now = SystemClock.uptimeMillis()
        val cached = cachedImePackage
        if (cached != null && (observerRegistered.get() || now - lastRefreshUptimeMs in 0..REFRESH_FALLBACK_INTERVAL_MS)) {
            return cached
        }
        return refresh(ctx)
    }

    fun isProtectedDefaultIme(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean {
        if (packageName.isNullOrEmpty() || packageName in prefs.forceFreezeSet) return false
        val activeIme = resolveDefaultImePackage(context) ?: return false
        return packageName == activeIme
    }

    private fun refresh(context: Context): String? {
        val token = Binder.clearCallingIdentity()
        return try {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val pkg = parseImePackage(raw)
            cachedImePackage = pkg
            lastRefreshUptimeMs = SystemClock.uptimeMillis()
            pkg
        } catch (_: Throwable) {
            cachedImePackage
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun ensureObserver(context: Context) {
        if (observerRegistered.get()) return
        val looper = Looper.getMainLooper() ?: return
        if (!observerRegistered.compareAndSet(false, true)) return
        val appCtx = context.applicationContext ?: context
        val token = Binder.clearCallingIdentity()
        try {
            val uri = Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD)
            val observer = object : ContentObserver(Handler(looper)) {
                override fun onChange(selfChange: Boolean) {
                    refresh(appCtx)
                }
            }
            appCtx.contentResolver.registerContentObserver(uri, false, observer)
            refresh(appCtx)
        } catch (_: Throwable) {
            observerRegistered.set(false)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
