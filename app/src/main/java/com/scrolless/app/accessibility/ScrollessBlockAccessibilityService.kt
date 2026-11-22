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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.scrolless.app.BuildConfig
import com.scrolless.app.core.accessibility.AccessibilityServiceConnection
import com.scrolless.app.core.blocking.BlockingManager
import com.scrolless.app.core.blocking.InstantBlockDecision
import com.scrolless.app.core.model.BlockOption
import com.scrolless.app.core.model.BlockableApp
import com.scrolless.app.core.model.BlockingConfig
import com.scrolless.app.core.model.BlockingResult
import com.scrolless.app.core.model.ContentBlockAction
import com.scrolless.app.core.model.ResolvedBlockableApp
import com.scrolless.app.core.repository.BlockingConfigRepository
import com.scrolless.app.core.repository.SessionTracker
import com.scrolless.app.core.repository.UserSettingsStore
import com.scrolless.app.debug.DebugOverlayConfig
import com.scrolless.app.ui.overlay.BlockedContentOverlayManager
import com.scrolless.app.ui.overlay.TimerOverlayManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Accessibility service that monitors and blocks access to distracting short-form video content
 * based on user-configured limits.
 *
 * Using Android's Accessibility framework, this service detects when the user enters specific apps
 * or screens (such as YouTube Shorts, Instagram Reels, or TikTok) and enforces blocking policies
 * according to the active [BlockOption]:
 * - [BlockOption.BlockAll]: Immediately blocks all detected content.
 * - [BlockOption.DailyLimit]: Allows usage up to a configured daily time limit.
 * - [BlockOption.IntervalTimer]: Allows usage within scheduled intervals.
 * - [BlockOption.NothingSelected]: No blocking is performed.
 *
 * Depending on the app, blocking is enforced either by:
 * - Performing automatic Back navigation to exit full-screen content, or
 * - Displaying a cover overlay over the video player (e.g. TikTok) while keeping
 *   the rest of the app (like Inbox and Profile) usable.
 *
 * The service tracks active viewing time, displays an optional timer overlay, and saves completed
 * sessions to the user's usage history.
 *
 * Permissions Required:
 * - Accessibility service permission must be granted by the user.
 *
 * @see BlockingManager for blocking logic and evaluation
 * @see BlockOption for available blocking strategies
 * @see BlockableApp for supported apps
 * @see ContentScanner for screen and window scanning
 * @see BlockedContentOverlayManager for overlay presentation
 */
