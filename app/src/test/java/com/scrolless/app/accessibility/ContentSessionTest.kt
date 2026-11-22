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
import com.scrolless.app.ui.overlay.ContentCover
import com.scrolless.app.ui.overlay.ContentCoverTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSessionTest {
    private val app = ResolvedBlockableApp(BlockableApp.TIKTOK, "com.zhiliaoapp.musically")
    private val cover = ContentCover(ContentCoverTarget.Screen(ContentBounds(0, 0, 1080, 2160)), 1, 2)
    private val content = DetectedBlockedContent(app, false, cover)

    @Test
    fun `covering finishes viewing only once`() {
        val session = ContentSession(content, 1_000)

        assertEquals(5_000L, session.cover(6_000)!!.durationMillis)
        assertTrue(session.isCovered)
        assertNull(session.cover(10_000))
        assertNull(session.finish(60_000))
    }

    @Test
    fun `viewing after a covered interval starts at the new time`() {
        val covered = ContentSession(content, 1_000)
        assertEquals(1_000L, covered.cover(2_000)!!.durationMillis)

        val resumed = ContentSession(content, 20_000)
        assertEquals(20_000L, resumed.startedAtMillis)
        assertEquals(5_000L, resumed.finish(25_000)!!.durationMillis)
    }

    @Test
    fun `suppressed content still records viewing time`() {
        val session = ContentSession(content.copy(blockingSuppressed = true), 1_000)

        assertTrue(session.blockingSuppressed)
        assertEquals(5_000L, session.finish(6_000)!!.durationMillis)
    }

    @Test
    fun `layout updates keep the original viewing start`() {
        val session = ContentSession(content, 1_000)
        session.content = content.copy(cover = cover.copy(target = ContentCoverTarget.Screen(ContentBounds(0, 0, 2160, 1000))))

        assertFalse(session.isCovered)
        assertEquals(1_000L, session.cover(2_000)!!.durationMillis)
    }

    @Test
    fun `clock moving backwards cannot record negative usage`() {
        val session = ContentSession(content, 10_000)

        assertEquals(0L, session.finish(1_000)!!.durationMillis)
    }

    @Test
    fun `a checkpoint waits for the interval`() {
        val session = ContentSession(content, 1_000)

        assertNull(session.checkpoint(15_999, 15_000))
        assertEquals(1_000L, session.savedUntilMillis)
        assertEquals(1L, session.millisUntilCheckpoint(15_999, 15_000))
    }

    @Test
    fun `checkpoints hand out each part of the visit only once`() {
        val session = ContentSession(content, 1_000)

        val first = session.checkpoint(16_000, 15_000)!!
        assertEquals(1_000L, first.startedAtMillis)
        assertEquals(15_000L, first.durationMillis)
        assertFalse(first.continuesSavedViewing)
        assertEquals(0L, session.unsavedMillis(16_000))
        assertEquals(15_000L, session.millisUntilCheckpoint(16_000, 15_000))

        // Asking again at the same time returns nothing, so the same time cannot be saved twice.
        assertNull(session.checkpoint(16_000, 15_000))

        val second = session.checkpoint(32_000, 15_000)!!
        assertEquals(16_000L, second.startedAtMillis)
        assertEquals(16_000L, second.durationMillis)
        assertTrue(second.continuesSavedViewing)

        val last = session.finish(35_000)!!
        assertEquals(32_000L, last.startedAtMillis)
        assertEquals(3_000L, last.durationMillis)
        assertEquals(34_000L, first.durationMillis + second.durationMillis + last.durationMillis)
        assertNull(session.checkpoint(60_000, 15_000))
    }

    @Test
    fun `covering after a checkpoint returns only the unsaved time`() {
        val session = ContentSession(content, 1_000)
        session.checkpoint(16_000, 15_000)!!

        val covered = session.cover(18_000)!!
        assertEquals(16_000L, covered.startedAtMillis)
        assertEquals(2_000L, covered.durationMillis)
        assertNull(session.checkpoint(40_000, 15_000))
    }

    @Test
    fun `a short visit is not tracked`() {
        val finished = ContentSession(content, 1_000).finish(1_999)!!

        assertFalse(finished.isTracked)
    }

    @Test
    fun `the short end of a visit saved in parts is still tracked`() {
        val session = ContentSession(content, 1_000)
        assertTrue(session.checkpoint(16_000, 15_000)!!.isTracked)

        val tail = session.finish(16_400)!!
        assertEquals(400L, tail.durationMillis)
        assertTrue(tail.isTracked)
    }

    @Test
    fun `an empty end of a visit saved in parts adds nothing`() {
        val session = ContentSession(content, 1_000)
        session.checkpoint(16_000, 15_000)!!

        assertFalse(session.finish(16_000)!!.isTracked)
    }

    @Test
    fun `clock moving backwards after a checkpoint records nothing more`() {
        val session = ContentSession(content, 1_000)
        session.checkpoint(16_000, 15_000)!!

        assertNull(session.checkpoint(5_000, 15_000))
        assertEquals(0L, session.unsavedMillis(5_000))
        assertEquals(0L, session.finish(5_000)!!.durationMillis)
    }
}
