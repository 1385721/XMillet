package io.github.univers629.millet.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SM-LOG 统一日志组件。
 *
 * 规范要求：
 * 1. 统一 Tag：`SM-LOG`
 * 2. 标准事件格式：`[级别] 事件 | 包名 | uid | 原因`
 * 3. 安装期日志（`开始XMillet Hook`、`xxx成功`、`共屏蔽N处应用无响应`、安装失败错误）不受 `isLog` 限制直接输出。
 * 4. 运行期干预日志受 `isLog`（或 Doze 专属 `dozeLog`）门控。
 * 5. 本地文件落盘统一投递至单线程 Handler（`XMillet-LogIo`），不在 Hook 回调线程内执行文件 IO。
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
        ERROR("E", 4);

        companion object {
            fun fromConfig(name: String?): Level {
                return when (name?.lowercase(Locale.ROOT)) {
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
     * system_server 启动时将上一次的 `/data/system/XMillet/log` 轮转为 `last.log`。
     */
    fun initSystemLogRotation() {
        postIoTask {
            try {
                val dir = File(LOCAL_LOG_DIR)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val lastFile = File(LOCAL_LAST_LOG_PATH)
                val logFile = File(LOCAL_LOG_PATH)
                if (lastFile.exists()) {
                    lastFile.delete()
                }
                if (logFile.exists()) {
                    logFile.renameTo(lastFile)
                }
                if (!logFile.exists()) {
                    logFile.createNewFile()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 装配期/无条件 Info 日志（不受 `isLog` 限制，用于安装成功回执等，始终尝试落盘）。
     */
    fun alwaysInfo(message: String) {
        emit(Level.INFO, message, currentSnapshot(), forceLocal = true)
    }

    /**
     * 装配期/无条件 Warn 日志（用于 `开始XMillet Hook`、`共屏蔽N处应用无响应`、每个成功项等，始终尝试落盘）。
     */
    fun alwaysWarn(message: String) {
        emit(Level.WARN, message, currentSnapshot(), forceLocal = true)
    }

    /**
     * 错误日志（无条件输出，附带异常摘要，始终尝试落盘）。
     */
    fun error(message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) {
            "$message: ${throwable.javaClass.simpleName}(${throwable.message})"
        } else {
            message
        }
        emit(Level.ERROR, fullMsg, currentSnapshot(), forceLocal = true)
    }

    /**
     * 后台单线程异步任务投递入口（供 `/proc` 读取、文件落盘等非阻塞操作复用）。
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

    private fun emit(
        level: Level,
        body: String,
        prefs: PrefsSnapshot,
        forceLocal: Boolean = false
    ) {
        val minLevel = Level.fromConfig(prefs.logLevel)
        if (level.priority < minLevel.priority && level != Level.ERROR) {
            return
        }

        val formattedLine = "$TAG [${level.code}] $body"

        // 1. 输出到 Modern LibXposed；框架未注入时退回 Android Logcat
        var loggedToXposed = false
        if (prefs.logType != "file") {
            try {
                if (ModernBridge.isModernActive) {
                    val priority = when (level) {
                        Level.DEBUG -> Log.DEBUG
                        Level.INFO -> Log.INFO
                        Level.WARN -> Log.WARN
                        Level.ERROR -> Log.ERROR
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
            }
        }

        // 2. 如需写入本地文件，统一投递到单线程 IO Handler
        if (prefs.isLog || prefs.logToLocal || prefs.logType == "file" || forceLocal) {
            postIoTask {
                appendLineToLocalFile(level, body)
            }
        }
    }

    private fun appendLineToLocalFile(level: Level, body: String) {
        try {
            val dir = File(LOCAL_LOG_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(LOCAL_LOG_PATH)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            FileWriter(file, true).use { writer ->
                writer.append("$timestamp [${level.code}] $body\n")
            }
        } catch (_: Throwable) {
            // 本地文件写入失败静默降级，不抛异常
        }
    }
}
