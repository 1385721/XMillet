package io.github.univers629.millet.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.util.Locale

/**
 * SM-LOG 统一日志组件。
 *
 * 规范要求：
 * 1. 统一 Tag：`SM-LOG`
 * 2. 标准事件格式：`[级别] 事件 | 包名 | uid | 原因`
 * 3. 安装期日志（`开始XMillet Hook`、`xxx成功`、`共屏蔽N处应用无响应`、安装失败错误）不受 `isLog` 限制直接输出。
 * 4. 运行期干预日志受 `isLog`（或 Doze 专属 `dozeLog`）门控。
 * 5. 日志等级可选「关闭(OFF)」：选中后完全不输出任何日志（含 alwaysWarn/error）。
 * 6. 不做本地文件落盘：日志仅输出到 Modern LibXposed / Android Logcat，避免文件 IO。
 */
object Logger {

    const val TAG = "SM-LOG"
    const val LOCAL_LOG_DIR = "/data/system/XMillet"
    const val LOCAL_LOG_PATH = "/data/system/XMillet/log"
    const val LOCAL_LAST_LOG_PATH = "/data/system/XMillet/last.log"

    enum class Level(val code: String, val priority: Int) {
        DEBUG("D", 1),
        INFO("I", 2),
        WARN("W", 3),
        ERROR("E", 4),
        OFF("-", 5);

        companion object {
            fun fromConfig(name: String?): Level {
                return when (name?.lowercase(Locale.ROOT)) {
                    "off", "o" -> OFF
                    "debug", "d" -> DEBUG
                    "info", "i", "verbose", "v" -> INFO
                    "warn", "w" -> WARN
                    "error", "e" -> ERROR
                    else -> DEBUG
                }
            }
        }
    }

    /** 单线程异步 IO Handler，避免在 Hook 回调中阻塞宿主线程 */
    private val ioLock = Any()

    @Volatile
    private var ioThread: HandlerThread? = null

    @Volatile
    private var ioHandler: Handler? = null

    @Volatile
    private var hotReloading: Boolean = false

    // 优化 #1：缓存 logLevel 的解析结果，避免每条日志都做 lowercase + when 解析。
    // 仅在 logLevel 配置字符串变化时重新解析；@Volatile 保证跨进程热重载后的可见性。
    @Volatile
    private var cachedLogLevelName: String? = null

    @Volatile
    private var cachedMinLevel: Level = Level.DEBUG

    private fun ensureIoHandler(): Handler? {
        if (hotReloading) return null
        ioHandler?.let { return it }
        synchronized(ioLock) {
            if (hotReloading) return null
            ioHandler?.let { return it }
            val thread = HandlerThread("XMillet-LogIo", Process.THREAD_PRIORITY_BACKGROUND)
            thread.start()
            val handler = Handler(thread.looper)
            ioThread = thread
            ioHandler = handler
            return handler
        }
    }

    /**
     * 热重载卸载前安全退出日志 IO 线程，排空已有任务且不再接收新异步投递。
     */
    fun onHotReloading() {
        val thread: HandlerThread?
        synchronized(ioLock) {
            hotReloading = true
            ioHandler = null
            thread = ioThread
            ioThread = null
        }
        thread?.quitSafely()
    }

    private fun currentSnapshot(): PrefsSnapshot {
        return PrefReader.activePrefs()
    }

    /**
     * 格式化结构化干预事件：`事件 | 包名 | uid | 原因`
     */
    fun formatEvent(
        event: String,
        pkg: String = "-",
        uid: Int = -1,
        reason: String = "-"
    ): String {
        val uidStr = if (uid >= 0) uid.toString() else "-"
        return "$event | $pkg | $uidStr | $reason"
    }

    /**
     * 结构化运行期日志（受 `isLog` 门控）。
     */
    fun logEvent(
        level: Level,
        event: String,
        pkg: String = "-",
        uid: Int = -1,
        reason: String = "-"
    ) {
        val prefs = currentSnapshot()
        if (!prefs.isLog && level != Level.ERROR) return
        emit(level, formatEvent(event, pkg, uid, reason), prefs)
    }

