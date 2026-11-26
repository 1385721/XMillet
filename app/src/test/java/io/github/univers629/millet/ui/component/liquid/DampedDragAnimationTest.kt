package io.github.univers629.millet.ui.component.liquid

import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import io.github.univers629.millet.ui.component.computeFloatingTabIndexAt
import io.github.univers629.millet.ui.component.computeFloatingTabWidthPx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun recreatedAnimationProducesDistinctPointerInputModifier() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val anim5Tabs = DampedDragAnimation(
                animationScope = scope,
                initialValue = 4f,
                valueRange = 0f..4f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.4f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
            val anim4Tabs = DampedDragAnimation(
                animationScope = scope,
                initialValue = 3f,
                valueRange = 0f..3f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.4f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
            assertEquals(anim5Tabs.modifier, anim5Tabs.modifier)
            assertNotEquals(anim5Tabs.modifier, anim4Tabs.modifier)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recreatedInteractiveHighlightProducesDistinctGestureModifier() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val first = InteractiveHighlight(animationScope = scope)
            val second = InteractiveHighlight(animationScope = scope)
            assertEquals(first.gestureModifier, first.gestureModifier)
            assertNotEquals(first.gestureModifier, second.gestureModifier)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preLayoutCapturedClosureReadsUpdatedTabWidthAfterMeasure() {
        assertEquals(60f, computeFloatingTabWidthPx(308f, 8f, 5), 0.001f)
        assertEquals(75f, computeFloatingTabWidthPx(308f, 8f, 4), 0.001f)
        assertEquals(0f, computeFloatingTabWidthPx(0f, 8f, 4), 0.001f)
        assertEquals(0f, computeFloatingTabWidthPx(308f, 8f, 0), 0.001f)

        var totalWidthPx by mutableFloatStateOf(0f)
        val tabWidthPx by derivedStateOf {
            computeFloatingTabWidthPx(totalWidthPx, 8f, 5)
        }

        // 在首次测量前（totalWidthPx == 0f）创建并捕获闭包，模拟 remember(tabsCount) 首帧行为
        val readTabWidth: () -> Float = { tabWidthPx }
        val indexAtLtr: (Float) -> Int = { x ->
            computeFloatingTabIndexAt(
                positionX = x,
                totalWidthPx = totalWidthPx,
                singleSidePaddingPx = 4f,
                tabsCount = 5,
                isLtr = true,
                fallbackIndex = 0,
            )
        }
        val indexAtRtl: (Float) -> Int = { x ->
            computeFloatingTabIndexAt(
                positionX = x,
                totalWidthPx = totalWidthPx,
                singleSidePaddingPx = 4f,
                tabsCount = 5,
                isLtr = false,
                fallbackIndex = 0,
            )
        }

        assertEquals(0f, readTabWidth(), 0.001f)
        assertEquals(0, indexAtLtr(350f))

        // 模拟 onGloballyPositioned 回填容器宽度后，不重建闭包也必须读到最新 tabWidthPx 与命中下标
        totalWidthPx = 508f
        assertEquals(100f, readTabWidth(), 0.001f)
        assertEquals(0, indexAtLtr(50f))
        assertEquals(1, indexAtLtr(150f))
        assertEquals(2, indexAtLtr(250f))
        assertEquals(4, indexAtLtr(450f))
        assertEquals(4, indexAtRtl(50f))
    }
}
