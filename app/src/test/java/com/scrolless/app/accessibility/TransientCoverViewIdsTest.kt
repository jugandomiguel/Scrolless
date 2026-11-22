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
import com.scrolless.app.core.model.DetectionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientCoverViewIdsTest {

    private fun BlockableApp.coverIds(includeStories: Boolean = false) = getDetectionMethod(includeStories).transientCoverViewIds()

    @Test
    fun `back blocked apps cover the viewer their rule detects`() {
        assertEquals(setOf("clips_viewer_view_pager"), BlockableApp.REELS.coverIds())
        assertEquals(setOf("reel_player_page_container"), BlockableApp.SHORTS.coverIds())
        assertEquals(setOf("video_view"), BlockableApp.FACEBOOK_LITE.coverIds())
        assertEquals(setOf("spotlight_container"), BlockableApp.SNAPCHAT.coverIds())
    }

    @Test
    fun `layout rules cover their innermost named view`() {
        assertEquals(setOf("video_view"), BlockableApp.INSTAGRAM_LITE.coverIds())
    }

    @Test
    fun `stories add their own viewer`() {
        assertEquals(setOf("clips_viewer_view_pager", "reel_viewer_root"), BlockableApp.REELS.coverIds(includeStories = true))
        assertEquals(setOf("spotlight_container", "opera_viewer"), BlockableApp.SNAPCHAT.coverIds(includeStories = true))
    }

    @Test
    fun `facebook has no view to measure`() {
        assertTrue(BlockableApp.FACEBOOK.coverIds().isEmpty())
        assertTrue(BlockableApp.FACEBOOK.coverIds(includeStories = true).isEmpty())
    }

    @Test
    fun `label and activity rules name no view`() {
        assertTrue(DetectionMethod.ContentDescriptions(setOf("Reels")).transientCoverViewIds().isEmpty())
        assertTrue(DetectionMethod.ContentDescriptionPrefix(setOf("Reels,")).transientCoverViewIds().isEmpty())
        assertTrue(DetectionMethod.ActivityName("StoryViewerActivity").transientCoverViewIds().isEmpty())
    }
}
