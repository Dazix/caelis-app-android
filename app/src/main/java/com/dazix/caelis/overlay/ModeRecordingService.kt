package com.dazix.caelis.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dazix.caelis.R
import com.dazix.caelis.domain.cameramode.ModeProofPolicy
import com.dazix.caelis.domain.cameramode.NodeInfo
import com.dazix.caelis.domain.cameramode.Point
import com.dazix.caelis.domain.cameramode.RecordedGesture
import com.dazix.caelis.domain.cameramode.RecordedStep
import com.dazix.caelis.domain.cameramode.RecordingPolicy
import com.dazix.caelis.domain.cameramode.TouchSample
import com.dazix.caelis.service.CameraProfiles
import com.dazix.caelis.service.DisplayGeometry
import com.dazix.caelis.service.ShutterAccessibilityService
import com.dazix.caelis.service.ShutterDispatcher
import kotlin.math.roundToInt

/**
 * Hosts the recording surface over the camera app, like [CalibrationOverlayService] hosts the
 * calibration one, and turns each gesture made on it into a [RecordedStep].
 *
 * The surface has to take the touch to know where it was, so the camera app never receives it
 * directly. Each finished gesture is therefore passed on: the window goes untouchable, the
 * accessibility service dispatches the same tap or swipe at the same pixels, and the window takes
 * touches again. The camera sees the gesture a moment after the finger left, which is all a mode
 * switch needs.
 *
 * What is written down for a tap is the element under the finger *before* it is passed on (once the
 * mode switched, that element may be gone). A tap on the camera's shutter is refused: replayed at
 * every session start, it would take a photo each time.
 *
 * The window is not focusable, so the camera keeps the input focus and stays the active window the
 * service reads; Back belongs to the camera app.
 */
class ModeRecordingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var surface: RecordingSurface? = null
    private var params: WindowManager.LayoutParams? = null

    private var targetPackage: String = ""
    private val steps = ArrayList<RecordedStep>()
    private var before: List<NodeInfo>? = null
    private var lastStepEndUptime = 0L
    private var passingThrough = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.ovl_permission_missing))
            dismiss()
            return START_NOT_STICKY
        }
        val pkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.isNotBlank() }
        if (pkg == null || surface != null) return START_NOT_STICKY
        targetPackage = pkg
        steps.clear()
        before = null
        lastStepEndUptime = SystemClock.uptimeMillis()
        val launched = intent.getBooleanExtra(EXTRA_TARGET_LAUNCHED, false)
        showSurface(launched)
        main.postDelayed(timeoutRunnable, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeSurface()
        main.removeCallbacksAndMessages(null)
        ModeRecordingOverlay.reportActive(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------- window plumbing

    private fun showSurface(targetLaunched: Boolean) {
        val manager = windowManager ?: run { dismiss(); return }
        val label = labelFor(targetPackage)
        val view = RecordingSurface(
            context = this,
            instruction = getString(
                if (targetLaunched) R.string.ovl_rec_instruction else R.string.ovl_rec_instruction_not_launched,
                label,
            ),
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            onGesture = ::onGesture,
            onUndo = ::undo,
            onDone = ::finish,
            onCancel = ::cancel,
        )
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // LAYOUT_IN_SCREEN + NO_LIMITS: raw touch coordinates are the pixels a dispatched gesture
            // is aimed at. NOT_FOCUSABLE: the camera keeps the focus and stays the active window.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { manager.addView(view, layout) }.isFailure) {
            toast(getString(R.string.ovl_permission_missing))
            dismiss()
            return
        }
        surface = view
        params = layout
        view.setStepCount(0)
        ModeRecordingOverlay.reportActive(true)
    }

    private fun setTouchable(touchable: Boolean) {
        val layout = params ?: return
        val view = surface ?: return
        layout.flags = if (touchable) {
            layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // Android blocks a touch that passes through another app's overlay more opaque than 0.8
        // (untrusted touch occlusion), the panel's cloak relies on the same limit.
        layout.alpha = if (touchable) 1f else PASS_THROUGH_ALPHA
        runCatching { windowManager?.updateViewLayout(view, layout) }
    }

    private fun removeSurface() {
        val view = surface ?: return
        surface = null
        params = null
        runCatching { windowManager?.removeViewImmediate(view) }
    }

    private fun dismiss() {
        removeSurface()
        ModeRecordingOverlay.reportActive(false)
        main.removeCallbacksAndMessages(null)
        stopSelf()
    }

    private val timeoutRunnable = Runnable {
        toast(getString(R.string.ovl_rec_timed_out))
        cancel()
    }

    // ----------------------------------------------------------------------------------- capture

    private fun onGesture(samples: List<TouchSample>) {
        val tools = ShutterAccessibilityService.modeTools()
        if (tools == null) {
            toast(getString(R.string.ovl_rec_service_lost))
            cancel()
            return
        }
        if (passingThrough) return
        if (!RecordingPolicy.canAddStep(steps.size)) {
            toast(getString(R.string.ovl_rec_too_many_steps))
            return
        }
        val gesture = RecordingPolicy.classify(samples, surface?.touchSlop ?: return) ?: return
        val delay = RecordingPolicy.clampDelay(samples.first().timeMs - lastStepEndUptime)

        passingThrough = true
        // Untouchable first, and only then read the camera's screen and pass the gesture on. While
        // this surface takes touches it covers the camera completely, and the accessibility service
        // is not given the camera's window at all (seen on a Pixel 7 Pro: no element, no proof).
        // The window change also reaches the input system asynchronously; dispatched at once, the
        // gesture landed on this surface again.
        setTouchable(false)
        main.postDelayed({ recordAndPassOn(tools, gesture, delay) }, UNTOUCHABLE_SETTLE_MS)
    }

    private fun recordAndPassOn(tools: ShutterDispatcher, gesture: RecordedGesture, delay: Long) {
        // The screen as it was before the first step: what the proof is compared against.
        if (before == null) before = tools.screen(targetPackage).infos

        val geometry = DisplayGeometry.current(this)
        val step = when (gesture) {
            is RecordedGesture.Tap -> {
                val element = tools.elementAt(targetPackage, gesture.x, gesture.y)
                val profile = CameraProfiles.forPackage(targetPackage)
                if (element != null && tools.isShutterElement(profile, element)) {
                    toast(getString(R.string.ovl_rec_shutter_refused))
                    passingThrough = false
                    setTouchable(true)
                    return
                }
                val point = geometry.naturalPoint(gesture.x, gesture.y)
                RecordedStep.Tap(point.x, point.y, element, delay)
            }
            is RecordedGesture.Swipe -> RecordedStep.Swipe(
                points = gesture.points.map { geometry.naturalPoint(it.x, it.y).let { p -> Point(p.x, p.y) } },
                durationMs = gesture.durationMs,
                delayBeforeMs = delay,
            )
        }

        tools.passThrough(gesture) { fired ->
            // A moment more before the surface takes touches again, so the camera's own reaction to
            // the gesture is not cut short by the window coming back.
            main.postDelayed({
                passingThrough = false
                setTouchable(true)
                lastStepEndUptime = SystemClock.uptimeMillis()
                if (fired) {
                    steps += step
                    surface?.setStepCount(steps.size)
                } else {
                    Log.w(TAG, "the camera app did not receive a recorded gesture")
                    toast(getString(R.string.ovl_rec_gesture_failed))
                }
            }, PASS_THROUGH_SETTLE_MS)
        }
    }

    private fun undo() {
        if (steps.isEmpty() || passingThrough) return
        steps.removeAt(steps.lastIndex)
        surface?.setStepCount(steps.size)
        toast(getString(R.string.ovl_rec_undone))
    }

    private fun finish() {
        if (passingThrough) return
        if (steps.isEmpty()) {
            toast(getString(R.string.ovl_rec_nothing))
            return
        }
        // Read the end state the same way as the start: with the camera's window visible to the service.
        passingThrough = true
        setTouchable(false)
        main.postDelayed({ report() }, UNTOUCHABLE_SETTLE_MS)
    }

    private fun report() {
        val tools = ShutterAccessibilityService.modeTools()
        val after = tools?.screen(targetPackage)?.infos.orEmpty()
        val proof = before?.let { ModeProofPolicy.proofFrom(it, after) }
        Log.i(TAG, "recorded ${steps.size} steps in $targetPackage, proof: ${proof?.label ?: "none"}")
        val geometry = DisplayGeometry.current(this)
        ModeRecordingOverlay.reportResult(
            ModeRecording(
                packageName = targetPackage,
                steps = steps.toList(),
                proof = proof,
                naturalWidthPx = geometry.naturalWidthPx,
                naturalHeightPx = geometry.naturalHeightPx,
                serial = 0L,
            ),
        )
        toast(getString(R.string.ovl_rec_saved))
        dismiss()
        returnToApp()
    }

    private fun cancel() {
        dismiss()
        returnToApp()
    }

    /** Brings our own UI back, as calibration does; holding the overlay permission allows it. */
    private fun returnToApp() {
        val intent = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(intent) }
    }

    private fun labelFor(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "ModeRecording"
        private const val EXTRA_TARGET_PACKAGE = "com.dazix.caelis.overlay.extra.REC_TARGET_PACKAGE"
        private const val EXTRA_TARGET_LAUNCHED = "com.dazix.caelis.overlay.extra.REC_TARGET_LAUNCHED"

        /** A recording nobody finished must not sit over the camera forever. */
        private const val TIMEOUT_MS = 10 * 60 * 1_000L

        private const val PASS_THROUGH_SETTLE_MS = 120L
        private const val UNTOUCHABLE_SETTLE_MS = 100L
        private const val PASS_THROUGH_ALPHA = 0.8f

        fun startIntent(context: Context, packageName: String, targetLaunched: Boolean): Intent =
            Intent(context, ModeRecordingService::class.java).apply {
                putExtra(EXTRA_TARGET_PACKAGE, packageName)
                putExtra(EXTRA_TARGET_LAUNCHED, targetLaunched)
            }
    }
}

