/*
 * Copyright (C) 2026 Scrolless
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.scrolless.app.accessibility

import com.scrolless.app.core.model.BlockableApp
import com.scrolless.app.core.model.ResolvedBlockableApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionThrottleTest {
    private val instagram = ResolvedBlockableApp(BlockableApp.REELS, "com.instagram.android")
    private val youtube = ResolvedBlockableApp(BlockableApp.SHORTS, "com.google.android.youtube")
    private val throttle = GlobalActionThrottle(windowMillis = 1_000L)

    @Test
    fun `the first action always runs`() {
        assertTrue(throttle.tryAcquire(instagram, 5_000L))
    }

    @Test
    fun `a repeated action for the same app waits for the window`() {
        assertTrue(throttle.tryAcquire(instagram, 5_000L))

        assertFalse(throttle.tryAcquire(instagram, 5_000L))
        assertFalse(throttle.tryAcquire(instagram, 5_999L))
        assertTrue(throttle.tryAcquire(instagram, 6_000L))
    }

    @Test
    fun `a held back attempt does not extend the window`() {
        assertTrue(throttle.tryAcquire(instagram, 5_000L))
        assertFalse(throttle.tryAcquire(instagram, 5_900L))

        assertTrue(throttle.tryAcquire(instagram, 6_000L))
    }

    @Test
    fun `another app is not held back`() {
        assertTrue(throttle.tryAcquire(instagram, 5_000L))

        assertTrue(throttle.tryAcquire(youtube, 5_100L))
    }

    @Test
    fun `a clock moving backwards does not hold an action back`() {
        assertTrue(throttle.tryAcquire(instagram, 5_000L))

        assertTrue(throttle.tryAcquire(instagram, 1_000L))
    }
}
