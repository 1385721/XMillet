package io.github.univers629.millet.core

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 常驻 root shell。
 *
 * 每一次 `Runtime.exec("su")` 都是一次超级用户请求，Magisk / KernelSU 默认会为此弹一次提示。
 * 刷新进程快照这类高频操作如果每次都新起 `su`，就会变成每分钟二十次 toast。
 *
 * 这里保持一条常驻 shell：命令写进它的 stdin，再追加一行哨兵标记，从 stdout 读到哨兵为止。
 * 整个进程生命周期只需要一次授权请求。
 *
 * 约束与取舍：
 * - 命令严格串行（单把锁），避免两条命令的输出互相串味；
 * - 单条命令超过 [DEFAULT_TIMEOUT_MS] 未结束就丢弃整条 shell，下次调用时重建。
 *   超时命令可能仍在输出，继续复用会把它的残留算进下一条命令；
 * - `su -M` 是另一条 shell：挂载命名空间只能在启动时指定，运行中换不了。
 */
internal object RootShell {

    private const val SENTINEL_PREFIX = "__XMILET_SHELL_DONE__"
    private const val EOF_MARKER = "\u0000__XMILET_SHELL_EOF__"
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    private class Session(val mountMaster: Boolean) {
        val process: Process = Runtime.getRuntime().exec(if (mountMaster) "su -M" else "su")
        val writer = DataOutputStream(process.outputStream)
        val output = LinkedBlockingQueue<String>()

        @Volatile
        var eof: Boolean = false

        init {
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.forEach { output.put(it) }
                    }
                } catch (_: Throwable) {
                    // 进程被杀或流已关闭：下面按 EOF 收尾，由调用方丢弃这条 shell
                } finally {
                    eof = true
                    runCatching { output.put(EOF_MARKER) }
                }
            }.apply {
                isDaemon = true
                name = "XMillet-RootShell"
                start()
            }
        }

        fun close() {
            runCatching { writer.close() }
            runCatching { process.destroy() }
        }
    }

    private val lock = Any()
    private var plainSession: Session? = null
    private var masterSession: Session? = null
    private var sequence: Long = 0L

    /**
     * 在常驻 shell 里执行一条命令。
     *
     * @return 命令的标准输出行；shell 不可用或命令超时时返回空列表（调用方按「没读到」处理）
     */
    fun exec(
        command: String,
        mountMaster: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<String> = synchronized(lock) {
        val session = ensureSession(mountMaster) ?: return@synchronized emptyList()
        val sentinel = SENTINEL_PREFIX + (++sequence)
        val lines = ArrayList<String>()
        var outcome: List<String>? = null

        try {
            session.writer.writeBytes(command)
            session.writer.writeBytes("\necho \"$sentinel\"\n")
            session.writer.flush()

            val deadline = System.currentTimeMillis() + timeoutMs
            while (outcome == null) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) {
                    Logger.warn("root shell 命令超时（${timeoutMs}ms），丢弃这条 shell：${command.take(60)}")
                    discard(mountMaster)
                    outcome = emptyList()
                    break
                }
                val line = session.output.poll(remain, TimeUnit.MILLISECONDS)
                when {
                    line == null -> Unit
                    line == sentinel -> outcome = lines
                    line == EOF_MARKER -> {
                        Logger.warn("root shell 已断开，下次调用重建：${command.take(60)}")
                        discard(mountMaster)
                        outcome = emptyList()
                    }
                    else -> lines.add(line)
                }
            }
        } catch (t: Throwable) {
            Logger.warn("root shell 执行失败（${t.javaClass.simpleName}），丢弃这条 shell")
            discard(mountMaster)
            outcome = emptyList()
        }

        outcome ?: emptyList()
    }

    private fun ensureSession(mountMaster: Boolean): Session? {
        val cached = if (mountMaster) masterSession else plainSession
        if (cached != null && !cached.eof) return cached
        cached?.close()

        val fresh = try {
            Session(mountMaster)
        } catch (t: Throwable) {
            Logger.warn("无法启动 root shell（${t.javaClass.simpleName}）")
            null
        }
        if (mountMaster) masterSession = fresh else plainSession = fresh
        return fresh
    }

    private fun discard(mountMaster: Boolean) {
        if (mountMaster) {
            masterSession?.close()
            masterSession = null
        } else {
            plainSession?.close()
            plainSession = null
        }
    }
}
