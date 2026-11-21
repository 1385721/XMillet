package io.github.univers629.millet.core

/** 解析按需采集的进程快照；解析失败与合法空结果分别处理。 */
object ProcessSnapshotCodec {
    data class Snapshot(val capturedAt: Long, val entries: List<RootHelper.ProcessRawEntry>)

    fun decode(payload: String?): List<RootHelper.ProcessRawEntry> = decodeSnapshot(payload)?.entries.orEmpty()

    fun decodeSnapshot(payload: String?): Snapshot? {
        if (payload.isNullOrEmpty()) return null
        val lines = payload.lineSequence().iterator()
        if (!lines.hasNext()) return null
        val header = lines.next().trim()
        val legacy = header == "v1"
        val fields = header.split('|')
        val capturedAt = if (legacy) 0L else {
            if (fields.size != 2 || fields[0] != "v2") return null
            fields[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        val result = ArrayList<RootHelper.ProcessRawEntry>()
        for (line in lines) {
            if (line.isBlank()) continue
            result.add(parseLine(line, legacy) ?: return null)
        }
        return Snapshot(capturedAt, result)
    }

    private fun parseLine(line: String, legacy: Boolean): RootHelper.ProcessRawEntry? {
        val parts = line.split('|')
        if (parts.size != (if (legacy) 11 else 12)) return null
        val pid = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val uid = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val rssKb = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val vszKb = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val wchan = parts[4]
        val elapsedSec = parts[5].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val processName = parts[6].takeIf { it.isNotEmpty() } ?: return null
        val packageName = if (legacy) parts[7].ifEmpty { processName.substringBefore(':') } else parts[7]
        val state = parts[8]
        if (parts[9] !in setOf("", "V1", "V2", "KILL")) return null
        var freezerType = when (parts[9]) {
            "V2" -> RootHelper.FreezerType.V2
            "V1" -> RootHelper.FreezerType.V1
            "KILL" -> RootHelper.FreezerType.KILL
            else -> RootHelper.FreezerType.UNKNOWN
        }
        // v1 的 V2 可能只来自请求值或普通等待函数，保守降级旧证据。
        if (legacy && freezerType == RootHelper.FreezerType.V2 && !wchan.contains("do_freezer_trap")) {
            freezerType = RootHelper.FreezerType.UNKNOWN
        }
        if (legacy && freezerType == RootHelper.FreezerType.KILL && state != "T") freezerType = RootHelper.FreezerType.UNKNOWN
        if (legacy && state == "T") freezerType = RootHelper.FreezerType.KILL
        if (!legacy && freezerType == RootHelper.FreezerType.KILL && state != "T") return null
        val freezeState = if (legacy) {
            if (freezerType == RootHelper.FreezerType.UNKNOWN) RootHelper.FreezeState.UNKNOWN else RootHelper.FreezeState.FROZEN
        } else {
            RootHelper.FreezeState.entries.firstOrNull { it.name == parts[11] } ?: return null
        }
        if ((freezeState == RootHelper.FreezeState.FROZEN) != (freezerType != RootHelper.FreezerType.UNKNOWN)) return null
        return RootHelper.ProcessRawEntry(
            uid = uid, pid = pid, rssKb = rssKb, vszKb = vszKb, wchan = wchan,
            elapsedStr = formatElapsed(elapsedSec), processName = processName,
            packageName = packageName, status = state, freezerType = freezerType,
            services = parts[10].split(';').filter { it.isNotEmpty() }, freezeState = freezeState
        )
    }

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