@SuppressLint("AccessibilityPolicy") // Accessibility APIs are required to enforce user-configured blocking policies.
@AndroidEntryPoint
class ScrollessBlockAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        timerOverlayManager.attachServiceContext(this)
        blockedContentOverlayManager.attachServiceContext(this)

        if (BuildConfig.DEBUG) {
            serviceScope.launch {
                DebugOverlayConfig.forceLegacyOverlay.drop(1).collect {
                    if (contentSession?.content?.cover != null) {
                        onBlockedContentExited()
                    }
                    blockedContentOverlayManager.hide()
                }
            }
        }
    }

    /** Main thread handler for running UI-related actions like overlays and navigation. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Coroutine scope for service operations. Uses SupervisorJob so child failures don't cancel the service. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tracks daily and session usage time across monitored apps. */
    @Inject
    lateinit var sessionTracker: SessionTracker

    /** Evaluates blocking rules and decides when to block or allow content. */
    @Inject
    lateinit var blockingManager: BlockingManager

    /** In-memory copy of the blocking state, so known blocks can happen on the detecting event. */
    @Inject
    lateinit var instantBlockDecision: InstantBlockDecision

    /** Repository for active blocking configurations and rules. */
    @Inject
    lateinit var blockingConfigRepository: BlockingConfigRepository

    /** Store for user settings, pause durations, and app preferences. */
    @Inject
    lateinit var userSettingsStore: UserSettingsStore

    /** Manages the floating timer overlay that shows remaining or elapsed time. */
    @Inject
    lateinit var timerOverlayManager: TimerOverlayManager

    /** Manages the blocker overlay placed directly over distracting video players. */
    @Inject
    lateinit var blockedContentOverlayManager: BlockedContentOverlayManager

    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }

    /** Currently active content session, including viewing state and any attached cover. */
    private var contentSession: ContentSession? = null

    /** Scans the window tree to find target apps, videos, and compute cover coordinates. */
    private val contentScanner by lazy {
        ContentScanner(
            service = this,
            windowAttachedCover = { isWindowAttachedCoverAllowed },
            allowVideosSentByDm = { currentAllowVideosSentByDm },
            includeStories = { currentIncludeStories },
            currentActivity = { currentActivity },
        )
    }

    /**
     * Whether covers should attach directly to app windows (Android 14+), or use screen overlays (debug fallback).
     */
    private val isWindowAttachedCoverAllowed: Boolean
        get() = !(BuildConfig.DEBUG && DebugOverlayConfig.forceLegacyOverlay.value)

    /** Active viewing session where usage time is currently running (not yet covered). */
    private val viewingSession: ContentSession?
        get() = contentSession?.takeUnless { it.isCovered }

    /** Whether the user enabled the floating timer overlay in settings. */
    private var currentTimerOverlayEnabled: Boolean = false

    /** Whether Stories participate in usage tracking and blocking. */
    private var currentIncludeStories: Boolean = false

    /** Whether videos received in direct messages are exempt from blocking. */
    private var currentAllowVideosSentByDm: Boolean = false

    /** Timestamp until which blocking is temporarily paused. */
    @Volatile
    private var pauseUntilMillis: Long = 0L

    private fun isPauseActive(now: Long = System.currentTimeMillis()): Boolean = pauseUntilMillis > now

    /** Whether the saved pause has loaded. Until then a pause may be active that we do not know about. */
    private var pauseStateLoaded: Boolean = false

    /** Whether the DM preference has loaded. Until then a DM video may be wrongly treated as blocked. */
    private var allowVideosSentByDmLoaded: Boolean = false

    /** Blocking without waiting for the database is only safe once every exemption is known. */
    private val canBlockInstantly: Boolean
        get() = pauseStateLoaded && allowVideosSentByDmLoaded

    /** Stops repeating failures from running on every event until the screen changes. */
    private val eventFailureGuard = EventFailureGuard()

    /** Prevents a second Back while the first one is still leaving the blocked screen. */
    private val globalActionThrottle = GlobalActionThrottle()

    /** App whose content is hidden by the brief cover shown around Back navigation, or null if none is shown. */
    private var transientCoverApp: ResolvedBlockableApp? = null

    /** Removes a brief cover that was never cleared, so it cannot stay over an allowed screen. */
    private val transientCoverTimeout = Runnable {
        Timber.d("Brief content cover timed out")
        hideTransientCover()
    }

    /** True if blocking is currently skipped due to an active pause or DM exemption. */
    private val isBlockingSuppressed: Boolean
        get() = isPauseActive() || contentSession?.blockingSuppressed == true

    /** Currently tracked target app in the foreground, or null if unrelated. */
    private var currentForegroundBrainRotApp: ResolvedBlockableApp? = null

    /** Last activity reported by a screen-change event; ordinary view events leave it unchanged. */
    private var currentActivity: ComponentName? = null

    /** Periodically checks if the user exceeded their usage limit while watching blocked content. */
    private val videoCheckRunnable: Runnable = Runnable {

        if (!validateTrackedAppState("Periodic check")) {
            return@Runnable
        }

        if (contentSession == null) {
            Timber.v("Periodic check runnable executed but no longer processing content")
            return@Runnable
        }

        val session = viewingSession ?: return@Runnable
        val now = System.currentTimeMillis()
        val elapsed = now - session.startedAtMillis
        // Save long visits in parts, so a killed service loses at most one interval of viewing time.
        val checkpoint = session.checkpoint(now, CHECKPOINT_INTERVAL_MILLIS)

        if (isBlockingSuppressed) {
            checkpoint?.let(::launchSaveViewing)
            // While paused or suppressed, continue scheduling so usage and timer overlay stay current.
            Timber.v(
                "Periodic check skipped: elapsed=%d ms, paused=%b, suppressed=%b",
                elapsed,
                isPauseActive(),
                session.blockingSuppressed,
            )
            mainHandler.postDelayed(videoCheckRunnable, 1000)
        } else {
            Timber.v("Periodic check running: elapsed=%d ms", elapsed)
            serviceScope.launch {
                // Save first: the check adds only the unsaved time to the saved usage, so both must agree.
                checkpoint?.let { withContext(NonCancellable) { saveViewing(it) } }
                val action = blockingManager.onPeriodicCheck(session.unsavedMillis(System.currentTimeMillis()))
                if (viewingSession !== session) return@launch
                when (action) {
                    is BlockingResult.BlockNow -> {
                        Timber.i("Periodic check: limit reached (elapsed=%d). Navigating back.", elapsed)
                        enforceBlocking(session)
                    }

                    is BlockingResult.CheckLater -> {
                        Timber.v("Periodic check: limit not reached, will check again later in %d ms", action.delayMillis)
                        mainHandler.postDelayed(videoCheckRunnable, nextCheckDelay(session, action.delayMillis))
                    }

                    is BlockingResult.Continue -> {
                        Timber.v("Periodic check: limit not reached, scheduling next check")
                        mainHandler.postDelayed(videoCheckRunnable, nextCheckDelay(session, 10000))
                    }
                }
            }
        }
    }

    /** Runs the next check no later than the next checkpoint is due. */
    private fun nextCheckDelay(session: ContentSession, delayMillis: Long): Long =
        minOf(delayMillis, session.millisUntilCheckpoint(System.currentTimeMillis(), CHECKPOINT_INTERVAL_MILLIS))

    /**
     * Called when the accessibility service connects and is ready to monitor apps.
     * Sets up package listeners and begins observing settings and blocking preferences.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("Accessibility service connected")
        AccessibilityServiceConnection.onConnected()

        // Start with restricted configuration to save battery
        refreshServiceConfig()

        // The user may have just enabled accessibility in Android Settings; bring them back once.
        // Only honored right after connecting (the window covers a cold start loading the flag
        // from the database), so setting the flag while already running doesn't consume it early.
        serviceScope.launch {
            val waiting = withTimeoutOrNull(WAITING_FOR_ACCESSIBILITY_WINDOW) {
                userSettingsStore.getWaitingForAccessibility().first { it }
            }
            if (waiting == true) {
                Timber.i("Bringing app to foreground")
                bringAppToForeground()
                userSettingsStore.setWaitingForAccessibility(false)
            } else {
                Timber.i("Skipping bringing app to foreground")
            }
        }

        serviceScope.launch {
            // Usage changes constantly; only the mode and its settings need a new handler.
            blockingConfigRepository.observeConfig()
                // Interval usage changes are needed here too, so keep every saved config in memory.
                .onEach { instantBlockDecision.onConfigChanged(it) }
                .map { it.activeOption to it.settings }
                .distinctUntilChanged()
                .onEach { (option, settings) ->
                    applySettingSafely("blocking option") {
                        Timber.d("Blocking option changed: %s", option)
                        blockingManager.init(option, settings)
                        reconsiderVisibleContent()
                    }
                }
                .catch { Timber.e(it, "Stopped following blocking option changes") }
                .collect()
        }

        // Load today's usage early so a reached daily limit is known before the first video opens.
        serviceScope.launch {
            try {
                instantBlockDecision.refreshDailyUsage { sessionTracker.getDailyUsage() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not preload daily usage; blocking decisions will wait for it")
            }
        }

        serviceScope.launch {
            userSettingsStore.getTimerOverlayEnabled()
                .onEach { currentTimerOverlayEnabled = it }
                .catch { Timber.e(it, "Stopped following the timer overlay setting") }
                .collect()
        }

        // Re-detect an open video when the DM preference changes, then apply the new decision.
        serviceScope.launch {
            userSettingsStore.getAllowVideosSentByDm()
                .onEach {
                    applySettingSafely("DM videos") {
                        currentAllowVideosSentByDm = it
                        allowVideosSentByDmLoaded = true
                        refreshDetectedContent()
                        reconsiderVisibleContent()
                    }
                }
                .catch { Timber.e(it, "Stopped following the DM videos setting") }
                .collect()
        }

        serviceScope.launch {
            userSettingsStore.getIncludeStories()
                .onEach {
                    applySettingSafely("Stories") {
                        currentIncludeStories = it
                        refreshDetectedContent()
                        reconsiderVisibleContent()
                    }
                }
                .catch { Timber.e(it, "Stopped following the Stories setting") }
                .collect()
        }

        // Apply a pause immediately, even if the app has not sent a new screen event.
        serviceScope.launch {
            userSettingsStore.getPauseUntil()
                .onEach { newPauseUntil ->
                    applySettingSafely("pause") {
                        pauseUntilMillis = newPauseUntil
                        pauseStateLoaded = true
                        reconsiderVisibleContent()
                    }
                }
                .catch { Timber.e(it, "Stopped following the pause setting") }
                .collect()
        }
    }

    /**
     * Applies one setting change. A failure skips only this change, so the setting is still followed;
     * errors from the setting's flow itself end that flow and are logged by its `catch`.
     */
    private inline fun applySettingSafely(setting: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not apply the %s setting change", setting)
        }
    }

    /**
     * Cleans up tracking and overlays when the user leaves a monitored app or turns off the screen.
     *
     * @param reason Diagnostic message for logs explaining why the session ended.
     */
    private fun handleTrackedAppExit(reason: String) {
        // A window cover can outlive tracking while its parent animates away. Screen-off clears it.
        if (!powerManager.isInteractive) blockedContentOverlayManager.hide()
        // A brief Back cover belongs to the screen being left, so it never follows the user out.
        hideTransientCover()
        if (contentSession == null && currentForegroundBrainRotApp == null) {
            return
        }

        Timber.i("Handling tracked app exit: %s", reason)
        if (contentSession != null) {
            onBlockedContentExited(appLeft = true)
        }

        if (currentForegroundBrainRotApp != null) {
            updateForegroundAppState(null)
        }
    }

    /** Ends tracking when the screen turns off or the tracked app is no longer visible. */
    private fun validateTrackedAppState(source: String): Boolean {
        if (!powerManager.isInteractive) {
            handleTrackedAppExit("$source - screen is off")
            return false
        }

        val trackedForegroundApp = currentForegroundBrainRotApp ?: return true
        if (contentScanner.isAppVisible(trackedForegroundApp)) {
            return true
        }

        handleTrackedAppExit("$source - tracked app package is not visible")
        return false
    }

    /**
     * Updates package filtering so we listen broadly while an app is open to detect exits,
     * or narrowly when idle to save battery.
     */
    private fun refreshServiceConfig() {
        updateServiceConfig(contentSession != null || currentForegroundBrainRotApp != null)
    }

    /**
     * Processes incoming accessibility events to detect when the user enters or exits blocked content.
     *
     * @param event The accessibility event emitted by Android.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!eventFailureGuard.shouldProcess(isWindowChange)) return
        try {
            processAccessibilityEvent(event)
            eventFailureGuard.onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An uncaught error here would stop the whole service, and with it every block.
            Timber.e(e, "Failed to process accessibility event (type=%d, package=%s)", event.eventType, event.packageName)
            if (eventFailureGuard.onFailure()) {
                Timber.e(
                    "%d accessibility events failed in a row; skipping events until the next window change",
                    eventFailureGuard.consecutiveFailures,
                )
            }
        }
    }

    private fun processAccessibilityEvent(event: AccessibilityEvent) {
        if (!powerManager.isInteractive) {
            handleTrackedAppExit("screen is off while receiving accessibility event")
            return
        }

        // TYPE_WINDOW_STATE_CHANGED also triggers for dialogs, popups, and keyboards.
        // Only record components ending in "Activity" so transient UI does not overwrite the active screen.
        val className = event.className?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && className?.endsWith("Activity") == true) {
            currentActivity = event.packageName?.let { ComponentName(it.toString(), className) }
        }

        val scan = contentScanner.scan(
            event.packageName?.toString(),
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            contentSession?.app,
            currentForegroundBrainRotApp,
            contentSession?.takeIf { it.isCovered }?.content?.cover,
        )
        if (scan.trackedAppExited) handleTrackedAppExit("covered app lost foreground")
        val userActiveApp = scan.foregroundApp
        updateForegroundAppState(userActiveApp)
        if (userActiveApp == null && contentSession == null) return
        if (!scan.rootAvailable) {
            validateTrackedAppState("Root node missing")
            return
        }
        val detectedContent = scan.content

        // No match means the user left the video, even if they stayed inside the same app.
        if (detectedContent != null) {
            onBlockedContentDetected(detectedContent)
        } else {
            if (contentSession != null) {
                Timber.v("Brain rot content no longer detected, triggering exit")
                onBlockedContentExited()
            }
            // Returning directly to an allowed screen must also clear a retained window cover.
            if (userActiveApp?.coverDetector != null) {
                blockedContentOverlayManager.hide()
            }
        }
    }

    /** Records when the user opens or leaves a monitored app and adjusts tracking accordingly. */
    private fun updateForegroundAppState(nextApp: ResolvedBlockableApp?) {
        val previousApp = currentForegroundBrainRotApp
        if (previousApp == nextApp) return

        if (previousApp != null) {
            Timber.v("*** User appears to have left a brain rot app: %s (%s)", previousApp.app.name, previousApp.packageId)
            sessionTracker.onAppClose()
            // When exiting to an unmonitored screen (e.g. launcher or home), reset the saved activity
            if (nextApp == null && currentActivity?.packageName == previousApp.packageId) {
                currentActivity = null
            }
        }

        if (nextApp != null) {
            Timber.v("**** User appears to have entered a brain rot app: %s (%s)", nextApp.app.name, nextApp.packageId)
            sessionTracker.onAppOpen(nextApp.app)
        }

        currentForegroundBrainRotApp = nextApp
        refreshServiceConfig()
    }

    /** Called when the accessibility service is interrupted by the system. */
    override fun onInterrupt() = Unit

    /** Cleans up overlays, cancels handlers, and stops all background jobs when the service is destroyed. */
    override fun onDestroy() {
        AccessibilityServiceConnection.onDisconnected()
        super.onDestroy()
        Timber.d(
            "Service state at destroy: hasContentSession=%b, viewingApp=%s",
            contentSession != null, viewingSession?.app,
        )
        // Save the visit in progress. serviceScope is cancelled below, so use a scope of its own;
        // nothing waits for it, so the main thread is not blocked while the database is written.
        val finished = contentSession?.finish(System.currentTimeMillis())
        contentSession = null
        if (finished != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                try {
                    // Nobody waits for this coroutine, so a timeout could only cut the save halfway.
                    withContext(NonCancellable) { saveViewing(finished) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Could not save the viewing session at shutdown")
                }
            }
        }
        stopPeriodicCheck()
        mainHandler.removeCallbacks(coveredContentCheck)
        hideTransientCover()
        blockedContentOverlayManager.hide()
        timerOverlayManager.cleanup()
        serviceScope.cancel()
    }

    /** Handles detected blocked content by updating the active session or starting a new one. */
    private fun onBlockedContentDetected(detectedContent: DetectedBlockedContent) {
        val activeSession = contentSession
        if (activeSession != null) {
            // A cover and a Back action need separate sessions, even inside the same app.
            if (!activeSession.content.canContinueWith(detectedContent)) {
                onBlockedContentExited()
            } else {
                val suppressionChanged = activeSession.blockingSuppressed != detectedContent.blockingSuppressed
                activeSession.content = detectedContent
                if (activeSession.isCovered && detectedContent.cover?.let { blockedContentOverlayManager.show(it) } != true) {
                    // The failed update removed the cover. Start a new viewing period below.
                    onBlockedContentExited()
                } else {
                    if (suppressionChanged) reconsiderVisibleContent()
                    return
                }
            }
        }

        contentSession = ContentSession(detectedContent, System.currentTimeMillis())
        onBlockedContentEntered()
    }

    /**
     * Called when the user starts viewing blocked content.
     * Starts periodic limit checks and displays the timer overlay if enabled.
     */
    private fun onBlockedContentEntered() {
        val session = viewingSession ?: return
        Timber.d("Entered blocked content at %d (app=%s, paused=%b)", session.startedAtMillis, session.app, isPauseActive())

        // Expand service scope to detect when user leaves the app (e.g. to launcher)
        refreshServiceConfig()

        startPeriodicCheck()

        // Decide on this same event when the in-memory state already answers, instead of showing the
        // content while usage loads. The database check below still runs and remains the authority.
        if (!isBlockingSuppressed) {
            when (if (canBlockInstantly) instantBlockDecision.shouldBlockNow() else null) {
                true -> {
                    Timber.i("Blocking on enter without waiting for usage")
                    // Main.immediate starts this on the current main-thread pass, without waiting for the
                    // database. enforceBlocking only suspends after the cover or Back has been applied.
                    serviceScope.launch { enforceBlocking(session) }
                }

                // Not known yet: hide Back-blocked content until the check below decides.
                null -> showTransientCover(session)

                false -> Unit
            }
        }

        serviceScope.launch {
            // Load timer settings alongside usage so displaying the timer does not add another wait.
            val timerConfig = if (currentTimerOverlayEnabled) async { loadTimerConfig() } else null
            val dailyUsageMillis = instantBlockDecision.refreshDailyUsage { sessionTracker.getDailyUsage() }
            // Share this usage reading with the timer, including during a pause or DM exemption.
            val shouldBlock = blockingManager.onEnterBlockedContent(dailyUsageMillis)
            // Reading the limit may take time. Ignore the answer if the user has already left.
            if (viewingSession !== session) return@launch
            if (!isBlockingSuppressed && shouldBlock) {
                Timber.i("Blocking on enter")
                enforceBlocking(session)
                return@launch
            }

            // Viewing is allowed now, so remove the brief cover shown while deciding.
            hideTransientCover()

            // Viewing is allowed now, so remove any cover kept from an earlier visit.
            if (session.app.coverDetector != null) {
                blockedContentOverlayManager.hide()
            }

            if (currentTimerOverlayEnabled) {
                // The saved timer preference may have finished loading during the blocking check.
                val config = if (timerConfig != null) timerConfig.await() else loadTimerConfig()
                if (config != null) {
                    showTimerOverlayIfEnabled(session, config, dailyUsageMillis)
                }
            }

            if (isBlockingSuppressed) {
                Timber.d(
                    "Skipping blocking check on enter, but tracking usage (paused=%b, suppressed=%b)",
                    isPauseActive(),
                    session.blockingSuppressed,
                )
            } else {
                Timber.d("Content allowed on enter, will monitor usage")
            }
        }
    }

    /** A timer settings failure should skip the timer without interrupting blocking. */
    private suspend fun loadTimerConfig(): BlockingConfig? = try {
        blockingConfigRepository.getConfig()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Could not load timer settings; skipping the timer overlay")
        null
    }

    /** Shows the timer using the settings and usage already loaded for this viewing session. */
    private fun showTimerOverlayIfEnabled(session: ContentSession, config: BlockingConfig, dailyUsageMillis: Long) {
        // Reads can finish after navigation. Only show the timer for the same viewing session.
        if (!currentTimerOverlayEnabled || viewingSession !== session) return

        Timber.v("Showing timer overlay")
        // This coroutine already runs on the main thread, so no Handler post is needed.
        if (config.activeOption == BlockOption.IntervalTimer) {
            timerOverlayManager.showInterval(
                sessionStartAt = session.startedAtMillis,
                intervalUsage = config.intervalUsage,
                intervalLengthMillis = config.settings.intervalLengthMillis,
            )
        } else {
            timerOverlayManager.showDaily(
                sessionStartAt = session.startedAtMillis,
                dailyUsageMillis = dailyUsageMillis,
            )
        }
    }

    /**
     * Called when the user leaves blocked content.
     * Stops usage checks, hides overlays, and records the completed viewing session.
     *
     * @param appLeft True if the user switched away from the host app entirely.
     */
    private fun onBlockedContentExited(appLeft: Boolean = false) {
        val session = contentSession
        contentSession = null
        val hadCover = session?.content?.cover != null
        val finished = session?.finish(System.currentTimeMillis())
        stopPeriodicCheck()
        mainHandler.removeCallbacks(coveredContentCheck)
        // The blocked screen is gone (Back finished, user left, or detection ended), so is its brief cover.
        hideTransientCover()
        // Keep an attached cover during app switching, but remove it when changing screens in the app.
        if (hadCover) {
            if (appLeft) {
                blockedContentOverlayManager.onAppExit(powerManager.isInteractive)
            } else {
                blockedContentOverlayManager.hide()
            }
        }
        refreshServiceConfig()
        if (session == null || finished == null) return // A covered player already finished its viewing session.
        // The timer belongs to the whole visit, not only to its last unsaved piece.
        timerOverlayManager.hide(session.startedAtMillis, finished.endedAtMillis)
        launchSaveViewing(finished)
    }

    /** Saves in the background; cancelling the service scope (on destroy) cannot interrupt a save already started. */
    private fun launchSaveViewing(finished: FinishedViewing) {
        serviceScope.launch { withContext(NonCancellable) { saveViewing(finished) } }
    }

    /**
     * Records viewing time in the session tracker and closes the period with the blocking manager.
     * [finished] may be one piece of a longer visit: earlier pieces were already saved, so only this one is added.
     */
    private suspend fun saveViewing(finished: FinishedViewing) {
        // A database failure must not cut the limit checks of the visit or stop the service, so it is only logged.
        try {
            if (finished.isTracked) {
                sessionTracker.addToDailyUsage(finished.durationMillis, finished.app.app)
                blockingManager.onExitBlockedContent(finished.startedAtMillis, finished.endedAtMillis)
                // Keep the in-memory usage current, so a limit reached by this visit blocks the next one instantly.
                instantBlockDecision.refreshDailyUsage { sessionTracker.getDailyUsage() }
            } else {
                // Sub-second duration (such as content immediately blocked on enter) does not count as viewed content.
                blockingManager.onExitBlockedContent(finished.startedAtMillis, finished.startedAtMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not save viewing %d..%d", finished.startedAtMillis, finished.endedAtMillis)
        }
    }

    /** Starts periodic checks to monitor usage limits while content is visible. */
    private fun startPeriodicCheck() {
        Timber.d("Starting periodic usage checks (every 1 second)")
        mainHandler.removeCallbacks(videoCheckRunnable)
        mainHandler.postDelayed(videoCheckRunnable, 1000)
    }

    /** Stops periodic usage checks when the user exits blocked content. */
    private fun stopPeriodicCheck() {
        mainHandler.removeCallbacks(videoCheckRunnable)
        Timber.d("Stopped periodic usage checks")
    }

    /** Enforces the active blocking policy by attaching a cover overlay or performing Back navigation. */
    private suspend fun enforceBlocking(session: ContentSession) {
        if (viewingSession !== session || isBlockingSuppressed) return
        when (val action = session.content.blockAction) {
            is ContentBlockAction.PerformGlobalAction -> {
                // Hide the content while Back animates. Back still runs if the cover cannot be shown.
                showTransientCover(session)
                if (globalActionThrottle.tryAcquire(session.app, System.currentTimeMillis())) {
                    performGlobalAction(action.action)
                } else {
                    // The previous Back may still be leaving this screen; check again shortly instead.
                    Timber.d("Skipping repeated navigation for %s", session.app.packageId)
                    startPeriodicCheck()
                }
            }

            ContentBlockAction.CoverVideoRegion -> {
                // The user may have switched apps while we were waiting for the blocking decision.
                if (!contentScanner.isAppVisible(session.app)) {
                    handleTrackedAppExit("app lost foreground before covering")
                    return
                }
                val cover = session.content.cover ?: return
                // Returning from Recents can replace the app's drawing surface without resizing it.
                // Reattach now; normal screen updates can keep using that attachment.
                if (!blockedContentOverlayManager.show(cover, refreshAttachment = true)) {
                    startPeriodicCheck()
                    return
                }
                // Stop counting only after the cover was shown successfully.
                val finished = session.cover(System.currentTimeMillis()) ?: return
                stopPeriodicCheck()
                timerOverlayManager.dismissImmediately()
                refreshServiceConfig()
                withContext(NonCancellable) { saveViewing(finished) }
                scheduleCoveredContentCheck()
            }
        }
    }

    /**
     * Shows a blank cover over the detected viewer of an app blocked with Back, reusing the TikTok cover overlay.
     * Failure only logs: blocking then falls back to Back alone, exactly as before.
     */
    private fun showTransientCover(session: ContentSession) {
        // Apps with their own cover (TikTok) never need this; their cover follows the blocking decision.
        if (session.app.coverDetector != null || session.content.blockAction !is ContentBlockAction.PerformGlobalAction) return
        val cover = try {
            contentScanner.findTransientCover(session.app)
        } catch (e: RuntimeException) {
            Timber.w(e, "Could not measure the brief content cover")
            null
        }
        // The viewer may already be gone because Back is running. Keep a cover that is already up for it.
        if (cover == null) return
        val shown = try {
            blockedContentOverlayManager.show(cover)
        } catch (e: RuntimeException) {
            Timber.w(e, "Could not show the brief content cover")
            false
        }
        if (!shown) {
            // A failed show can leave half-created overlay state behind; clear it and rely on Back alone.
            transientCoverApp = session.app
            hideTransientCover()
            return
        }
        transientCoverApp = session.app
        mainHandler.removeCallbacks(transientCoverTimeout)
        mainHandler.postDelayed(transientCoverTimeout, TRANSIENT_COVER_MAX_MILLIS)
    }

    /** Removes the brief Back cover if one is shown. Never touches a TikTok cover. */
    private fun hideTransientCover() {
        mainHandler.removeCallbacks(transientCoverTimeout)
        if (transientCoverApp == null) return
        transientCoverApp = null
        try {
            blockedContentOverlayManager.hide()
        } catch (e: RuntimeException) {
            Timber.w(e, "Could not remove the brief content cover")
        }
    }

    /** Periodically checks whether a covered video is still on screen or if new allowance is available. */
    private val coveredContentCheck = Runnable {
        refreshDetectedContent()
        reconsiderVisibleContent()
    }

    private fun scheduleCoveredContentCheck() {
        mainHandler.removeCallbacks(coveredContentCheck)
        if (contentSession?.isCovered == true) {
            mainHandler.postDelayed(coveredContentCheck, 1000)
        }
    }

    /** Re-scans visible windows to update the current content session. */
    private fun refreshDetectedContent() {
        if (!validateTrackedAppState("Content refresh")) return
        val session = contentSession
        val app = session?.app ?: currentForegroundBrainRotApp
        if (app == null) {
            // Preferences can load before the first app event; discover what is already open.
            val scan = contentScanner.scan(eventPackage = null, windowsChanged = true, trackedApp = null, foregroundApp = null)
            updateForegroundAppState(scan.foregroundApp)
            scan.content?.let(::onBlockedContentDetected)
            return
        }
        val activeCover = session?.takeIf { it.isCovered }?.content?.cover
        val content = contentScanner.findVisibleBlockedContent(app, activeCover)
        if (content != null) {
            onBlockedContentDetected(content)
        } else if (session != null) {
            onBlockedContentExited()
        }
    }

    /** Checks if the user's latest settings or allowances should block or unblock the current screen. */
    private fun reconsiderVisibleContent() {
        val current = contentSession ?: return
        if (current.app.coverDetector != null && !contentScanner.isAppVisible(current.app)) {
            handleTrackedAppExit("app lost foreground before reconsidering content")
            return
        }
        serviceScope.launch {
            if (!current.isCovered) {
                if (!isBlockingSuppressed &&
                    blockingManager.onPeriodicCheck(current.unsavedMillis(System.currentTimeMillis())) == BlockingResult.BlockNow
                ) {
                    enforceBlocking(current)
                }
            } else {
                // Query blocking policy without starting a viewing session or recording usage prematurely.
                val shouldBlock = blockingManager.shouldBlockContent()
                if (contentSession !== current || (shouldBlock && !isBlockingSuppressed)) {
                    scheduleCoveredContentCheck()
                    return@launch
                }
                // Transition the covered session into an active viewing session and start counting usage from now.
                val viewing = ContentSession(current.content, System.currentTimeMillis())
                contentSession = viewing
                mainHandler.removeCallbacks(coveredContentCheck)
                onBlockedContentEntered()
            }
        }
    }

    /**
     * Updates accessibility event filtering.
     *
     * @param listenToAll If true, listens to all packages to detect exits; if false, filters to target apps to save battery.
     */
    private fun updateServiceConfig(listenToAll: Boolean) {
        val info = serviceInfo ?: return

        // Do not delay Home/app-switching events while covered, or the old screen cover can linger.
        info.notificationTimeout = if (contentSession?.isCovered == true) 0L else DEFAULT_NOTIFICATION_TIMEOUT_MS

        if (listenToAll) {
            info.packageNames = null // Listen to all
            Timber.d("Expanded service configuration to listen to all packages")

            // Ensure windows are available for visibility-based exit checks.
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.packageNames = BlockableApp.entries.flatMap { it.getPackageIds() }.toTypedArray()
            Timber.d("Restricted service configuration to target packages only")
        }
        serviceInfo = info
    }

    /** Reopens Scrolless after the user grants accessibility permissions in system settings. */
    private fun bringAppToForeground() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            Timber.i("Successfully launched app to foreground")
        } catch (e: Exception) {
            Timber.e(e, "Failed to bring app to foreground")
        }
    }

    private companion object {
        /** Default event debounce timeout in ms (matches android:notificationTimeout in accessibility_service_config.xml). */
        const val DEFAULT_NOTIFICATION_TIMEOUT_MS = 250L

        /** Longest time a brief Back cover may stay up if nothing else removes it. */
        const val TRANSIENT_COVER_MAX_MILLIS = 1_500L

        /** How often a long visit is saved while it continues, so a killed service loses little viewing time. */
        const val CHECKPOINT_INTERVAL_MILLIS = 15_000L

        /** How long after connecting the service will bring the app back if it was waiting. */
        val WAITING_FOR_ACCESSIBILITY_WINDOW = 5.seconds
    }
}
