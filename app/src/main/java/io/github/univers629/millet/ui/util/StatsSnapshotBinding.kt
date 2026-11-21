package io.github.univers629.millet.ui.util

import io.github.univers629.millet.core.StatsWindow

internal data class StatsQueryScope(val window: StatsWindow, val isSample: Boolean)

internal data class StatsDetailSelection(val uid: Int, val scope: StatsQueryScope)

/** Prevents a superseded query from publishing into the current window. */
internal class StatsRefreshGate {
    private var generation = 0L
    private var scope: StatsQueryScope? = null

    fun begin(nextScope: StatsQueryScope): Long {
        scope = nextScope
        return ++generation
    }

    fun invalidate() {
        generation++
        scope = null
    }

    fun accepts(ticket: Long, queryScope: StatsQueryScope): Boolean =
        generation == ticket && scope == queryScope
}

internal fun <T> resolveStatsDetail(
    selection: StatsDetailSelection?,
    currentScope: StatsQueryScope,
    snapshotScope: StatsQueryScope?,
    apps: List<T>,
    uidOf: (T) -> Int,
): T? {
    if (selection == null || selection.scope != currentScope || snapshotScope != currentScope) return null
    return apps.firstOrNull { uidOf(it) == selection.uid }
}