    /**
     * 运行期 Debug 日志（受 `isLog` 门控）。
     */
    fun debug(message: String) {
        val prefs = currentSnapshot()
        if (!prefs.isLog) return
        emit(Level.DEBUG, message, prefs)
    }

    /**
     * 运行期 Info 日志（受 `isLog` 门控）。
     */
    fun info(message: String) {
        val prefs = currentSnapshot()
        if (!prefs.isLog) return
        emit(Level.INFO, message, prefs)
    }

    /**
     * 运行期 Warn 日志（受 `isLog` 门控）。
     */
    fun warn(message: String) {
        val prefs = currentSnapshot()
        if (!prefs.isLog) return
        emit(Level.WARN, message, prefs)
    }

    /**
     * Doze 专属日志（受 `dozeLog` 或 `isLog` 门控）。
     */
    fun doze(level: Level, message: String) {
        val prefs = currentSnapshot()
        if (!prefs.dozeLog && !prefs.isLog) return
        emit(level, message, prefs)
    }

    /**
     * 电量观测专属日志（受 `batteryChangeObserve` 或 `isLog` 门控）。
     */
    fun battery(level: Level, message: String) {
        val prefs = currentSnapshot()
        if (!prefs.batteryChangeObserve && !prefs.isLog) return
        emit(level, message, prefs)
    }

    /**
     * 装配期/无条件 Info 日志（不受 `isLog` 限制，用于安装成功回执等）。
     */
    fun alwaysInfo(message: String) {
        emit(Level.INFO, message, currentSnapshot())
    }

    /**
     * 装配期/无条件 Warn 日志（用于 `开始XMillet Hook`、`共屏蔽N处应用无响应`、每个成功项等）。
     */
    fun alwaysWarn(message: String) {
        emit(Level.WARN, message, currentSnapshot())
    }

    /**
     * 错误日志（无条件输出，附带异常摘要）。
     */
    fun error(message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) {
            "$message: ${throwable.javaClass.simpleName}(${throwable.message})"
        } else {
            message
        }
        emit(Level.ERROR, fullMsg, currentSnapshot())
    }

    /**
     * 后台单线程异步任务投递入口（供 `/proc` 读取等非阻塞操作复用）。
     */
    fun postIoTask(task: () -> Unit) {
        val handler = ensureIoHandler() ?: return
        try {
            handler.post {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.e(TAG, "[E] async task error: ${t.message}")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 仅在配置字符串变化时重新解析日志等级，热路径只做一次引用比较。 */
    private fun resolveMinLevel(configName: String?): Level {
        val cachedName = cachedLogLevelName
        if (cachedName == configName) return cachedMinLevel
        val parsed = Level.fromConfig(configName)
        cachedLogLevelName = configName
        cachedMinLevel = parsed
        return parsed
    }

    private fun emit(
        level: Level,
        body: String,
        prefs: PrefsSnapshot
    ) {
        val minLevel = resolveMinLevel(prefs.logLevel)
        // OFF：完全关闭日志，含 alwaysWarn/error。ERROR 的穿透逻辑也不能越过此门。
        if (minLevel == Level.OFF) return
        if (level.priority < minLevel.priority && level != Level.ERROR) {
            return
        }

        // 输出到 Modern LibXposed；框架未注入时退回 Android Logcat
        var loggedToXposed = false
        if (prefs.logType != "file") {
            try {
                if (ModernBridge.isModernActive) {
                    val priority = when (level) {
                        Level.DEBUG -> Log.DEBUG
                        Level.INFO -> Log.INFO
                        Level.WARN -> Log.WARN
                        Level.ERROR -> Log.ERROR
                        Level.OFF -> return
                    }
                    ModernBridge.xposedModule?.log(priority, TAG, "[${level.code}] $body")
                    loggedToXposed = true
                }
            } catch (_: Throwable) {
            }
        }

        if (!loggedToXposed) {
            when (level) {
                Level.DEBUG -> Log.d(TAG, "[${level.code}] $body")
                Level.INFO -> Log.i(TAG, "[${level.code}] $body")
                Level.WARN -> Log.w(TAG, "[${level.code}] $body")
                Level.ERROR -> Log.e(TAG, "[${level.code}] $body")
                Level.OFF -> Unit
            }
        }
    }
}
