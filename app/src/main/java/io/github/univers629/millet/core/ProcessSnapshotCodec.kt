package io.github.univers629.millet.core

/**
 * 解析 system_server 通过 `TX_GET_PROCESS_SNAPSHOT` 回传的进程快照。
 *
 * 协议（`ProcessSnapshotProvider` 生成）：
 * ```
 * v1
 * pid|uid|rssKb|vszKb|wchan|elapsedSec|processName|packageName|state|freezerType|svc1;svc2
 * ```
 * 任何一行解析失败都跳过该行，不影响其它进程；版本不认识时整体退化为空列表，
 * 让调用方回退到 root 采集。
 */
object ProcessSnapshotCodec {

    private const val FIELD_COUNT = 11

    fun decode(payload: String?): List<RootHelper.ProcessRawEntry> {
        if (payload.isNullOrEmpty()) return emptyList()
        val lines = payload.lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()
        val header = lines.next().trim()
        if (header != "v1") {
            Logger.warn("进程快照版本不支持: $header")
            return emptyList()
        }
        val result = ArrayList<RootHelper.ProcessRawEntry>()
        for (line in lines) {
            if (line.isBlank()) continue
            val entry = parseLine(line) ?: continue
            result.add(entry)
        }
        return result
    }

    private fun parseLine(line: String): RootHelper.ProcessRawEntry? {
        val parts = line.split('|')
        if (parts.size < FIELD_COUNT) return null
        val pid = parts[0].toIntOrNull() ?: return null
        val uid = parts[1].toIntOrNull() ?: return null
        val rssKb = parts[2].toLongOrNull() ?: 0L
        val vszKb = parts[3].toLongOrNull() ?: 0L
        val wchan = parts[4]
        val elapsedSec = parts[5].toLongOrNull() ?: 0L
        val processName = parts[6]
        val packageName = parts[7].ifEmpty { processName.substringBefore(':') }
        val state = parts[8]
        val freezerType = parseFreezerType(parts[9])
        val services = if (parts.size > 10 && parts[10].isNotEmpty()) {
            parts[10].split(';').filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return RootHelper.ProcessRawEntry(
            uid = uid,
            pid = pid,
            rssKb = rssKb,
            vszKb = vszKb,
            wchan = wchan,
            elapsedStr = formatElapsed(elapsedSec),
            processName = processName,
            packageName = packageName,
            status = state,
            freezerType = freezerType,
            services = services
        )
    }

    private fun parseFreezerType(raw: String): RootHelper.FreezerType = when (raw) {
        "V2" -> RootHelper.FreezerType.V2
        "V1" -> RootHelper.FreezerType.V1
        "KILL" -> RootHelper.FreezerType.KILL
        else -> RootHelper.FreezerType.UNKNOWN
    }

    /** 转成 toybox ps 的 ELAPSED 形态（`HH:MM:SS` / `D-HH:MM:SS`），复用现有格式化逻辑。 */
    private fun formatElapsed(seconds: Long): String {
        if (seconds <= 0L) return ""
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        val secs = seconds % 60
        val clock = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        return if (days > 0) "$days-$clock" else clock
    }
}
