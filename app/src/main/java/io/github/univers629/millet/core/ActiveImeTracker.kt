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
    private var cachedAccessibilityPackages: Set<String> = emptySet()

    @Volatile
    private var lastRefreshUptimeMs: Long = 0L

    private val observerRegistered = AtomicBoolean(false)
    private val observerLock = Any()

    @Volatile
    private var registeredContext: Context? = null

    @Volatile
    private var registeredObserver: ContentObserver? = null

    fun onHotReloading() {
        val ctx: Context?
        val observer: ContentObserver?
        synchronized(observerLock) {
            ctx = registeredContext
            observer = registeredObserver
            registeredContext = null
            registeredObserver = null
            observerRegistered.set(false)
            cachedImePackage = null
            cachedAccessibilityPackages = emptySet()
            lastRefreshUptimeMs = 0L
        }
        if (ctx != null && observer != null) {
            val token = Binder.clearCallingIdentity()
            try {
                ctx.contentResolver.unregisterContentObserver(observer)
            } catch (_: Throwable) {
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }

    fun parseImePackage(componentId: String?): String? {
        if (componentId.isNullOrBlank()) return null
        val pkg = componentId.substringBefore('/').trim()
        return pkg.takeIf { it.isNotEmpty() && '.' in it }
    }

    fun parseAccessibilityPackages(enabledFlag: Int, servicesRaw: String?): Set<String> {
        if (enabledFlag != 1 || servicesRaw.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        for (entry in servicesRaw.split(':')) {
            parseImePackage(entry)?.let(result::add)
        }
        return result
    }

    fun resolveDefaultImePackage(context: Context?): String? {
        val ctx = context ?: return cachedImePackage
        ensureObserver(ctx)
        if (!isCacheFresh()) {
            refresh(ctx)
        }
        return cachedImePackage
    }

    fun resolveAccessibilityPackages(context: Context?): Set<String> {
        val ctx = context ?: return cachedAccessibilityPackages
        ensureObserver(ctx)
        if (!isCacheFresh()) {
            refresh(ctx)
        }
        return cachedAccessibilityPackages
    }

    private fun isCacheFresh(): Boolean {
        if (observerRegistered.get() && lastRefreshUptimeMs > 0L) return true
        val now = SystemClock.uptimeMillis()
        return lastRefreshUptimeMs > 0L && (now - lastRefreshUptimeMs in 0..REFRESH_FALLBACK_INTERVAL_MS)
    }

    fun isProtectedDefaultIme(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean {
        if (packageName.isNullOrEmpty() || packageName in prefs.forceFreezeSet) return false
        val activeIme = resolveDefaultImePackage(context) ?: return false
        return packageName == activeIme
    }

    fun isProtectedAccessibilityApp(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean {
        if (packageName.isNullOrEmpty() || packageName in prefs.forceFreezeSet) return false
        return packageName in resolveAccessibilityPackages(context)
    }

    fun isProtectedSystemServiceApp(context: Context?, packageName: String?, prefs: PrefsSnapshot): Boolean =
        isProtectedDefaultIme(context, packageName, prefs) ||
            isProtectedAccessibilityApp(context, packageName, prefs)

    private fun refresh(context: Context): String? {
        val token = Binder.clearCallingIdentity()
        return try {
            val resolver = context.contentResolver
            val rawIme = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val imePkg = parseImePackage(rawIme)
            val a11yEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            val rawA11y = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            cachedImePackage = imePkg
            cachedAccessibilityPackages = parseAccessibilityPackages(a11yEnabled, rawA11y)
            lastRefreshUptimeMs = SystemClock.uptimeMillis()
            imePkg
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
            val resolver = appCtx.contentResolver
            val observer = object : ContentObserver(Handler(looper)) {
                override fun onChange(selfChange: Boolean) {
                    refresh(appCtx)
                }
            }
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                observer
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer
            )
            synchronized(observerLock) {
                registeredContext = appCtx
                registeredObserver = observer
            }
            refresh(appCtx)
        } catch (_: Throwable) {
            observerRegistered.set(false)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
