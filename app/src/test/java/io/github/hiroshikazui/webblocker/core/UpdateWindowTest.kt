package io.github.hiroshikazui.webblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateWindowTest {
    private val now = 1_700_000_000_000L

    @Test
    fun opensOnlyOutsideBlockHours() {
        assertEquals(now + UpdateWindow.DURATION_MS, UpdateWindow.open(now, inBlockHours = false))
        assertNull(UpdateWindow.open(now, inBlockHours = true))
    }

    @Test
    fun remainingCountsDownAndExpires() {
        val until = now + UpdateWindow.DURATION_MS
        assertEquals(UpdateWindow.DURATION_MS, UpdateWindow.remainingMs(now, until, false))
        assertEquals(60_000L, UpdateWindow.remainingMs(until - 60_000L, until, false))
        assertEquals(0L, UpdateWindow.remainingMs(until + 1, until, false))
        assertEquals(0L, UpdateWindow.remainingMs(now, 0L, false))
    }

    @Test
    fun closesWhenBlockHoursStart() {
        assertEquals(0L, UpdateWindow.remainingMs(now, now + UpdateWindow.DURATION_MS, inBlockHours = true))
    }

    @Test
    fun corruptedFarFutureValueIsCapped() {
        assertEquals(UpdateWindow.DURATION_MS, UpdateWindow.remainingMs(now, Long.MAX_VALUE, false))
    }
}
