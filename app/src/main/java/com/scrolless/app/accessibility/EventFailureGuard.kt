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

/**
 * Counts accessibility events that failed in a row, so one broken screen cannot make every event fail.
 *
 * After [maxConsecutiveFailures] failures in a row, ordinary events are skipped until the next window change,
 * which usually means a different screen that may work again. Any event that succeeds resets the count.
 */
internal class EventFailureGuard(private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES) {
    /** Failures since the last event that was processed without error. */
    var consecutiveFailures: Int = 0
        private set

    /** Whether ordinary events are being skipped until the next window change. */
    val isPaused: Boolean get() = consecutiveFailures >= maxConsecutiveFailures

    /** Whether to process the next event. A window change always gets a new attempt. */
    fun shouldProcess(isWindowChange: Boolean): Boolean = !isPaused || isWindowChange

    fun onSuccess() {
        consecutiveFailures = 0
    }

    /** Records a failure. Returns `true` only for the failure that starts skipping events. */
    fun onFailure(): Boolean {
        consecutiveFailures++
        return consecutiveFailures == maxConsecutiveFailures
    }

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 5
    }
}
