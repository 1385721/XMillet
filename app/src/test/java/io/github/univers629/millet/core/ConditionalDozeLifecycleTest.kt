package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionalDozeLifecycleTest {
    private class Rig {
        val lifecycle = ConditionalDozeLifecycle()
        var state = ConditionalDozeLifecycle.Conditions(
            screenOn = false, charging = false, enabled = true, ready = true,
            stopped = false, forced = false, deepIdle = false,
        )
        val commands = mutableListOf<String>()
        var enableSucceeds = true
        var forceSucceeds = true
        var unforceSucceeds = true
        var forceChangesFlag = true
        var unforceChangesFlag = true
        var afterEnable: () -> Unit = {}
        var afterForce: () -> Unit = {}
        var afterRead: () -> Unit = {}

        fun schedule(): Long = requireNotNull(lifecycle.schedule(state))

        fun run(token: Long): Boolean = lifecycle.run(
            token,
            read = { state.also { afterRead() } },
            enable = {
                commands += "enable"
                afterEnable()
                enableSucceeds
            },
            force = {
                commands += "force"
                if (forceChangesFlag) state = state.copy(forced = true)
                afterForce()
                forceSucceeds
            },
            unforce = ::unforce,
        )

        private fun unforce(): Boolean {
            commands += "unforce"
            if (unforceChangesFlag) state = state.copy(forced = false)
            return unforceSucceeds
        }

        fun release(): Boolean = lifecycle.release({ state.forced }, ::unforce)
    }

    @Test
    fun rapidScreenOffOnOffDoesNotReviveOldToken() {
        val rig = Rig()
        val oldToken = rig.schedule()
        rig.state = rig.state.copy(screenOn = true)
        rig.lifecycle.cancel()
        assertFalse(rig.run(oldToken))
        rig.state = rig.state.copy(screenOn = false)
        val newToken = rig.schedule()
        assertFalse(rig.run(oldToken))
        assertTrue(rig.commands.isEmpty())
        assertTrue(rig.run(newToken))
        assertEquals(listOf("enable", "force"), rig.commands)
        assertFalse(rig.run(newToken))
    }

    @Test
    fun repeatedSchedulingInvalidatesPreviousAlarm() {
        val rig = Rig()
        val first = rig.schedule()
        val second = rig.schedule()
        assertFalse(rig.run(first))
        assertTrue(rig.run(second))
    }

    @Test
    fun schedulingRequiresKnownScreenChargingForceAndIdleState() {
        val rig = Rig()
        val allowed = rig.state
        val denied = listOf(
            allowed.copy(screenOn = true), allowed.copy(screenOn = null),
            allowed.copy(charging = true), allowed.copy(charging = null),
            allowed.copy(enabled = false), allowed.copy(ready = false),
            allowed.copy(stopped = true), allowed.copy(forced = true),
            allowed.copy(forced = null), allowed.copy(deepIdle = true),
            allowed.copy(deepIdle = null),
        )
        for (state in denied) assertNull(rig.lifecycle.schedule(state))
        assertNotNull(rig.lifecycle.schedule(allowed))
    }

    @Test
    fun executionRechecksScreenChargerPreferencesAndHotReload() {
        val baseline = Rig().state
        for (denied in listOf(
            baseline.copy(screenOn = true), baseline.copy(charging = true),
            baseline.copy(enabled = false), baseline.copy(ready = false),
            baseline.copy(stopped = true), baseline.copy(forced = null),
            baseline.copy(screenOn = null), baseline.copy(charging = null),
        )) {
            val rig = Rig()
            val token = rig.schedule()
            rig.state = denied
            assertFalse(rig.run(token))
            assertTrue(rig.commands.isEmpty())
            assertFalse(rig.release())
        }
    }

    @Test
    fun enableFailureDoesNotForceOrOwnIdle() {
        val rig = Rig()
        rig.enableSucceeds = false
        assertFalse(rig.run(rig.schedule()))
        assertEquals(listOf("enable"), rig.commands)
        assertFalse(rig.lifecycle.ownsForce)
        assertFalse(rig.release())
    }

    @Test
    fun conditionsAndCancellationAreRecheckedAfterEnable() {
        val baseline = Rig().state
        for (denied in listOf(
            baseline.copy(screenOn = true), baseline.copy(charging = true),
            baseline.copy(enabled = false), baseline.copy(stopped = true),
            baseline.copy(screenOn = null), baseline.copy(charging = null),
            baseline.copy(ready = false),
            baseline.copy(forced = true), baseline.copy(forced = null),
            baseline.copy(deepIdle = true),
        )) {
            val rig = Rig()
            rig.afterEnable = { rig.state = denied }
            assertFalse(rig.run(rig.schedule()))
            assertEquals(listOf("enable"), rig.commands)
            assertFalse(rig.release())
        }
        val rig = Rig()
        rig.afterEnable = { rig.lifecycle.cancel() }
        assertFalse(rig.run(rig.schedule()))
        assertEquals(listOf("enable"), rig.commands)
    }

    @Test
    fun cancellationDuringSnapshotReadPreventsShellStage() {
        val rig = Rig()
        rig.afterRead = { rig.lifecycle.cancel() }
        assertFalse(rig.run(rig.schedule()))
        assertTrue(rig.commands.isEmpty())
        val secondStage = Rig()
        var reads = 0
        secondStage.afterRead = { if (++reads == 2) secondStage.lifecycle.cancel() }
        assertFalse(secondStage.run(secondStage.schedule()))
        assertEquals(listOf("enable"), secondStage.commands)
    }

    @Test
    fun preexistingForceBeforeExecutionIsNeverOwnedOrReleased() {
        val rig = Rig()
        val token = rig.schedule()
        rig.state = rig.state.copy(forced = true, deepIdle = true)
        assertFalse(rig.run(token))
        assertFalse(rig.release())
        assertTrue(rig.commands.isEmpty())
        assertEquals(true, rig.state.forced)
    }

    @Test
    fun naturalDeepIdleCancelsAlarmWithoutTakingOwnership() {
        val rig = Rig()
        val token = rig.schedule()
        rig.state = rig.state.copy(deepIdle = true)
        rig.lifecycle.cancelPendingForIdle()
        assertFalse(rig.run(token))
        assertFalse(rig.release())
        assertTrue(rig.commands.isEmpty())
    }

    @Test
    fun synchronousIdleCallbackDoesNotInvalidateForceAcquisition() {
        val rig = Rig()
        rig.afterForce = {
            rig.state = rig.state.copy(deepIdle = true)
            rig.lifecycle.cancelPendingForIdle()
        }
        assertTrue(rig.run(rig.schedule()))
        assertTrue(rig.lifecycle.ownsForce)
        assertEquals(listOf("enable", "force"), rig.commands)
    }

    @Test
    fun ownedForceIsReleasedWhetherOrNotDeepIdleWasEntered() {
        for (enteredIdle in listOf(false, true)) {
            val rig = Rig()
            rig.afterForce = { rig.state = rig.state.copy(deepIdle = enteredIdle) }
            assertTrue(rig.run(rig.schedule()))
            rig.lifecycle.cancel()
            assertTrue(rig.release())
            assertFalse(rig.lifecycle.ownsForce)
            assertEquals(listOf("enable", "force", "unforce"), rig.commands)
            assertFalse(rig.release())
        }
    }

    @Test
    fun screenChargerAndDisableDuringForceReleaseOnlyAcquiredForce() {
        for (change in listOf<(Rig) -> Unit>(
            { it.state = it.state.copy(screenOn = true) },
            { it.state = it.state.copy(charging = true) },
            { it.state = it.state.copy(enabled = false) },
            { it.lifecycle.cancel() },
            { it.lifecycle.stop() },
        )) {
            val rig = Rig()
            rig.afterForce = { change(rig) }
            assertFalse(rig.run(rig.schedule()))
            assertFalse(rig.lifecycle.ownsForce)
            assertEquals(listOf("enable", "force", "unforce"), rig.commands)
        }
    }

    @Test
    fun failedForceWithoutFlagTransitionDoesNotOwnOrUnforce() {
        val rig = Rig()
        rig.forceSucceeds = false
        rig.forceChangesFlag = false
        assertFalse(rig.run(rig.schedule()))
        assertFalse(rig.lifecycle.ownsForce)
        assertFalse(rig.release())
        assertEquals(listOf("enable", "force"), rig.commands)
    }

    @Test
    fun failedForceWithConfirmedFlagTransitionIsCleanedUp() {
        val rig = Rig()
        rig.forceSucceeds = false
        assertFalse(rig.run(rig.schedule()))
        assertFalse(rig.lifecycle.ownsForce)
        assertEquals(listOf("enable", "force", "unforce"), rig.commands)
    }

    @Test
    fun shellSuccessWithoutConfirmedForceCannotTakeOwnership() {
        val rig = Rig()
        rig.forceChangesFlag = false
        assertFalse(rig.run(rig.schedule()))
        assertFalse(rig.release())
        assertFalse(rig.lifecycle.ownsForce)
        assertEquals(listOf("enable", "force"), rig.commands)
    }

    @Test
    fun unreadableForceAfterShellCannotTakeOwnership() {
        val rig = Rig()
        rig.afterForce = { rig.state = rig.state.copy(forced = null) }
        assertFalse(rig.run(rig.schedule()))
        assertFalse(rig.release())
        assertFalse(rig.lifecycle.ownsForce)
        assertEquals(listOf("enable", "force"), rig.commands)
    }

    @Test
    fun failedOrUnconfirmedUnforceRetainsOwnershipForLaterLifecycleCleanup() {
        val rig = Rig()
        assertTrue(rig.run(rig.schedule()))
        rig.unforceSucceeds = false
        rig.unforceChangesFlag = false
        assertFalse(rig.release())
        assertTrue(rig.lifecycle.ownsForce)
        rig.unforceSucceeds = true
        assertFalse(rig.release())
        assertTrue(rig.lifecycle.ownsForce)
        rig.unforceChangesFlag = true
        assertTrue(rig.release())
        assertFalse(rig.lifecycle.ownsForce)
    }

    @Test
    fun failedUnforceThatClearsFlagDoesNotRetainOwnership() {
        val rig = Rig()
        assertTrue(rig.run(rig.schedule()))
        rig.unforceSucceeds = false
        assertTrue(rig.release())
        assertFalse(rig.lifecycle.ownsForce)
    }

    @Test
    fun externallyClearedOrUnreadableForceDoesNotInvokeUnforce() {
        val rig = Rig()
        assertTrue(rig.run(rig.schedule()))
        rig.state = rig.state.copy(forced = null)
        assertFalse(rig.release())
        assertTrue(rig.lifecycle.ownsForce)
        rig.state = rig.state.copy(forced = false)
        assertTrue(rig.release())
        assertFalse(rig.lifecycle.ownsForce)
        assertEquals(listOf("enable", "force"), rig.commands)
    }

    @Test
    fun hotReloadStopIsTerminalAndOwnedForceCanBeReleased() {
        val rig = Rig()
        val token = rig.schedule()
        rig.lifecycle.stop()
        rig.lifecycle.stop()
        assertFalse(rig.run(token))
        assertNull(rig.lifecycle.schedule(rig.state))
        assertFalse(rig.release())
        assertTrue(rig.commands.isEmpty())
        val owned = Rig()
        assertTrue(owned.run(owned.schedule()))
        owned.lifecycle.stop()
        assertTrue(owned.release())
        assertNull(owned.lifecycle.schedule(owned.state))
    }
}
