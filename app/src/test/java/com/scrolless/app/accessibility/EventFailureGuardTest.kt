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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFailureGuardTest {
    private val guard = EventFailureGuard(maxConsecutiveFailures = 3)

    @Test
    fun `events are processed while failures stay below the limit`() {
        assertFalse(guard.onFailure())
        assertFalse(guard.onFailure())

        assertTrue(guard.shouldProcess(isWindowChange = false))
        assertFalse(guard.isPaused)
    }

    @Test
    fun `reaching the limit pauses ordinary events and reports it once`() {
        guard.onFailure()
        guard.onFailure()
        assertTrue(guard.onFailure())

        assertTrue(guard.isPaused)
        assertFalse(guard.shouldProcess(isWindowChange = false))
        // A window change that fails again keeps the pause without reporting it again.
        assertTrue(guard.shouldProcess(isWindowChange = true))
        assertFalse(guard.onFailure())
        assertEquals(4, guard.consecutiveFailures)
    }

    @Test
    fun `a successful event resets the count`() {
        guard.onFailure()
        guard.onFailure()
        guard.onSuccess()
        guard.onFailure()
        guard.onFailure()

        assertFalse(guard.isPaused)
        assertEquals(2, guard.consecutiveFailures)
    }

    @Test
    fun `a window change that succeeds resumes ordinary events`() {
        repeat(3) { guard.onFailure() }

        assertTrue(guard.shouldProcess(isWindowChange = true))
        guard.onSuccess()

        assertTrue(guard.shouldProcess(isWindowChange = false))
        assertEquals(0, guard.consecutiveFailures)
    }
}
