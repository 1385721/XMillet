package io.github.univers629.millet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedHookParamTest {

    @Suppress("unused")
    private class DummyTargets {
        fun retVoid() {}
        fun retBool(): Boolean = true
        fun retBoxedBool(): Boolean? = true
        fun retList(): List<Int> = listOf(1, 2)
        fun retSet(): Set<Int> = setOf(1)
        fun retIntArray(): IntArray = intArrayOf(1)
        fun retInt(): Int = 42
    }

    private fun paramFor(methodName: String): UnifiedHookParamImpl {
        val m = DummyTargets::class.java.getDeclaredMethod(methodName)
        return UnifiedHookParamImpl(
            thisObject = DummyTargets(),
            args = emptyArray(),
            method = m,
        )
    }

    @Test
    fun `blockSafely handles void boolean collections and arrays`() {
        val voidParam = paramFor("retVoid")
        assertTrue(voidParam.blockSafely())
        assertTrue(voidParam.isSkipped)
        assertNull(voidParam.result)

        val boolParam = paramFor("retBool")
        assertTrue(boolParam.blockSafely())
        assertTrue(boolParam.isSkipped)
        assertEquals(false, boolParam.result)

        val boxedBoolParam = paramFor("retBoxedBool")
        assertTrue(boxedBoolParam.blockSafely())
        assertTrue(boxedBoolParam.isSkipped)
        assertEquals(false, boxedBoolParam.result)

        val listParam = paramFor("retList")
        assertTrue(listParam.blockSafely())
        assertTrue(listParam.isSkipped)
        assertEquals(emptyList<Any>(), listParam.result)

        val setParam = paramFor("retSet")
        assertTrue(setParam.blockSafely())
        assertTrue(setParam.isSkipped)
        assertEquals(emptySet<Any>(), setParam.result)

        val arrayParam = paramFor("retIntArray")
        assertTrue(arrayParam.blockSafely())
        assertTrue(arrayParam.isSkipped)
        assertTrue(arrayParam.result is IntArray)
        assertEquals(0, (arrayParam.result as IntArray).size)
    }

    @Test
    fun `blockSafely refuses to shortcut unknown return types`() {
        val intParam = paramFor("retInt")
        assertFalse(intParam.blockSafely())
        assertFalse(intParam.isSkipped)
        assertNull(intParam.result)

        val nullMethodParam = UnifiedHookParamImpl(thisObject = null, args = emptyArray(), method = null)
        assertFalse(nullMethodParam.blockSafely())
        assertFalse(nullMethodParam.isSkipped)
    }
}
