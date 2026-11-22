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

import com.scrolless.app.core.blocking.time.TimeProvider
import com.scrolless.app.core.data.repository.SessionTrackerImpl
import com.scrolless.app.core.model.BlockableApp
import com.scrolless.app.core.model.ResolvedBlockableApp
import com.scrolless.app.core.model.SessionSegment
import com.scrolless.app.core.model.usage.DailyUsageTotal
import com.scrolless.app.core.repository.SessionSegmentStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Saving a visit in checkpoint pieces must add up to one usage segment with the visit's full duration. */
class CheckpointUsageTrackingTest {
    private val app = ResolvedBlockableApp(BlockableApp.REELS, "com.instagram.android")
    private val content = DetectedBlockedContent(app, blockingSuppressed = false)
    private val clock = FakeClock(nowMillis = Instant.parse("2026-11-23T12:00:00Z").toEpochMilli())
    private val store = FakeSegmentStore()
    private val tracker = SessionTrackerImpl(clock, store)

    @Test
    fun `pieces of one visit extend a single segment`() = runBlocking {
        watch(seconds = 40, extraMillis = 700)

        assertEquals(1, store.segments.size)
        assertEquals(40_700L, store.segments.single().durationMillis)
        assertEquals(40_700L, tracker.getDailyUsage())
    }

    @Test
    fun `a sub-second end after a checkpoint is not lost`() = runBlocking {
        // Pieces end at 15 s and 30 s, leaving 400 ms that would be dropped on its own.
        watch(seconds = 30, extraMillis = 400)

        assertEquals(1, store.segments.size)
        assertEquals(30_400L, store.segments.single().durationMillis)
    }

    @Test
    fun `a sub-second visit is still ignored`() = runBlocking {
        watch(seconds = 0, extraMillis = 900)

        assertEquals(0, store.segments.size)
    }

    /** Watches for [seconds] with a check every second, then leaves after [extraMillis] more. */
    private suspend fun watch(seconds: Int, extraMillis: Long) {
        tracker.onAppOpen(app.app)
        val session = ContentSession(content, clock.nowMillis)
        repeat(seconds) {
            clock.nowMillis += 1_000
            session.checkpoint(clock.nowMillis, 15_000)?.let(::save)
        }
        clock.nowMillis += extraMillis
        save(session.finish(clock.nowMillis)!!)
    }

    /** Same rule as the service: only tracked periods add usage. */
    private fun save(finished: FinishedViewing) = runBlocking {
        if (finished.isTracked) tracker.addToDailyUsage(finished.durationMillis, finished.app.app)
    }

    private class FakeClock(var nowMillis: Long) : TimeProvider {
        override fun currentTimeInMillis(): Long = nowMillis
        override fun localDateNow(): LocalDate = localDateTimeNow().toLocalDate()
        override fun localDateTimeNow(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
    }

    private class FakeSegmentStore : SessionSegmentStore {
        val segments = mutableListOf<SessionSegment>()

        override suspend fun addSessionSegment(sessionSegment: SessionSegment): Long {
            segments += sessionSegment
            return segments.size.toLong()
        }

        override suspend fun updateSessionSegmentDuration(lastSessionId: Long, sessionTime: Long) {
            val index = (lastSessionId - 1).toInt()
            segments[index] = segments[index].copy(durationMillis = sessionTime)
        }

        override suspend fun getTodayTotalDurationSnapshot(): Long = segments.sumOf { it.durationMillis }

        override fun observeTotalDuration(date: LocalDate): Flow<Long> = flowOf(getTotal())
        override fun getListSessionSegments(startDate: LocalDate, endDateInclusive: LocalDate): Flow<List<SessionSegment>> =
            flowOf(segments)
        override fun getDailyUsageTotals(startDate: LocalDate, endDateInclusive: LocalDate): Flow<List<DailyUsageTotal>> =
            flowOf(emptyList())
        override suspend fun replaceSessionSegmentsForDate(date: LocalDate, sessionSegments: List<SessionSegment>) = Unit

        private fun getTotal(): Long = segments.sumOf { it.durationMillis }
    }
}
