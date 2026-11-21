package com.dazix.caelis.service

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.dazix.caelis.domain.TapTarget
import kotlin.random.Random

/**
 * Immutable snapshot of the display, and the single place where [TapTarget] fractions become pixels.
 *
 * ### Coordinate spaces
 * A [TapTarget]'s fractions are stored in **natural space** — the fractions the point would have on
 * the display in [Surface.ROTATION_0] — so one calibration survives every rotation. Calibration UI
 * captures a touch in *current screen* space and calls [toNatural] before saving; the dispatcher
 * calls [pointFor]/[rectFor], which rotate natural space into the current screen and only then
 * multiply by the current pixel size. A point calibrated in portrait therefore still lands on the
 * shutter after the user rotates to landscape, and it lands correctly on a different-resolution
 * display too.
 *
 * A target captured in some other rotation (for example one loaded from a per-rotation calibration
 * slot) can be resolved by passing that rotation as `capturedRotation`.
 *
 * [Surface] rotation constants are the small integers 0..3, so `(a - b + 4) % 4` is the number of
 * quarter turns between two rotations, which is all the transform needs.
 */
class DisplayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val rotation: Int,
) {

    /** Width of the display as it is in [Surface.ROTATION_0]. */
    val naturalWidthPx: Int get() = if (isQuarterTurned) heightPx else widthPx

    /** Height of the display as it is in [Surface.ROTATION_0]. */
    val naturalHeightPx: Int get() = if (isQuarterTurned) widthPx else heightPx

    private val isQuarterTurned: Boolean
        get() = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    /** The rect of the whole current screen. */
    fun screenBounds(): Rect = Rect(0, 0, widthPx, heightPx)

    /**
     * Bottom-centre third of the current screen: the last-resort search region for a shutter node.
     * One method so the heuristic can be retuned in a single place.
     */
    fun shutterSearchRegion(): Rect = Rect(
        widthPx / 3,
        heightPx * 2 / 3,
        widthPx - widthPx / 3,
        heightPx,
    )

    /** Converts a target whose fractions are in *current screen* space into natural space. */
    fun toNatural(target: TapTarget): TapTarget = rotateTarget(target, quarterTurns(rotation, Surface.ROTATION_0))

    /** Converts a natural-space target into *current screen* space fractions. */
    fun toScreenFractions(target: TapTarget, capturedRotation: Int = Surface.ROTATION_0): TapTarget =
        rotateTarget(target, quarterTurns(capturedRotation, rotation))

    /** Current-screen pixel rect of [target]. A point target yields a zero-size rect at the point. */
    fun rectFor(target: TapTarget, capturedRotation: Int = Surface.ROTATION_0): RectF {
        val screen = toScreenFractions(target, capturedRotation)
        val centreX = screen.xFraction * widthPx
        val centreY = screen.yFraction * heightPx
        val halfWidth = screen.widthFraction * widthPx / 2f
        val halfHeight = screen.heightFraction * heightPx / 2f
        return RectF(
            centreX - halfWidth,
            centreY - halfHeight,
            centreX + halfWidth,
            centreY + halfHeight,
        )
    }

    /**
     * The pixel to tap for [target]: the centre for a point target and for a rect target, or a
     * uniformly random pixel inside the rect when [TapTarget.jitter] is on. Always inside the screen.
     */
    fun pointFor(
        target: TapTarget,
        capturedRotation: Int = Surface.ROTATION_0,
        random: Random = Random.Default,
    ): PointF {
        val rect = rectFor(target, capturedRotation)
        val point = if (target.isRect && target.jitter) {
            PointF(
                rect.left + random.nextFloat() * rect.width(),
                rect.top + random.nextFloat() * rect.height(),
            )
        } else {
            PointF(rect.centerX(), rect.centerY())
        }
        return clampToScreen(point)
    }

    /** Current-screen fractions of a current-screen pixel; the inverse of the multiply in [rectFor]. */
    fun fractionsOf(x: Float, y: Float): PointF = PointF(
        if (widthPx > 0) x / widthPx else 0f,
        if (heightPx > 0) y / heightPx else 0f,
    )

    /** Natural-space fractions of a current-screen pixel. */
    fun naturalPoint(x: Float, y: Float): PointF {
        val screen = fractionsOf(x, y)
        val natural = toNatural(TapTarget(screen.x, screen.y))
        return PointF(natural.xFraction, natural.yFraction)
    }

    /** Natural-space fractions (left, top, right, bottom) of a current-screen pixel rect. */
    fun naturalBounds(rect: Rect): RectF {
        val centre = fractionsOf(rect.exactCenterX(), rect.exactCenterY())
        val size = fractionsOf(rect.width().toFloat(), rect.height().toFloat())
        val natural = toNatural(TapTarget(centre.x, centre.y, size.x, size.y))
        return RectF(
            natural.xFraction - natural.widthFraction / 2f,
            natural.yFraction - natural.heightFraction / 2f,
            natural.xFraction + natural.widthFraction / 2f,
            natural.yFraction + natural.heightFraction / 2f,
        )
    }

    /** The current-screen pixel of a natural-space point, inside the screen. */
    fun screenPoint(naturalX: Float, naturalY: Float): PointF = pointFor(TapTarget(naturalX, naturalY))

    /** Keeps a gesture coordinate strictly inside the display, which the gesture API requires. */
    fun clampToScreen(point: PointF): PointF = PointF(
        point.x.coerceIn(0f, (widthPx - 1).coerceAtLeast(0).toFloat()),
        point.y.coerceIn(0f, (heightPx - 1).coerceAtLeast(0).toFloat()),
    )

    private fun rotateTarget(target: TapTarget, turns: Int): TapTarget {
        val rotated = rotateFractions(target.xFraction, target.yFraction, turns)
        val swap = turns % 2 != 0
        return target.copy(
            xFraction = rotated.x,
            yFraction = rotated.y,
            widthFraction = if (swap) target.heightFraction else target.widthFraction,
            heightFraction = if (swap) target.widthFraction else target.heightFraction,
        )
    }

    companion object {

        /** Reads the live display size and rotation; never throws. */
        fun current(context: Context): DisplayGeometry {
            val metrics = context.resources.displayMetrics
            val bounds = runCatching {
                context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            }.getOrNull()
            val width = bounds?.width()?.takeIf { it > 0 } ?: metrics.widthPixels
            val height = bounds?.height()?.takeIf { it > 0 } ?: metrics.heightPixels
            return DisplayGeometry(width, height, currentRotation(context))
        }

        fun currentRotation(context: Context): Int =
            runCatching { context.display.rotation }.getOrNull()
                ?: runCatching {
                    context.getSystemService(DisplayManager::class.java)
                        ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                }.getOrNull()
                ?: Surface.ROTATION_0

        /** Quarter turns from rotation [from] to rotation [to]; [Surface] constants are 0..3. */
        fun quarterTurns(from: Int, to: Int): Int = ((to - from) % 4 + 4) % 4

        /**
         * Rotates a unit-square fraction pair by [turns] quarter turns. Derived from the display
         * convention that in [Surface.ROTATION_90] the visible top-left corner is the natural
         * top-right one, so `(x, y) -> (y, 1 - x)`.
         */
        fun rotateFractions(x: Float, y: Float, turns: Int): PointF = when (((turns % 4) + 4) % 4) {
            1 -> PointF(y, 1f - x)
            2 -> PointF(1f - x, 1f - y)
            3 -> PointF(1f - y, x)
            else -> PointF(x, y)
        }
    }
}
