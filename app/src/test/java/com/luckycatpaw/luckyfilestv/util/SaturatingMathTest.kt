package com.luckycatpaw.luckyfilestv.util

import kotlin.test.assertEquals
import org.junit.Test

class SaturatingMathTest {

    @Test
    fun `adds normally while there is room`() {
        assertEquals(5L, safeAdd(2L, 3L))
        assertEquals(0L, safeAdd(0L, 0L))
        assertEquals(Long.MAX_VALUE, safeAdd(Long.MAX_VALUE - 1L, 1L))
    }

    @Test
    fun `pins at the maximum instead of wrapping`() {
        // A wrapped total goes negative, which runs a progress bar backwards and lets a
        // free space check pass on a volume that has nothing left.
        assertEquals(Long.MAX_VALUE, safeAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, safeAdd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, safeAdd(Long.MAX_VALUE - 1L, 2L))
    }

    // Records the behaviour rather than endorsing it: the guard subtracts the first operand
    // from Long.MAX_VALUE, which overflows when that operand is negative, and the result
    // saturates instead of adding. Every caller sums file sizes, so this is a precondition
    // nobody violates today — but nothing in the signature says so.
    @Test
    fun `a negative operand saturates rather than subtracting`() {
        assertEquals(Long.MAX_VALUE, safeAdd(-5L, 3L))
    }
}
