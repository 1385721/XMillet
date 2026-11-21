package io.github.univers629.millet.ui.component.liquid

import androidx.compose.foundation.MutatorMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DampedDragAnimationTest {

    @Test
    fun mutationWaitsForBothAnimations() = runBlocking {
        val mutex = MutatorMutex()
        val valueFinished = CompletableDeferred<Unit>()
        val velocityFinished = CompletableDeferred<Unit>()
        val mutationFinished = CompletableDeferred<Unit>()
        val mutation = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.mutateAnimations {
                launch(start = CoroutineStart.UNDISPATCHED) { valueFinished.await() }
                launch(start = CoroutineStart.UNDISPATCHED) { velocityFinished.await() }
            }
            mutationFinished.complete(Unit)
        }

        assertTrue(mutation.isActive)
        assertFalse(mutationFinished.isCompleted)
        valueFinished.complete(Unit)
        yield()
        assertTrue(mutation.isActive)
        assertFalse(mutationFinished.isCompleted)
        velocityFinished.complete(Unit)
        mutation.join()
        assertTrue(mutationFinished.isCompleted)
    }

    @Test
    fun replacementCancelsChildrenBeforeEnteringNextMutation() = runBlocking {
        val mutex = MutatorMutex()
        val order = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.mutateAnimations {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        order += "value cancelled"
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        order += "velocity cancelled"
                    }
                }
            }
        }
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.mutateAnimations { order += "replacement entered" }
        }

        replacement.join()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals(3, order.size)
        val replacementIndex = order.indexOf("replacement entered")
        assertTrue(order.indexOf("value cancelled") in 0 until replacementIndex)
        assertTrue(order.indexOf("velocity cancelled") in 0 until replacementIndex)
    }

    @Test
    fun cancellingMutationCancelsBothChildren() = runBlocking {
        val mutex = MutatorMutex()
        var finishedChildren = 0
        val mutation = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.mutateAnimations {
                repeat(2) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            finishedChildren++
                        }
                    }
                }
            }
        }

        mutation.cancelAndJoin()
        assertEquals(2, finishedChildren)
        var nextEntered = false
        mutex.mutateAnimations { nextEntered = true }
        assertTrue(nextEntered)
    }

    @Test
    fun animationFailureCancelsSiblingAndReleasesMutation() = runBlocking {
        val mutex = MutatorMutex()
        var siblingCancelled = false
        supervisorScope {
            val mutation = async {
                mutex.mutateAnimations {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            siblingCancelled = true
                        }
                    }
                    launch { error("animation failure") }
                }
            }
            val failure = runCatching { mutation.await() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("animation failure", failure?.message)
        }

        assertTrue(siblingCancelled)
        var nextEntered = false
        mutex.mutateAnimations { nextEntered = true }
        assertTrue(nextEntered)
    }
}
