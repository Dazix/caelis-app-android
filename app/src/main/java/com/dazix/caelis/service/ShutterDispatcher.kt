package com.dazix.caelis.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dazix.caelis.domain.TapTarget
import com.dazix.caelis.domain.cameramode.CustomCameraMode
import com.dazix.caelis.domain.cameramode.ModeProofPolicy
import com.dazix.caelis.domain.cameramode.NodeAtPoint
import com.dazix.caelis.domain.cameramode.NodeInfo
import com.dazix.caelis.domain.cameramode.NodeSignature
import com.dazix.caelis.domain.cameramode.RecordedGesture
import com.dazix.caelis.domain.cameramode.RecordedStep
import com.dazix.caelis.domain.cameramode.StepMatchPolicy
import com.dazix.caelis.domain.cameramode.StepTarget
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Which of the two mechanisms actually pressed the shutter. Reported back so the engine can log it. */
enum class TapPath {
    /** The shutter node accepted `ACTION_CLICK`. */
    NODE_CLICK,

    /** A synthetic tap on the located node's bounds centre. */
    GESTURE_ON_NODE,

    /** A synthetic tap on the calibrated [TapTarget]. */
    GESTURE_ON_TARGET,

    /** Nothing was pressed. */
    NONE,
}

/** What to press and how. */
data class ShutterRequest(
    val profile: CameraProfile,
    /** Calibrated fallback, in the space described by [capturedRotation]. */
    val calibrated: TapTarget? = null,
    /** Rotation the calibration was taken in; the default is natural space. See [DisplayGeometry]. */
    val capturedRotation: Int = Surface.ROTATION_0,
    /** PeriodicTap: never touch the node tree, always dispatch a gesture at the calibrated point. */
    val gestureOnly: Boolean = false,
)

/** Result of one shutter attempt. */
data class TapOutcome(
    val path: TapPath,
    val fired: Boolean,
    val x: Float = -1f,
    val y: Float = -1f,
    /** How the node was found: `viewId:…`, `label:…`, `region`, or `calibrated`. */
    val matchedBy: String? = null,
    /** Machine-readable reason when [fired] is false. */
    val failure: String? = null,
) {
    override fun toString(): String =
        "${path.name}${if (fired) "" else "(failed:$failure)"} at ${x.toInt()},${y.toInt()}" +
            (matchedBy?.let { " via $it" } ?: "")
}

/** What [ShutterDispatcher.selectCameraMode] did. */
enum class ModeSelection {
    /** The mode's chip was already selected; nothing was tapped. */
    ALREADY,

    /** The chip was clicked and the UI was given time to settle. */
    SWITCHED,

    /** No chip for the mode on screen (yet): a camera still starting up shows its carousel late. */
    NOT_FOUND,

    /** The profile knows no such mode; nothing will ever be tapped for it. */
    UNKNOWN_MODE,
}

/** What [ShutterDispatcher.replayCustomMode] did. A failed step carries its index (0-based). */
sealed interface CustomReplayResult {
    /** The mode's proof was already selected; nothing was tapped. */
    data object Already : CustomReplayResult

    /** Every step was replayed and the UI was given time to settle. */
    data object Switched : CustomReplayResult

    /** The step's element did not appear (or several fit it); the steps after it were not replayed. */
    data class StepNotFound(val index: Int) : CustomReplayResult

    /** The step needs its recorded position, and this is not the screen it was recorded on. */
    data class ScreenMismatch(val index: Int) : CustomReplayResult

    /** The platform refused or cancelled the step's gesture. */
    data class GestureRejected(val index: Int) : CustomReplayResult

    /** The caller stopped wanting it (the session ended) before the replay was done. */
    data object Cancelled : CustomReplayResult
}

/** One look at the screen: the real nodes, and the same nodes for the pure policies. */
class Screen(
    val geometry: DisplayGeometry,
    val nodes: List<AccessibilityNodeInfo>,
    val infos: List<NodeInfo>,
)

