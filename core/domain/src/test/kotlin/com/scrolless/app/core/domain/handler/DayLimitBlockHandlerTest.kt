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
package com.scrolless.app.core.domain.handler

import com.scrolless.app.core.blocking.handler.BlockOptionHandler
import com.scrolless.app.core.blocking.handler.DayLimitBlockHandler
import com.scrolless.app.core.blocking.time.TimeProvider
import com.scrolless.app.core.domain.BaseTest
import com.scrolless.app.core.model.BlockingResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DayLimitBlockHandlerTest : BaseTest() {

    companion object {
        private const val LIMIT_MILLIS = 30 * 60 * 1000L // 30 minutes
    }

    private val testDispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 1, 10)
    private var nowDateTime: LocalDateTime = today.atTime(12, 0)
    private val timeProvider = object : TimeProvider {
        private fun zone() = ZoneId.systemDefault()
        override fun currentTimeInMillis(): Long = nowDateTime.atZone(zone()).toInstant().toEpochMilli()
        override fun localDateNow(): LocalDate = nowDateTime.toLocalDate()
        override fun localDateTimeNow(): LocalDateTime = nowDateTime
    }
    private val dayLimitBlockHandler: BlockOptionHandler =
        DayLimitBlockHandler(LIMIT_MILLIS, timeProvider)

    @Test
    fun whenEnterContent_withinLimitNeverBlocks() = runTest(testDispatcher) {
        val didBlock = dayLimitBlockHandler.onEnterContent(0L)
        assert(!didBlock)
    }

    @Test
    fun whenEnterContent_exceedsLimitBlocks() = runTest(testDispatcher) {
        // Simulate entering content after exceeding the limit
        val didBlock = dayLimitBlockHandler.onEnterContent(LIMIT_MILLIS + 1)
        assert(didBlock)
    }

    @Test
    fun whenPeriodicCheck_withinLimitNeverBlocks() = runTest(testDispatcher) {
        val result = dayLimitBlockHandler.onPeriodicCheck(0L, 1000L)
        assert(result is BlockingResult.CheckLater)
        val nextCheck = result as BlockingResult.CheckLater
        assert(nextCheck.delayMillis == LIMIT_MILLIS - 1000L)
    }

    @Test
    fun whenPeriodicCheck_exceedsLimitBlocks() = runTest(testDispatcher) {
        val result = dayLimitBlockHandler.onPeriodicCheck(LIMIT_MILLIS + 1, 1000L)
        assert(result is BlockingResult.BlockNow)
    }

    @Test
    fun whenPeriodicCheck_sessionCrossingMidnightOnlyChargesTodayPart() = runTest(testDispatcher) {
        // Session started at 23:50 yesterday, now it is 00:10. Today already has 15 min saved.
        nowDateTime = today.atTime(0, 10)
        val elapsedSinceSessionStart = 20 * 60 * 1000L
        val result = dayLimitBlockHandler.onPeriodicCheck(15 * 60 * 1000L, elapsedSinceSessionStart)

        // Only 10 min of the session belong to today: 15 + 10 = 25 min < 30 min limit
        assert(result is BlockingResult.CheckLater)
        assert((result as BlockingResult.CheckLater).delayMillis == 5 * 60 * 1000L)
    }

    @Test
    fun whenPeriodicCheck_sessionStartedTodayIsNotClamped() = runTest(testDispatcher) {
        nowDateTime = today.atTime(0, 10)
        // Session started 5 minutes ago (00:05), fully today
        val result = dayLimitBlockHandler.onPeriodicCheck(15 * 60 * 1000L, 5 * 60 * 1000L)
        assert((result as BlockingResult.CheckLater).delayMillis == 10 * 60 * 1000L)
    }

    @Test
    fun whenExitContent_resetsState() = runTest(testDispatcher) {
        dayLimitBlockHandler.onExitContent(0L, 0L)
        // No state to verify directly, but ensure no exceptions occur
    }
}