/**
 * A nearly clear sheet that takes the gestures, and a bar with the step count and the controls. The
 * bar starts at the top and can be dragged down by its text, out of the way of controls up there.
 */
private class RecordingSurface(
    context: Context,
    instruction: CharSequence,
    val touchSlop: Float,
    private val onGesture: (List<TouchSample>) -> Unit,
    private val onUndo: () -> Unit,
    private val onDone: () -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val status: TextView
    private val bar: LinearLayout
    private val margin = dp(16f)
    private var insetTop = 0

    init {
        setBackgroundColor(SCRIM)
        addView(TraceView(context, onGesture), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(CARD)
            }
        }
        val text = TextView(context).apply {
            this.text = instruction
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        status = TextView(context).apply {
            setTextColor(Color.WHITE)
            alpha = 0.75f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button(R.string.ovl_rec_cancel, onCancel))
            addView(button(R.string.ovl_rec_undo, onUndo))
            addView(button(R.string.ovl_rec_done, onDone))
        }
        bar.addView(text)
        bar.addView(status, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4f) })
        bar.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f) })
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                leftMargin = margin
                rightMargin = margin
                topMargin = margin
            },
        )
        // Dragging the text moves the bar; its own touches are never recorded.
        var grabY = 0f
        var grabTop = 0
        val drag = OnTouchListener { _, event ->
            val lp = bar.layoutParams as LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabY = event.rawY
                    grabTop = lp.topMargin
                }
                MotionEvent.ACTION_MOVE -> {
                    val max = (height - bar.height - margin).coerceAtLeast(insetTop + margin)
                    lp.topMargin = (grabTop + (event.rawY - grabY).roundToInt()).coerceIn(insetTop + margin, max)
                    bar.layoutParams = lp
                }
            }
            true
        }
        text.setOnTouchListener(drag)
        status.setOnTouchListener(drag)
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val lp = bar.layoutParams as LayoutParams
            if (lp.topMargin < bars.top + margin) lp.topMargin = bars.top + margin
            insetTop = bars.top
            view.requestLayout()
            insets
        }
    }

    fun setStepCount(count: Int) {
        status.text = context.resources.getQuantityString(R.plurals.ovl_rec_steps, count, count)
    }

    private fun button(text: Int, onClick: () -> Unit) = Button(context).apply {
        setText(text)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val SCRIM = 0x1A000000
        private const val CARD = 0xE6101014.toInt()
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

/** Collects one finger's samples in raw display pixels and draws its trace while it is down. */
private class TraceView(
    context: Context,
    private val onGesture: (List<TouchSample>) -> Unit,
) : View(context) {

    private val samples = ArrayList<TouchSample>()
    private val trace = Path()
    private var tracing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                samples.clear()
                trace.reset()
                trace.moveTo(event.x, event.y)
                tracing = true
                samples += TouchSample(event.rawX, event.rawY, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> if (tracing) {
                // Historical samples: a fast flick delivers several per event, and its path is them.
                val offsetX = event.rawX - event.x
                val offsetY = event.rawY - event.y
                for (h in 0 until event.historySize) {
                    samples += TouchSample(event.getHistoricalX(h) + offsetX, event.getHistoricalY(h) + offsetY, event.getHistoricalEventTime(h))
                }
                samples += TouchSample(event.rawX, event.rawY, event.eventTime)
                trace.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> if (tracing) {
                tracing = false
                samples += TouchSample(event.rawX, event.rawY, event.eventTime)
                trace.reset()
                onGesture(samples.toList())
            }
            // A second finger or the system taking the gesture: nothing is recorded.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                tracing = false
                trace.reset()
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tracing) canvas.drawPath(trace, paint)
    }
}