/**
 * Presses the shutter. Hybrid on purpose: `ACTION_CLICK` on the real node is precise and survives
 * layout changes, but plenty of camera apps expose a shutter that is not click-accessible (custom
 * touch handling, `isClickable == false`, or a click that returns false and does nothing), so a
 * synthetic tap is always available as the fallback.
 *
 * Resolution order for what gets pressed (each step only runs when the previous found nothing):
 *  1. `viewIdResourceName` against [CameraProfile.shutterViewIdPatterns]
 *  2. `contentDescription`, then `text`, against [CameraProfile.shutterDescriptionPatterns]
 *  3. the user's calibrated [TapTarget], when there is one
 *  4. the region heuristic — the largest clickable node lying **inside** the bottom-centre third
 *
 * Steps 3 and 4 interact: the region heuristic is a *guess*, so it is only allowed to answer when
 * there is no calibrated point, or when the node it found actually contains that point (in which
 * case it is the same button, and `ACTION_CLICK` on the real node is the more precise way to press
 * it). A mode pill, a gallery thumbnail or a flash chip in that region would otherwise accept the
 * click, report success and quietly replace a calibration the user made on purpose — no photo taken,
 * shot counter still rising.
 *
 * Every method is main-thread only and never blocks.
 */
class ShutterDispatcher(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    /** Package of the app currently in front, or `null` when it cannot be determined. */
    fun foregroundPackage(): String? {
        val fromWindows = runCatching {
            service.windows
                ?.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root?.packageName?.toString()
        }.getOrNull()
        if (!fromWindows.isNullOrBlank()) return fromWindows
        return runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
    }

    /**
     * Fires the shutter and reports the outcome exactly once. The node paths call back synchronously;
     * the gesture path calls back when the platform completes or cancels the gesture.
     */
    fun fireShutter(request: ShutterRequest, onResult: (TapOutcome) -> Unit) {
        val geometry = DisplayGeometry.current(service)
        // The point the calibration denotes, without jitter: used to decide whether the region guess
        // is talking about the same button. The tap itself re-resolves the point so jitter applies.
        val calibratedAnchor = request.calibrated?.let {
            val rect = geometry.rectFor(it, request.capturedRotation)
            geometry.clampToScreen(PointF(rect.centerX(), rect.centerY()))
        }
        val match = if (request.gestureOnly) {
            null
        } else {
            findShutterNode(request.profile, geometry, calibratedAnchor)
        }

        if (match != null && match.clickTarget != null) {
            val clicked = runCatching {
                match.clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                val centre = centreOf(match.node, geometry)
                onResult(
                    TapOutcome(
                        path = TapPath.NODE_CLICK,
                        fired = true,
                        x = centre?.x ?: -1f,
                        y = centre?.y ?: -1f,
                        matchedBy = match.matchedBy,
                    ),
                )
                return
            }
        }

        // No node, not clickable, or the click was refused: fall through to a synthetic tap.
        val nodeCentre = match?.let { centreOf(it.node, geometry) }
        val point = nodeCentre
            ?: request.calibrated?.let { geometry.pointFor(it, request.capturedRotation, random) }
        if (point == null) {
            onResult(TapOutcome(path = TapPath.NONE, fired = false, failure = FAILURE_NO_TARGET))
            return
        }
        tap(
            point = point,
            path = if (nodeCentre != null) TapPath.GESTURE_ON_NODE else TapPath.GESTURE_ON_TARGET,
            matchedBy = match?.matchedBy ?: MATCHED_CALIBRATED,
            onResult = onResult,
        )
    }

    /**
     * True while the camera app is visibly still working on a capture: one of the profile's busy
     * indicators is on screen, or a shutter is shown but disabled. The second rule is what makes this
     * work for any camera app: Pixel Camera's Astro disables its shutter (and relabels it "Zrušit
     * fotku") for the whole exposure, and a camera that will not take a tap is busy whatever it says.
     */
    fun isCameraBusy(profile: CameraProfile): Boolean = nodes().any { node ->
        profile.longExposureBusyPatterns.matchesAny(node.contentDescription) ||
            profile.longExposureBusyPatterns.matchesAny(node.text) ||
            (node.isVisibleToUser && !node.isEnabled && isShutterNode(profile, node))
    }

    private fun isShutterNode(profile: CameraProfile, node: AccessibilityNodeInfo): Boolean =
        profile.isShutterViewId(node.viewIdResourceName) ||
            CameraProfiles.generic.isShutterViewId(node.viewIdResourceName) ||
            profile.isShutterLabel(node.contentDescription)

    /** Clicks post-capture chips/previews standing between us and the next shot. Returns how many. */
    fun dismissPostCaptureUi(profile: CameraProfile): Int {
        if (profile.postCaptureDismissPatterns.isEmpty()) return 0
        var clicked = 0
        for (node in nodes()) {
            val matches = profile.postCaptureDismissPatterns.matchesAny(node.contentDescription) ||
                profile.postCaptureDismissPatterns.matchesAny(node.text)
            if (!matches || !node.isVisibleToUser) continue
            if (clickableSelfOrAncestor(node)?.performActionSafely() == true) clicked++
            if (clicked >= MAX_DISMISS_CLICKS) break
        }
        return clicked
    }

    /**
     * Opens the timer chip and picks "off" when the profile can see an armed self timer. Two taps
     * with a UI settle between them, so it reports through [onDone] rather than returning.
     */
    fun ensureSelfTimerOff(profile: CameraProfile, onDone: (Boolean) -> Unit) {
        val armed = firstMatching(profile.selfTimerOnPatterns)
        if (armed == null) {
            onDone(false)
            return
        }
        if (clickableSelfOrAncestor(armed)?.performActionSafely() != true) {
            onDone(false)
            return
        }
        handler.postDelayed({
            val off = firstMatching(profile.selfTimerOffPatterns)
            val changed = clickableSelfOrAncestor(off)?.performActionSafely() == true
            onDone(changed)
        }, UI_SETTLE_MS)
    }

    /**
     * Taps the mode chip for [modeKey] unless the app already indicates it is in that mode.
     *
     * [modeKey] is a stable key ([CameraProfile.MODE_PHOTO] and friends), never a display string:
     * chip labels are localized, so comparing them against an English literal silently did nothing
     * on a Czech phone. The profile's [CameraProfile.modeAliases] carry the per-language patterns,
     * and [CameraProfile.isModeChipFor] decides which node is that mode's chip, so an unrelated
     * control with the same words (Pixel's minibar Night Sight icon, which opens the settings sheet)
     * cannot be hit. An unknown key does nothing rather than guessing.
     */
    fun selectCameraMode(profile: CameraProfile, modeKey: String, onDone: (ModeSelection) -> Unit) {
        if (!profile.supportsMode(modeKey)) {
            Log.i(TAG, "profile ${profile.id} knows no mode '$modeKey', leaving the app alone")
            onDone(ModeSelection.UNKNOWN_MODE)
            return
        }
        fun AccessibilityNodeInfo.isChip(): Boolean =
            profile.isModeChipFor(modeKey, viewIdResourceName, contentDescription) ||
                profile.isModeChipFor(modeKey, viewIdResourceName, text)

        val alreadyThere = nodes().any { node -> node.isSelected && node.isChip() }
        if (alreadyThere) {
            onDone(ModeSelection.ALREADY)
            return
        }
        val chip = nodes().firstOrNull { node -> node.isVisibleToUser && node.isChip() }
        if (chip == null) Log.i(TAG, "no '$modeKey' chip on screen for profile ${profile.id}")
        val switched = clickableSelfOrAncestor(chip)?.performActionSafely() == true
        if (!switched) {
            onDone(ModeSelection.NOT_FOUND)
            return
        }
        handler.postDelayed({ onDone(ModeSelection.SWITCHED) }, UI_SETTLE_MS)
    }

    /** True when the app currently shows a still-photo mode indicator (empty patterns: unknown). */
    fun looksLikePhotoMode(profile: CameraProfile): Boolean {
        if (profile.photoModeIndicatorPatterns.isEmpty()) return true
        return firstMatching(profile.photoModeIndicatorPatterns) != null
    }

    // ---- recorded modes ------------------------------------------------------------------------

    /**
     * The screen of [packageName]'s window as the pure policies see it, with the real nodes beside
     * it. The app's own window rather than the active one: during a recording our overlay sits on top.
     */
    fun screen(packageName: String?): Screen {
        val geometry = DisplayGeometry.current(service)
        val nodes = nodes(rootFor(packageName))
        val bounds = Rect()
        val infos = nodes.mapIndexed { index, node ->
            runCatching { node.getBoundsInScreen(bounds) }.onFailure { bounds.setEmpty() }
            val natural = geometry.naturalBounds(bounds)
            NodeInfo(
                index = index,
                viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
                label = labelOf(node),
                className = node.className?.toString(),
                left = natural.left,
                top = natural.top,
                right = natural.right,
                bottom = natural.bottom,
                clickable = node.isClickable,
                visible = node.isVisibleToUser && !bounds.isEmpty,
                selected = node.isSelected,
            )
        }
        return Screen(geometry, nodes, infos)
    }

    /** What a finger at the current-screen pixel ([x], [y]) is on, or null when nothing identifiable. */
    fun elementAt(packageName: String, x: Float, y: Float): NodeSignature? {
        val screen = screen(packageName)
        val point = screen.geometry.naturalPoint(x, y)
        return NodeAtPoint.pick(screen.infos, point.x, point.y)?.toSignature()
    }

    /** True when [signature] is the camera's shutter: recording it would take a photo at every start. */
    fun isShutterElement(profile: CameraProfile, signature: NodeSignature): Boolean =
        profile.isShutterViewId(signature.viewId) ||
            CameraProfiles.generic.isShutterViewId(signature.viewId) ||
            profile.isShutterLabel(signature.label)

    /**
     * Passes a gesture the user just made on the recording overlay through to the app below, exactly
     * where it was made. The overlay must be untouchable while this runs, or it receives it itself.
     */
    fun passThrough(gesture: RecordedGesture, onDone: (Boolean) -> Unit) {
        when (gesture) {
            is RecordedGesture.Tap -> tap(PointF(gesture.x, gesture.y), TapPath.GESTURE_ON_TARGET, null) {
                onDone(it.fired)
            }
            is RecordedGesture.Swipe ->
                swipe(gesture.points.map { PointF(it.x, it.y) }, gesture.durationMs, onDone)
        }
    }

    /**
     * Replays [mode] into the app in front, step by step, and reports once through [onDone].
     *
     * Nothing is tapped when the mode's proof is already selected. A step whose element is not on
     * screen is looked for again until its deadline (the first step until [firstStepDeadline], an
     * uptime, because a camera that was just launched draws its controls late; later steps for
     * [NEXT_STEP_WAIT_MS]) and then stops the replay: tapping the steps after a missing one would
     * act on a screen the recording never saw. [isActive] is asked before every step, so a session
     * stopped mid-replay taps nothing more.
     */
    fun replayCustomMode(
        mode: CustomCameraMode,
        firstStepDeadline: Long,
        isActive: () -> Boolean,
        onDone: (CustomReplayResult) -> Unit,
    ) {
        val start = screen(mode.packageName)
        if (ModeProofPolicy.isOn(mode.proof, start.infos)) {
            onDone(CustomReplayResult.Already)
            return
        }
        replayStep(mode, 0, firstStepDeadline, isActive, onDone)
    }

    private fun replayStep(
        mode: CustomCameraMode,
        index: Int,
        firstStepDeadline: Long,
        isActive: () -> Boolean,
        onDone: (CustomReplayResult) -> Unit,
    ) {
        if (index >= mode.steps.size) {
            handler.postDelayed({ onDone(CustomReplayResult.Switched) }, UI_SETTLE_MS)
            return
        }
        val step = mode.steps[index]
        val wait = maxOf(step.delayBeforeMs, UI_SETTLE_MS)
        handler.postDelayed({
            val deadline = if (index == 0) {
                maxOf(firstStepDeadline, SystemClock.uptimeMillis())
            } else {
                SystemClock.uptimeMillis() + NEXT_STEP_WAIT_MS
            }
            attemptStep(mode, index, deadline, firstStepDeadline, isActive, onDone)
        }, wait)
    }

    private fun attemptStep(
        mode: CustomCameraMode,
        index: Int,
        deadline: Long,
        firstStepDeadline: Long,
        isActive: () -> Boolean,
        onDone: (CustomReplayResult) -> Unit,
    ) {
        if (!isActive()) {
            onDone(CustomReplayResult.Cancelled)
            return
        }
        val screen = screen(mode.packageName)
        val sameScreen = StepMatchPolicy.sameScreen(mode, screen.geometry.naturalWidthPx, screen.geometry.naturalHeightPx)
        val next: (Boolean) -> Unit = { fired ->
            if (fired) {
                replayStep(mode, index + 1, firstStepDeadline, isActive, onDone)
            } else {
                onDone(CustomReplayResult.GestureRejected(index))
            }
        }
        when (val step = mode.steps[index]) {
            is RecordedStep.Swipe -> {
                if (!StepMatchPolicy.canSwipe(sameScreen)) {
                    onDone(CustomReplayResult.ScreenMismatch(index))
                    return
                }
                val path = step.points.map { screen.geometry.screenPoint(it.x, it.y) }
                swipe(path, step.durationMs, next)
            }
            is RecordedStep.Tap -> when (val target = StepMatchPolicy.locate(step, screen.infos, sameScreen)) {
                is StepTarget.Element -> {
                    val node = screen.nodes[target.node.index]
                    if (clickableSelfOrAncestor(node)?.performActionSafely() == true) {
                        next(true)
                    } else {
                        val centre = centreOf(node, screen.geometry)
                        if (centre == null) {
                            onDone(CustomReplayResult.StepNotFound(index))
                        } else {
                            tap(centre, TapPath.GESTURE_ON_NODE, null) { next(it.fired) }
                        }
                    }
                }
                StepTarget.Position ->
                    tap(screen.geometry.screenPoint(step.x, step.y), TapPath.GESTURE_ON_TARGET, null) {
                        next(it.fired)
                    }
                StepTarget.ScreenMismatch -> onDone(CustomReplayResult.ScreenMismatch(index))
                StepTarget.NotFound, StepTarget.Ambiguous -> {
                    if (SystemClock.uptimeMillis() < deadline) {
                        handler.postDelayed(
                            { attemptStep(mode, index, deadline, firstStepDeadline, isActive, onDone) },
                            STEP_POLL_MS,
                        )
                    } else {
                        Log.i(TAG, "recorded step ${index + 1} of '${mode.name}' not found: $target")
                        onDone(CustomReplayResult.StepNotFound(index))
                    }
                }
            }
        }
    }

    private fun rootFor(packageName: String?): AccessibilityNodeInfo? {
        if (packageName != null) {
            val own = runCatching {
                service.windows
                    ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == packageName }
                    ?.root
            }.getOrNull()
            if (own != null) return own
        }
        return runCatching { service.rootInActiveWindow }.getOrNull()
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? =
        (node.contentDescription ?: node.text)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL_CHARS)

    // ---- node search ---------------------------------------------------------------------------

    private class Match(
        val node: AccessibilityNodeInfo,
        val matchedBy: String,
        /** The node that should receive `ACTION_CLICK`, or null when nothing in the chain is clickable. */
        val clickTarget: AccessibilityNodeInfo?,
        /** Screen bounds at the time of the match; empty when they could not be read. */
        val bounds: Rect = Rect(),
    )

    /**
     * See the class doc for the order. [calibratedAnchor] is the calibrated point in current-screen
     * pixels, or null when the user calibrated nothing; it is what demotes the region heuristic from
     * "a match" to "a match only if it agrees with the user".
     */
    private fun findShutterNode(
        profile: CameraProfile,
        geometry: DisplayGeometry,
        calibratedAnchor: PointF?,
    ): Match? {
        val nodes = nodes()
        if (nodes.isEmpty()) return null

        byViewId(nodes, profile)?.let { return it }
        byLabel(nodes, profile)?.let { return it }

        val region = byRegion(nodes, geometry) ?: return null
        if (calibratedAnchor == null) return region
        val agrees = region.bounds.contains(calibratedAnchor.x.toInt(), calibratedAnchor.y.toInt())
        if (!agrees) {
            Log.i(
                TAG,
                "region guess ${region.bounds.toShortString()} does not contain the calibrated point " +
                    "${calibratedAnchor.x.toInt()},${calibratedAnchor.y.toInt()}; tapping the calibration",
            )
        }
        return region.takeIf { agrees }
    }

    private fun byViewId(nodes: List<AccessibilityNodeInfo>, profile: CameraProfile): Match? {
        val candidates = nodes.filter { profile.isShutterViewId(it.viewIdResourceName) }
        val node = candidates.firstOrNull { it.isVisibleToUser } ?: candidates.firstOrNull() ?: return null
        return Match(node, "viewId:${node.viewIdResourceName}", clickableSelfOrAncestor(node))
    }

    private fun byLabel(nodes: List<AccessibilityNodeInfo>, profile: CameraProfile): Match? {
        // contentDescription is the reliable one; text is the fallback for labelled buttons.
        val byDescription = nodes.filter { profile.isShutterLabel(it.contentDescription) }
        val byText = nodes.filter { profile.isShutterLabel(it.text) }
        val node = byDescription.firstOrNull { it.isVisibleToUser }
            ?: byText.firstOrNull { it.isVisibleToUser }
            ?: byDescription.firstOrNull()
            ?: byText.firstOrNull()
            ?: return null
        val label = node.contentDescription ?: node.text
        return Match(node, "label:$label", clickableSelfOrAncestor(node))
    }

    /**
     * Last resort: the biggest clickable thing that sits wholly inside the bottom-centre third.
     * Requiring full containment is what keeps a full-screen clickable preview out of the running.
     */
    private fun byRegion(nodes: List<AccessibilityNodeInfo>, geometry: DisplayGeometry): Match? {
        val region = geometry.shutterSearchRegion()
        val bounds = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        for (node in nodes) {
            if (!node.isClickable || !node.isVisibleToUser) continue
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty || !region.contains(bounds)) continue
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (area > bestArea) {
                bestArea = area
                best = node
            }
        }
        val node = best ?: return null
        val nodeBounds = Rect()
        runCatching { node.getBoundsInScreen(nodeBounds) }
        return Match(node, MATCHED_REGION, node, nodeBounds)
    }

    private fun nodes(
        root: AccessibilityNodeInfo? = runCatching { service.rootInActiveWindow }.getOrNull(),
    ): List<AccessibilityNodeInfo> {
        root ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val depths = ArrayDeque<Int>()
        queue.add(root)
        depths.add(0)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val node = queue.removeFirst()
            val depth = depths.removeFirst()
            out.add(node)
            if (depth >= MAX_DEPTH) continue
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.add(child)
                depths.add(depth + 1)
            }
        }
        return out
    }

    private fun firstMatching(patterns: List<Regex>): AccessibilityNodeInfo? {
        if (patterns.isEmpty()) return null
        return nodes().firstOrNull { node ->
            patterns.matchesAny(node.contentDescription) || patterns.matchesAny(node.text)
        }
    }

    /** The node itself when clickable, otherwise the nearest clickable ancestor within a few hops. */
    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node ?: return null
        var hops = 0
        while (hops <= MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = runCatching { current.parent }.getOrNull() ?: return null
            hops++
        }
        return null
    }

    private fun AccessibilityNodeInfo.performActionSafely(): Boolean =
        runCatching { performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

    private fun centreOf(node: AccessibilityNodeInfo, geometry: DisplayGeometry): PointF? {
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }.getOrNull() ?: return null
        if (bounds.isEmpty) return null
        return geometry.clampToScreen(PointF(bounds.exactCenterX(), bounds.exactCenterY()))
    }

    // ---- gesture ------------------------------------------------------------------------------

    /**
     * A single synthetic tap: pointer down at the point, up [TAP_DURATION_MS] later. A zero-length
     * stroke is exactly how the platform models a tap.
     */
    private fun tap(point: PointF, path: TapPath, matchedBy: String?, onResult: (TapOutcome) -> Unit) {
        stroke(Path().apply { moveTo(point.x, point.y) }, TAP_DURATION_MS) { fired, failure ->
            onResult(TapOutcome(path, fired, point.x, point.y, matchedBy, failure))
        }
    }

    /** One finger along [points] (current-screen pixels) over [durationMs]. */
    private fun swipe(points: List<PointF>, durationMs: Long, onDone: (Boolean) -> Unit) {
        if (points.size < 2) {
            onDone(false)
            return
        }
        val geometry = DisplayGeometry.current(service)
        val path = Path()
        points.map { geometry.clampToScreen(it) }.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        stroke(path, durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())) { fired, _ -> onDone(fired) }
    }

    private fun stroke(path: Path, durationMs: Long, onResult: (Boolean, String?) -> Unit) {
        val reported = AtomicBoolean(false)
        fun report(fired: Boolean, failure: String? = null) {
            if (reported.compareAndSet(false, true)) onResult(fired, failure)
        }

        val gesture = runCatching {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            GestureDescription.Builder().addStroke(stroke).build()
        }.getOrElse {
            Log.w(TAG, "could not build a gesture", it)
            report(false, FAILURE_GESTURE_INVALID)
            return
        }

        val accepted = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) = report(true)

                    override fun onCancelled(description: GestureDescription?) =
                        report(false, FAILURE_GESTURE_CANCELLED)
                },
                handler,
            )
        }.getOrDefault(false)

        // dispatchGesture never invokes the callback when it returns false.
        if (!accepted) report(false, FAILURE_GESTURE_REJECTED)
    }

    private val random = Random.Default

    companion object {
        private const val TAG = "ShutterDispatcher"

        /** Pointer-down to pointer-up time of the synthetic tap. */
        const val TAP_DURATION_MS = 70L

        /** Time given to the camera UI to react between two chip taps. */
        const val UI_SETTLE_MS = 350L

        const val FAILURE_NO_TARGET = "no_target"
        const val FAILURE_GESTURE_CANCELLED = "gesture_cancelled"
        const val FAILURE_GESTURE_REJECTED = "gesture_rejected"
        const val FAILURE_GESTURE_INVALID = "gesture_invalid"

        const val MATCHED_REGION = "region"
        const val MATCHED_CALIBRATED = "calibrated"

        /** How long a recorded step after the first may take to appear after the one before. */
        const val NEXT_STEP_WAIT_MS = 3_000L

        private const val STEP_POLL_MS = 400L
        private const val MAX_LABEL_CHARS = 200

        private const val MAX_NODES = 2_500
        private const val MAX_DEPTH = 40
        private const val MAX_ANCESTOR_HOPS = 3
        private const val MAX_DISMISS_CLICKS = 2
    }
}
