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

import com.scrolless.app.core.model.ContentBlockAction
import com.scrolless.app.core.model.ResolvedBlockableApp
import com.scrolless.app.ui.overlay.ContentCover

/** Information about blocked content detected on screen. */
internal data class DetectedBlockedContent(
    val app: ResolvedBlockableApp,
    val blockingSuppressed: Boolean,
    val cover: ContentCover? = null,
) {
    /** The action required for this screen: cover its video when possible, otherwise use the app's normal action. */
    val blockAction: ContentBlockAction
        get() = if (cover != null) ContentBlockAction.CoverVideoRegion else app.getBlockAction()

    /** Layout changes keep the session; changing the app or blocking action starts a new one. */
    fun canContinueWith(next: DetectedBlockedContent): Boolean = app == next.app && blockAction == next.blockAction
}

/** Minimum viewing duration required to record a visit. Sub-second visits (like immediate blocks) are ignored. */
internal const val MIN_TRACKED_DURATION_MILLIS = 1_000L

/** Tracks a viewing session while the user is inside blocked content. */
internal class ContentSession(var content: DetectedBlockedContent, val startedAtMillis: Long) {
    val app: ResolvedBlockableApp get() = content.app

    /** Whether blocking is temporarily skipped for this screen while viewing time is still counted. */
    val blockingSuppressed: Boolean get() = content.blockingSuppressed

    /** Whether a cover has stopped this session's viewing time but screen detection must continue. */
    var isCovered: Boolean = false
        private set

    /** Prevents more than one usage record from being produced for this visit. */
    private var viewingFinished: Boolean = false

    /**
     * End of the viewing time already handed out for saving by [checkpoint].
     * Everything before it is saved, so later pieces start here and no time is counted twice.
     */
    var savedUntilMillis: Long = startedAtMillis
        private set

    /** Viewing time since the last saved piece, which usage records do not include yet. */
    fun unsavedMillis(nowMillis: Long): Long = (nowMillis - savedUntilMillis).coerceAtLeast(0L)

    /** How long until [checkpoint] with [intervalMillis] would return a piece. */
    fun millisUntilCheckpoint(nowMillis: Long, intervalMillis: Long): Long = (intervalMillis - unsavedMillis(nowMillis)).coerceAtLeast(0L)

    /**
     * Returns the viewing time since the last saved piece once it reaches [intervalMillis], so a long visit
     * is saved in parts and survives the service being killed. Returns `null` when nothing is due yet,
     * or when viewing has already finished.
     */
    fun checkpoint(nowMillis: Long, intervalMillis: Long): FinishedViewing? {
        if (viewingFinished || unsavedMillis(nowMillis) < intervalMillis) return null
        return nextPiece(nowMillis)
    }

    /**
     * Marks the video as covered and returns the viewing period that just ended.
     * Later calls return `null` so the same viewing time cannot be saved twice.
     */
    fun cover(nowMillis: Long): FinishedViewing? {
        if (viewingFinished) return null
        isCovered = true
        return finishOnce(nowMillis)
    }

    /**
     * Ends this visit and returns its viewing period.
     * Later calls return `null` because a visit must be saved only once.
     */
    fun finish(nowMillis: Long): FinishedViewing? {
        if (viewingFinished) return null
        return finishOnce(nowMillis)
    }

    private fun finishOnce(nowMillis: Long): FinishedViewing {
        viewingFinished = true
        return nextPiece(nowMillis)
    }

    /** The unsaved time up to [nowMillis]. Moves [savedUntilMillis] forward so it is handed out only once. */
    private fun nextPiece(nowMillis: Long): FinishedViewing {
        val piece = FinishedViewing(
            app = app,
            startedAtMillis = savedUntilMillis,
            endedAtMillis = maxOf(savedUntilMillis, nowMillis),
            continuesSavedViewing = savedUntilMillis > startedAtMillis,
        )
        savedUntilMillis = piece.endedAtMillis
        return piece
    }
}

/**
 * Viewing period ready to be saved to usage history: a whole visit, or one piece of a longer visit.
 *
 * @property continuesSavedViewing True when an earlier piece of the same visit was already saved.
 */
internal data class FinishedViewing(
    val app: ResolvedBlockableApp,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val continuesSavedViewing: Boolean = false,
) {
    val durationMillis: Long get() = endedAtMillis - startedAtMillis

    /**
     * Whether this period adds viewing time. A short visit on its own does not count, but the short end of a
     * longer visit does, so that time is not lost once the visit has been saved in parts.
     */
    val isTracked: Boolean
        get() = durationMillis >= MIN_TRACKED_DURATION_MILLIS || (continuesSavedViewing && durationMillis > 0L)
}
