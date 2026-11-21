package io.github.univers629.millet.ui.util

import io.github.univers629.millet.core.StatsWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsSnapshotBindingTest {
    private data class Entry(val uid: Int, val count: Int)
    private val today = StatsQueryScope(StatsWindow.TODAY, false)
    private val week = StatsQueryScope(StatsWindow.WEEK, false)

    @Test
    fun newerQueryRejectsAnOlderResponse() {
        val gate = StatsRefreshGate()
        val old = gate.begin(today)
        val fresh = gate.begin(today)
        assertFalse(gate.accepts(old, today))
        assertTrue(gate.accepts(fresh, today))
    }

    @Test
    fun changingWindowRejectsAnOlderWindow() {
        val gate = StatsRefreshGate()
        val old = gate.begin(today)
        val fresh = gate.begin(week)
        assertFalse(gate.accepts(old, today))
        assertFalse(gate.accepts(fresh, today))
        assertTrue(gate.accepts(fresh, week))
    }

    @Test
    fun leavingThePageInvalidatesTheQuery() {
        val gate = StatsRefreshGate()
        val ticket = gate.begin(today)
        gate.invalidate()
        assertFalse(gate.accepts(ticket, today))
    }

    @Test
    fun detailUsesTheLatestSnapshotForTheFullUid() {
        val selection = StatsDetailSelection(99910131, today)
        val entries = listOf(Entry(10131, 8), Entry(99910131, 12))
        assertEquals(12, resolveStatsDetail(selection, today, today, entries, Entry::uid)?.count)
        assertEquals(15, resolveStatsDetail(selection, today, today, listOf(Entry(99910131, 15)), Entry::uid)?.count)
    }

    @Test
    fun detailDoesNotReuseAnotherWindowOrSample() {
        val selection = StatsDetailSelection(10131, today)
        val entries = listOf(Entry(10131, 8))
        assertNull(resolveStatsDetail(selection, week, week, entries, Entry::uid))
        assertNull(resolveStatsDetail(selection, today, week, entries, Entry::uid))
        assertNull(resolveStatsDetail(selection, today, StatsQueryScope(StatsWindow.TODAY, true), entries, Entry::uid))
    }

    @Test
    fun missingAppDoesNotRetainItsOldDetails() {
        val selection = StatsDetailSelection(10131, today)
        assertNull(resolveStatsDetail(selection, today, today, listOf(Entry(10200, 4)), Entry::uid))
        assertNull(resolveStatsDetail(null, today, today, listOf(Entry(10131, 8)), Entry::uid))
    }
}
