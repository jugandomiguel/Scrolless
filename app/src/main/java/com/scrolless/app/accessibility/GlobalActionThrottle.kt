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

import com.scrolless.app.core.model.ResolvedBlockableApp

/**
 * Stops a navigation action (such as Back) from being repeated for the same app while the previous one
 * is still taking effect. A second Back would leave the screen Scrolless returned to, often closing the app.
 *
 * Keyed by app rather than by viewing session: while Back animates, the same video can be detected again
 * as a new session, and it must not trigger another Back.
 */
internal class GlobalActionThrottle(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    private var lastApp: ResolvedBlockableApp? = null
    private var lastActionAtMillis = 0L

    /**
     * Returns `true` and records the action when [app] had no action within the window before [nowMillis].
     * A clock that moved backwards never holds an action back.
     */
    fun tryAcquire(app: ResolvedBlockableApp, nowMillis: Long): Boolean {
        val elapsedMillis = nowMillis - lastActionAtMillis
        if (app == lastApp && elapsedMillis in 0L until windowMillis) return false
        lastApp = app
        lastActionAtMillis = nowMillis
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_000L
    }
}
