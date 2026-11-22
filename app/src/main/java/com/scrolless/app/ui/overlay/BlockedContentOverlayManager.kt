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
package com.scrolless.app.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import com.scrolless.app.designsystem.theme.timerOverlayBackgroundColor
import javax.inject.Inject
import timber.log.Timber

/**
 * Manages the overlay covering the blocked video or screen area.
 * On Android 14+, covers attach directly to the app's window so they move together.
 */
class BlockedContentOverlayManager @Inject constructor() {
    private lateinit var service: AccessibilityService
    private lateinit var windowManager: WindowManager
    private var view: View? = null
    private var shownCover: ContentCover? = null
    private var windowOverlay: WindowAttachedContentOverlay? = null

    fun attachServiceContext(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
    }

    /**
     * Shows or updates the cover overlay.
     * Returns true if it was successfully displayed.
     */
    internal fun show(cover: ContentCover, refreshAttachment: Boolean = false): Boolean {
        val target = cover.target
        if (!target.bounds.isVisible) return false
        // Different text or a different rendering mode needs a fresh view, not a resize.
        shownCover?.let { if (!cover.canReuseView(it)) hide() }
        // Accessibility sends many identical events. Leave an unchanged cover alone.
        if (!target.needsUpdate(shownCover?.target, refreshAttachment)) return true
        when (target) {
            is ContentCoverTarget.Screen -> if (!showScreenCover(cover)) {
                // Clear the half-shown state so the next cover starts with a new view.
                hide()
                return false
            }

            is ContentCoverTarget.Window -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
                val overlay = windowOverlay ?: WindowAttachedContentOverlay(service) { createCoverView(it, cover) }.also {
                    windowOverlay = it
                }
                if (!overlay.show(target, shownCover?.target as? ContentCoverTarget.Window, refreshAttachment)) {
                    hide()
                    return false
                }
            }
        }
        shownCover = cover
        return true
    }

    /** Keep attached covers for the app-switching animation; screen-off always removes them. */
    fun onAppExit(screenInteractive: Boolean) {
        if (shownCover?.target?.keepOnAppExit(screenInteractive) != true) hide()
    }

    /**
     * Draws the legacy cover using screen coordinates on Android versions without window attachment.
     * Returns false if the window manager refused the cover, for example because the service is disconnecting.
     */
    @SuppressLint("RtlHardcoded")
    private fun showScreenCover(cover: ContentCover): Boolean {
        val bounds = cover.target.bounds

        // Keep app focus and accept touches only inside this rectangle. Native tabs stay usable.
        val params = WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            // Accessibility gives physical left/top coordinates, even in right-to-left languages.
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
            // Do not let system-bar or cutout padding shift the rectangle Android reported.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val currentView = view
        return try {
            if (currentView == null) {
                val newView = createCoverView(service, cover)
                // Remember the view before adding it, so hide() also removes one that was added partly.
                view = newView
                windowManager.addView(newView, params)
            } else {
                windowManager.updateViewLayout(currentView, params)
            }
            true
        } catch (error: RuntimeException) {
            // BadTokenException, InvalidDisplayException or IllegalArgumentException (view no longer attached).
            Timber.e(error, "Unable to show screen content cover")
            false
        }
    }

    /** Removes every cover and clears cached state so the next one is created safely. */
    fun hide() {
        // Remove both possible overlay types, including a cover left attached after leaving the app.
        val oldView = view
        // Clear the reference first, so a failed removal cannot leave a stale view to update later.
        view = null
        try {
            oldView?.let(windowManager::removeViewImmediate)
        } catch (error: RuntimeException) {
            // IllegalArgumentException when the view was never attached or is already gone.
            Timber.w(error, "Unable to remove screen content cover")
        }
        val oldWindowOverlay = windowOverlay
        windowOverlay = null
        shownCover = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                oldWindowOverlay?.hide()
            } catch (error: RuntimeException) {
                Timber.w(error, "Unable to remove window content cover")
            }
        }
    }

    /** Builds the opaque, touch-consuming message shown in place of a blocked video. */
    private fun createCoverView(context: Context, cover: ContentCover): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val padding = (32 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        // The timer color may be translucent. The blocker must be fully opaque to hide the video.
        setBackgroundColor(timerOverlayBackgroundColor.toArgb() or 0xFF000000.toInt())
        // Consume taps and swipes rather than allowing them to reach the video beneath us.
        isClickable = true
        // A brief cover shown while Back navigation runs has no text; it only hides the video.
        cover.titleRes?.let { titleRes ->
            addView(
                TextView(context).apply {
                    setText(titleRes)
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    ViewCompat.setAccessibilityHeading(this, true)
                },
            )
        }
        cover.descriptionRes?.let { descriptionRes ->
            addView(
                TextView(context).apply {
                    setText(descriptionRes)
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, padding / 2, 0, 0)
                },
            )
        }
    }
}
