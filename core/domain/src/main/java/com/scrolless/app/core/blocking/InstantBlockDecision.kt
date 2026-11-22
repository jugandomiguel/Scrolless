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
package com.scrolless.app.core.blocking

import com.scrolless.app.core.blocking.time.TimeProvider
import com.scrolless.app.core.model.BlockOption
import com.scrolless.app.core.model.BlockingConfig
import java.time.LocalDate
import javax.inject.Inject

/**
 * Keeps the latest blocking configuration and daily usage in memory, so content can be blocked on the
 * same accessibility event that detected it instead of after a database read.
 *
 * Answers follow the handlers built by [BlockingManagerImpl] for content the user is just entering.
 * This is only a shortcut for blocking: [BlockingManager.onEnterBlockedContent] stays the authority,
 * and callers must still ask it whenever this returns `false` or `null`.
 */
class InstantBlockDecision @Inject constructor(private val timeProvider: TimeProvider) {

    @Volatile
    private var config: BlockingConfig? = null

    @Volatile
    private var dailyUsage: DailyUsage? = null

    /** Stores the latest saved configuration, including the interval usage it carries. */
    fun onConfigChanged(config: BlockingConfig) {
        this.config = config
    }

    /**
     * Reads today's usage with [readDailyUsage], remembers it, and returns it.
     * The day is taken before reading, so a read that finishes after midnight is not used for the new day.
     */
    suspend fun refreshDailyUsage(readDailyUsage: suspend () -> Long): Long {
        val date = timeProvider.localDateNow()
        val usageMillis = readDailyUsage()
        dailyUsage = DailyUsage(date, usageMillis)
        return usageMillis
    }

    /**
     * Whether content opened right now must be blocked.
     *
     * @return `true` or `false` when the loaded state answers it, or `null` when the configuration or
     * today's usage is not known yet.
     */
    fun shouldBlockNow(): Boolean? {
        val config = config ?: return null
        val settings = config.settings
        return when (config.activeOption) {
            BlockOption.NothingSelected -> false

            BlockOption.BlockAll -> true

            BlockOption.DailyLimit -> {
                // A mode that was never configured does not block, as in BlockingManagerImpl.
                if (settings.dailyLimitMillis <= 0L) return false
                val usage = dailyUsage?.takeIf { it.date == timeProvider.localDateNow() } ?: return null
                usage.usageMillis >= settings.dailyLimitMillis
            }

            BlockOption.IntervalTimer -> {
                if (settings.intervalAllowanceMillis <= 0L || settings.intervalLengthMillis <= 0L) return false
                val usage = config.intervalUsage.activeIntervalAt(timeProvider.currentTimeInMillis(), settings.intervalLengthMillis)
                usage.usageMillis >= settings.intervalAllowanceMillis
            }
        }
    }

    private data class DailyUsage(val date: LocalDate, val usageMillis: Long)
}
