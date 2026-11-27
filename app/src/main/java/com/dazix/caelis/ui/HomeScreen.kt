package com.dazix.caelis.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dazix.caelis.R
import com.dazix.caelis.domain.RampCurve
import com.dazix.caelis.domain.SessionSnapshot
import com.dazix.caelis.domain.SessionState
import com.dazix.caelis.domain.StopReason
import com.dazix.caelis.domain.stability.HandheldPolicy
import com.dazix.caelis.domain.studio.StudioShortcut
import com.dazix.caelis.domain.stability.HandheldState
import com.dazix.caelis.overlay.OverlayPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    snapshot: SessionSnapshot,
    setup: SetupUiState,
    calibrated: Boolean,
    blockers: List<StartBlocker>,
    serviceUnavailable: Boolean,
    handheldWarning: Boolean,
    scrollState: ScrollState = rememberScrollState(),
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onCalibrate: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onDismissServiceMessage: () -> Unit,
    launchMode: String? = null,
) {
    val active = snapshot.state is SessionState.Running ||
        snapshot.state is SessionState.WaitingToStart ||
        snapshot.state is SessionState.Paused
    val paused = snapshot.state is SessionState.Paused

    // The "phone in hand" check before Start: a live line under the session card, and a question
    // when Start is pressed from the hand. Once the session runs, the engine watches instead.
    val timelapse = setup.mode == ModeChoice.TIMELAPSE
    val watchHandheld = HandheldPolicy.watchBeforeStart(active, timelapse, handheldWarning)
    val handheldBeforeStart by rememberHandheldState(watchHandheld)
    val holdHandheldLine = HandheldPolicy.holdStatusLine(watchHandheld, rememberHasGyroscope())
    val startGuarded = rememberHandheldStartGuard(
        state = handheldBeforeStart,
        timelapse = timelapse,
        enabled = handheldWarning,
        onStart = onStart,
    )

    // The settings button on the status card scrolls down to the settings card: where that card starts
    // in the scrolled column, and how far the floating toolbar reaches over the bottom of the screen.
    // Once there, the card's outline pulses once, so the eye lands on what it was sent to
    // rather than having to find it among the other cards.
    var settingsCardTop by remember { mutableIntStateOf(0) }
    var settingsCardHeight by remember { mutableIntStateOf(0) }
    val clearancePx = with(LocalDensity.current) { FloatingLayerClearance.roundToPx() }
    val settingsHighlight = remember { Animatable(0f) }
    val highlightSpec = ExpressiveMotion.effects<Float>()
    val scope = rememberCoroutineScope()
    val showSettings: () -> Unit = {
        scope.launch {
            settingsHighlight.snapTo(0f)
            scrollState.animateScrollTo(
                HomeScrollPolicy.settingsScrollTarget(
                    cardTop = settingsCardTop,
                    cardHeight = settingsCardHeight,
                    viewportHeight = scrollState.viewportSize,
                    bottomClearance = clearancePx,
                    maxScroll = scrollState.maxValue,
                ),
            )
            settingsHighlight.animateTo(1f, highlightSpec)
            delay(SETTINGS_HIGHLIGHT_HOLD_MS)
            settingsHighlight.animateTo(0f, tween(SETTINGS_HIGHLIGHT_FADE_MS))
        }
    }

    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(savedScrollOffset) {
        if (savedScrollOffset > 0 && scrollState.value != savedScrollOffset) {
            scrollState.scrollTo(savedScrollOffset)
        }
    }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }
            .collect { offset ->
                if (scrollState.isScrollInProgress || offset > 0) {
                    savedScrollOffset = offset
                }
            }
    }

    ScreenScaffold(
        title = stringResource(R.string.home_title),
        subtitle = setup.targetLabel
            ?: setup.targetPackage
            ?: stringResource(R.string.home_target_app_none),
        scrollState = scrollState,
        floating = {
            // Pause and resume also live in the notification and in the overlay, but a session that
            // paused itself with notifications blocked and no overlay permission would otherwise be
            // impossible to get going again without ending it. The toolbar floats above the content
            // instead of scrolling with it, so start, stop and pause are reachable at any scroll
            // position — which is the reason it exists rather than a row of buttons in the content.
            SessionToolbar(
                fab = {
                    SessionActionButton(
                        label = stringResource(
                            if (active) R.string.home_stop else R.string.home_start
                        ),
                        icon = if (active) AppIcons.Stop else AppIcons.Play,
                        onClick = if (active) onStop else startGuarded,
                        enabled = active || blockers.isEmpty(),
                        destructive = active,
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
            ) {
                // Both actions are always present, so the toolbar keeps one shape instead of turning
                // from a single circle into a pill when a session starts. Pause is inert while nothing
                // runs and calibration is refused while something does, and each says so through its
                // disabled state rather than by disappearing.
                ToolbarAction(
                    icon = if (paused) AppIcons.Play else AppIcons.Pause,
                    description = stringResource(
                        if (paused) R.string.home_resume else R.string.home_pause
                    ),
                    onClick = if (paused) onResume else onPause,
                    enabled = active,
                )
                ToolbarAction(
                    icon = AppIcons.Calibrate,
                    description = stringResource(
                        if (calibrated) R.string.home_recalibrate else R.string.home_calibrate
                    ),
                    onClick = onCalibrate,
                    enabled = !active,
                )
            }
        },
    ) {
        SessionStatusCard(
            snapshot = snapshot,
            setup = setup,
            active = active,
            handheldBeforeStart = handheldBeforeStart,
            holdHandheldLine = holdHandheldLine,
            onShowSettings = showSettings,
            launchMode = launchMode,
        )

        if (StudioShortcut.offer(snapshot.state is SessionState.Stopped, snapshot.shotsTaken, timelapse)) {
            SectionCard(tone = CardTone.ACCENT) {
                ListRowButton(
                    title = pluralStringResource(
                        R.plurals.home_studio_shortcut,
                        snapshot.shotsTaken,
                        snapshot.shotsTaken,
                    ),
                    subtitle = stringResource(R.string.home_studio_shortcut_summary),
                    icon = AppIcons.AutoAwesome,
                    onClick = { onNavigate(Screen.STUDIO) },
                )
            }
        }

        if (serviceUnavailable) {
            SectionCard(tone = CardTone.WARNING) {
                Text(
                    text = stringResource(R.string.home_service_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenAccessibilitySettings) {
                        Text(stringResource(R.string.onboarding_accessibility_action))
                    }
                    TextButton(onClick = onDismissServiceMessage) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
        }

        if (!active && blockers.isNotEmpty()) {
            SectionCard(
                title = stringResource(R.string.home_blocked_title),
                tone = CardTone.WARNING,
            ) {
                blockers.forEachIndexed { index, blocker ->
                    if (index > 0) HorizontalDivider()
                    BlockerRow(
                        blocker = blocker,
                        onNavigate = onNavigate,
                        onCalibrate = onCalibrate,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.nav_app_picker)) {
            // The package under the name, as in the mode editor: two apps can share a label.
            Column {
                Text(
                    text = setup.targetLabel
                        ?: setup.targetPackage
                        ?: stringResource(R.string.home_target_app_none),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (setup.targetLabel != null && setup.targetPackage != null) {
                    Text(
                        text = setup.targetPackage,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
            StatusChip(
                text = stringResource(
                    if (calibrated) R.string.home_calibration_ok else R.string.home_calibration_missing
                ),
                granted = calibrated,
            )
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onNavigate(Screen.APP_PICKER) },
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Text(stringResource(R.string.nav_app_picker))
                }
                Button(
                    onClick = onCalibrate,
                    enabled = !active,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Text(
                        stringResource(
                            if (calibrated) R.string.home_recalibrate else R.string.home_calibrate
                        )
                    )
                }
            }
        }

        // The taps are real touches on a real screen, so both of these are hard requirements rather
        // than advice; the engine pauses the session when either stops holding.
        SectionCard(title = stringResource(R.string.home_requirements_title)) {
            Text(
                text = stringResource(R.string.home_requirements_screen_on),
                style = MaterialTheme.typography.bodySmall,
            )
            val targetName = setup.targetLabel ?: setup.targetPackage
            Text(
                text = if (targetName != null) {
                    stringResource(R.string.home_requirements_foreground, targetName)
                } else {
                    stringResource(R.string.home_requirements_foreground_generic)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val highlightColor = MaterialTheme.colorScheme.primary
        val cardShape = MaterialTheme.shapes.large
        SectionCard(
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    settingsCardTop = coordinates.positionInParent().y.roundToInt()
                    settingsCardHeight = coordinates.size.height
                }
                // Read in the draw phase only: the fade redraws the outline, it never recomposes.
                .drawWithContent {
                    drawContent()
                    val alpha = settingsHighlight.value
                    if (alpha > 0f) {
                        val stroke = SETTINGS_HIGHLIGHT_STROKE.toPx()
                        drawOutline(
                            outline = cardShape.createOutline(size, layoutDirection, this),
                            color = highlightColor.copy(alpha = alpha),
                            style = Stroke(width = stroke),
                        )
                    }
                },
        ) {
            ListRowButton(
                title = stringResource(R.string.nav_setup),
                subtitle = stringResource(R.string.nav_setup_summary),
                icon = AppIcons.Setup,
                onClick = { onNavigate(Screen.SETUP) },
            )
            HorizontalDivider()
            ListRowButton(
                title = stringResource(R.string.nav_ramp),
                // The same line as under the planned interval; only the end interval read as the
                // whole series' interval.
                subtitle = if (setup.rampSpec() != null) {
                    plannedLine(setup)
                } else {
                    stringResource(R.string.nav_ramp_summary)
                },
                icon = AppIcons.Ramp,
                onClick = { onNavigate(Screen.RAMP) },
            )
            HorizontalDivider()
            ListRowButton(
                title = stringResource(R.string.nav_advanced),
                subtitle = stringResource(R.string.nav_advanced_summary),
                icon = AppIcons.Advanced,
                onClick = { onNavigate(Screen.ADVANCED) },
            )
            HorizontalDivider()
            ListRowButton(
                title = stringResource(R.string.nav_studio),
                subtitle = stringResource(R.string.nav_studio_summary),
                icon = AppIcons.AutoAwesome,
                onClick = { onNavigate(Screen.STUDIO) },
            )
            HorizontalDivider()
            ListRowButton(
                title = stringResource(R.string.nav_permissions),
                subtitle = stringResource(R.string.nav_permissions_summary),
                icon = AppIcons.Permissions,
                onClick = { onNavigate(Screen.ONBOARDING) },
            )
        }
    }
}

/**
 * The live numbers. This is what the app is read for from across a room, so the countdown is the
 * biggest thing on the screen and everything else is arranged around it.
 */
@Composable
private fun SessionStatusCard(
    snapshot: SessionSnapshot,
    setup: SetupUiState,
    active: Boolean,
    handheldBeforeStart: HandheldState,
    holdHandheldLine: Boolean,
    onShowSettings: () -> Unit,
    launchMode: String?,
) {
    val now by rememberElapsedRealtime(active)
    val state = snapshot.state
    SectionCard(tone = if (active) CardTone.ACCENT else CardTone.NEUTRAL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(stateLabel(state)),
                style = ExpressiveType.figureLabel,
                modifier = Modifier.weight(1f),
            )
            // The same sliders as the "Session setup" row it leads to: the gear in the navigation bar
            // already means the app's own settings. Into the card's corner by the button's own inner
            // padding, so the glyph lines up with the text edge while the touch target keeps its 48dp.
            IconButton(onClick = onShowSettings, modifier = Modifier.offset(x = 12.dp)) {
                Icon(
                    imageVector = AppIcons.Setup,
                    contentDescription = stringResource(R.string.home_show_settings),
                )
            }
        }

        val hero = heroText(snapshot = snapshot, setup = setup, now = now, active = active)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Only while something is actually running: a determinate indicator sitting at zero next
            // to an idle session is a shape with no meaning, and it reads as a decorative blob.
            if (active) {
                ProgressRing(progress = waitProgress(snapshot, setup, now), active = true)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = hero.first, style = ExpressiveType.hero)
                Text(
                    text = hero.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
                // Only when the session will switch the camera; "leave the app as it is" says nothing.
                if (launchMode != null) {
                    Text(
                        text = stringResource(R.string.home_launch_mode, launchMode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
        }

        val planned = snapshot.shotsPlanned
        Text(
            text = if (planned != null && planned > 0) {
                pluralStringResource(
                    R.plurals.home_shots_taken_of, planned, snapshot.shotsTaken, planned,
                )
            } else {
                pluralStringResource(R.plurals.home_shots_taken, snapshot.shotsTaken, snapshot.shotsTaken)
            },
            style = ExpressiveType.figure,
        )
        // Only the *achieved* interval goes here. While the session is idle the hero line beneath the
        // big number already reads "planned interval 10 s", and printing the same sentence twice is
        // what it looked like before this condition existed.
        if (snapshot.shotsTaken > 0 && snapshot.lastIntervalMs > 0L) {
            Text(
                text = stringResource(
                    R.string.home_current_interval,
                    durationText(snapshot.lastIntervalMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state is SessionState.Paused) {
            Text(
                text = stringResource(R.string.home_state_paused_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state is SessionState.Stopped) {
            Text(
                text = state.message ?: stringResource(stopReasonLabel(state.reason)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val warning = OverlayPolicy.warningText(snapshot, stringResource(R.string.session_warning_handheld))
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Before Start only; once a session runs, the line above carries the same news.
        if (!active) HandheldStatusRow(handheldBeforeStart, holdLine = holdHandheldLine)
    }
}

/**
 * The hero line and the line under it: the countdown while something is coming, the state otherwise.
 * Both are words as well as numbers, so nothing here depends on the ring beside it being seen.
 */
@Composable
private fun heroText(
    snapshot: SessionSnapshot,
    setup: SetupUiState,
    now: Long,
    active: Boolean,
): Pair<String, String> {
    val state = snapshot.state
    val next = snapshot.nextShotAtElapsed
    return when {
        state is SessionState.WaitingToStart -> {
            val remaining = (state.startsAtElapsed - now).coerceAtLeast(0L)
            durationText(remaining) to stringResource(R.string.home_starts_in, durationText(remaining))
        }
        active && next != null -> {
            val remaining = (next - now).coerceAtLeast(0L)
            // The hero line is always a duration, never a phrase: a sentence set in the display step
            // wraps to three lines, and the phrase belongs on the line below it anyway.
            durationText(remaining) to if (remaining == 0L) {
                stringResource(R.string.home_next_shot_now)
            } else {
                stringResource(R.string.home_next_shot_in, durationText(remaining))
            }
        }
        else -> durationText(setup.intervalMs) to plannedLine(setup)
    }
}

/**
 * The line under the planned interval. With a ramp the series does not stay at the number above it,
 * so the line says where it goes and how; only a ramp the session will actually run counts
 * ([SetupUiState.rampSpec] is null for one switched off or not filled in).
 */
@Composable
private fun plannedLine(setup: SetupUiState): String {
    val start = durationText(setup.intervalMs)
    val ramp = setup.rampSpec() ?: return stringResource(R.string.home_planned_interval, start)
    val text = when (ramp.curve) {
        RampCurve.LINEAR -> R.string.home_planned_ramp_linear
        RampCurve.EXPONENTIAL -> R.string.home_planned_ramp_exponential
    }
    return stringResource(text, start, durationText(ramp.endIntervalMs))
}

/** How far the wait for the next shot has run, 0 at the last shot and 1 at the next one. */
/**
 * How far the current wait has run, in `0f..1f`.
 *
 * The denominator has to be the wait the user is actually watching, which is not always the interval:
 * before the first shot the session is counting down the *start delay*, and the engine seeds
 * `lastIntervalMs` with the planned interval before any shot exists. Dividing the remaining start
 * delay by the interval is what made the ring sit half-full the moment Start was pressed (5 s delay
 * against a 10 s interval) and pinned at 0 whenever the delay was the longer of the two.
 */
internal fun waitProgress(
    snapshot: SessionSnapshot,
    setup: SetupUiState,
    now: Long,
): Float {
    val state = snapshot.state
    val total = when {
        state is SessionState.WaitingToStart -> setup.startDelayMs
        // Only once a shot has actually been taken does the achieved interval mean anything.
        snapshot.shotsTaken > 0 -> snapshot.lastIntervalMs
        else -> setup.intervalMs
    }.takeIf { it > 0L } ?: return 0f
    val target = when (state) {
        is SessionState.WaitingToStart -> state.startsAtElapsed
        else -> snapshot.nextShotAtElapsed ?: return 0f
    }
    val remaining = (target - now).coerceIn(0L, total)
    return 1f - remaining.toFloat() / total.toFloat()
}

@Composable
private fun BlockerRow(
    blocker: StartBlocker,
    onNavigate: (Screen) -> Unit,
    onCalibrate: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (blocker) {
                    StartBlocker.ServiceOff -> stringResource(R.string.home_block_service)
                    StartBlocker.OverlayMissing -> stringResource(R.string.home_block_overlay)
                    StartBlocker.NoTargetApp -> stringResource(R.string.home_block_no_target)
                    is StartBlocker.NoCalibration ->
                        stringResource(R.string.home_block_no_calibration, blocker.appLabel)
                    is StartBlocker.IntervalBelowFloor -> stringResource(
                        R.string.home_block_interval_floor,
                        durationText(blocker.intervalMs),
                        durationText(blocker.floorMs),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.size(8.dp))
        TextButton(
            onClick = {
                when (blocker) {
                    StartBlocker.ServiceOff -> onOpenAccessibilitySettings()
                    StartBlocker.OverlayMissing -> onOpenOverlaySettings()
                    StartBlocker.NoTargetApp -> onNavigate(Screen.APP_PICKER)
                    is StartBlocker.NoCalibration -> onCalibrate()
                    is StartBlocker.IntervalBelowFloor -> onNavigate(Screen.SETUP)
                }
            },
        ) {
            Text(stringResource(R.string.action_fix))
        }
    }
}

/** Ticks once a second while a session is active so countdowns move without the service pushing. */
@Composable
private fun rememberElapsedRealtime(active: Boolean) = produceState(
    initialValue = SystemClock.elapsedRealtime(),
    key1 = active,
) {
    while (true) {
        value = SystemClock.elapsedRealtime()
        if (!active) break
        delay(500)
    }
}

private fun stateLabel(state: SessionState): Int = when (state) {
    SessionState.Idle -> R.string.home_state_idle
    is SessionState.WaitingToStart -> R.string.home_state_waiting
    SessionState.Running -> R.string.home_state_running
    SessionState.Paused -> R.string.home_state_paused
    is SessionState.Stopped -> R.string.home_state_stopped
}

private fun stopReasonLabel(reason: StopReason): Int = when (reason) {
    StopReason.COMPLETED -> R.string.home_stop_reason_completed
    StopReason.USER -> R.string.home_stop_reason_user
    StopReason.MAX_DURATION -> R.string.home_stop_reason_max_duration
    StopReason.LOW_BATTERY -> R.string.home_stop_reason_low_battery
    StopReason.LOW_STORAGE -> R.string.home_stop_reason_low_storage
    StopReason.SERVICE_LOST -> R.string.home_stop_reason_service_lost
    StopReason.ERROR -> R.string.home_stop_reason_error
}

/** Human units everywhere: seconds below a minute, then minutes, then hours. */
@Composable
fun durationText(ms: Long): String = when {
    ms < 60_000L -> stringResource(R.string.duration_seconds, trimmedNumber(ms / 1000.0))
    ms < 3_600_000L -> {
        val minutes = (ms / 60_000L).toInt()
        val seconds = ((ms % 60_000L) / 1000L).toInt()
        if (seconds == 0) {
            stringResource(R.string.duration_minutes, minutes.toString())
        } else {
            stringResource(R.string.duration_minutes_seconds, minutes, seconds)
        }
    }
    else -> stringResource(
        R.string.duration_hours_minutes,
        (ms / 3_600_000L).toInt(),
        ((ms % 3_600_000L) / 60_000L).toInt(),
    )
}

private fun trimmedNumber(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.1f", value)
    }

/** A pulse, not a state: the outline peaks and goes at once, held only long enough to be seen. */
private const val SETTINGS_HIGHLIGHT_HOLD_MS = 150L

/** The outline fades out more slowly than it appears, so it reads as settling rather than blinking. */
private const val SETTINGS_HIGHLIGHT_FADE_MS = 400

private val SETTINGS_HIGHLIGHT_STROKE = 1.5.dp
