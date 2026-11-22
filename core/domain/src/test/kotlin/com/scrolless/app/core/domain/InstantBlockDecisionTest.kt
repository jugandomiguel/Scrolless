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
package com.scrolless.app.core.domain

import com.scrolless.app.core.blocking.InstantBlockDecision
import com.scrolless.app.core.blocking.time.TimeProvider
import com.scrolless.app.core.model.BlockOption
import com.scrolless.app.core.model.BlockingConfig
import com.scrolless.app.core.model.BlockingSettings
import com.scrolless.app.core.model.IntervalUsage
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantBlockDecisionTest {

    private var nowMillis = 0L
    private var today: LocalDate = LocalDate.of(2026, 1, 10)
    private val timeProvider = object : TimeProvider {
        override fun currentTimeInMillis(): Long = nowMillis
        override fun localDateNow(): LocalDate = today
        override fun localDateTimeNow(): LocalDateTime = today.atStartOfDay()
    }
    private val decision = InstantBlockDecision(timeProvider)

    @Test
    fun `nothing is known before the configuration loads`() {
        assertNull(decision.shouldBlockNow())
    }

    @Test
    fun `block all and nothing selected need no usage`() {
        decision.onConfigChanged(BlockingConfig(activeOption = BlockOption.BlockAll))
        assertEquals(true, decision.shouldBlockNow())

        decision.onConfigChanged(BlockingConfig(activeOption = BlockOption.NothingSelected))
        assertEquals(false, decision.shouldBlockNow())
    }

    @Test
    fun `modes selected before they were configured block nothing`() {
        decision.onConfigChanged(BlockingConfig(activeOption = BlockOption.DailyLimit))
        assertEquals(false, decision.shouldBlockNow())

        decision.onConfigChanged(
            BlockingConfig(activeOption = BlockOption.IntervalTimer, settings = BlockingSettings(intervalAllowanceMillis = 1_000L)),
        )
        assertEquals(false, decision.shouldBlockNow())
    }

    @Test
    fun `daily limit is unknown until usage is read`() = runTest {
        decision.onConfigChanged(dailyLimit(30_000L))
        assertNull(decision.shouldBlockNow())

        assertEquals(10_000L, decision.refreshDailyUsage { 10_000L })
        assertEquals(false, decision.shouldBlockNow())

        decision.refreshDailyUsage { 30_000L }
        assertEquals(true, decision.shouldBlockNow())
    }

    @Test
    fun `a new limit applies to the usage already read`() = runTest {
        decision.onConfigChanged(dailyLimit(30_000L))
        decision.refreshDailyUsage { 40_000L }
        assertEquals(true, decision.shouldBlockNow())

        decision.onConfigChanged(dailyLimit(60_000L))
        assertEquals(false, decision.shouldBlockNow())
    }

    @Test
    fun `usage read on an earlier day is not used after midnight`() = runTest {
        decision.onConfigChanged(dailyLimit(30_000L))
        decision.refreshDailyUsage { 40_000L }

        today = today.plusDays(1)

        assertNull(decision.shouldBlockNow())
    }

    @Test
    fun `a read that finishes after midnight keeps the day it started on`() = runTest {
        decision.onConfigChanged(dailyLimit(30_000L))
        decision.refreshDailyUsage {
            today = today.plusDays(1)
            40_000L
        }

        assertNull(decision.shouldBlockNow())
    }

    @Test
    fun `interval timer blocks only while the used interval is running`() {
        val settings = BlockingSettings(intervalAllowanceMillis = 5_000L, intervalLengthMillis = 60_000L)
        decision.onConfigChanged(
            BlockingConfig(
                activeOption = BlockOption.IntervalTimer,
                settings = settings,
                intervalUsage = IntervalUsage(startMillis = 100_000L, usageMillis = 5_000L),
            ),
        )

        nowMillis = 120_000L
        assertTrue(decision.shouldBlockNow()!!)

        // The interval ended, so a new allowance starts with the next viewing.
        nowMillis = 160_000L
        assertFalse(decision.shouldBlockNow()!!)
    }

    @Test
    fun `interval timer allows while usage is under the allowance`() {
        decision.onConfigChanged(
            BlockingConfig(
                activeOption = BlockOption.IntervalTimer,
                settings = BlockingSettings(intervalAllowanceMillis = 5_000L, intervalLengthMillis = 60_000L),
                intervalUsage = IntervalUsage(startMillis = 100_000L, usageMillis = 4_999L),
            ),
        )
        nowMillis = 110_000L

        assertEquals(false, decision.shouldBlockNow())
    }

    private fun dailyLimit(limitMillis: Long) =
        BlockingConfig(activeOption = BlockOption.DailyLimit, settings = BlockingSettings(dailyLimitMillis = limitMillis))
}
