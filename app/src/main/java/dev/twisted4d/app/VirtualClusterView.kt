package dev.twisted4d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * One touch-operable control cluster for the portrait virtual controller (see
 * `future_virtual_stick_control`/the portrait-controller branch's design discussion) -- a
 * self-contained, fixed-width block: a top row of 2 small buttons, a shoulder row of 2 buttons
 * directly below (both rows sharing the exact same width/gap, so their edges line up), then
 * either an analog stick or a Y/X/B/A-style face-button diamond filling the remaining width
 * below that. Deliberately drawn in the same "white/blue outline, no button chrome" style
 * [GamepadOverlayView] already uses, so a real gamepad's HUD and these touch equivalents read as
 * the same visual language.
 *
 * Two of these (one per hand) make up the whole portrait control bar -- see
 * `MainActivity.virtualClusterViews` for how they're wired to the exact same functions a real
 * gamepad button press already calls, so there's only ever one implementation of what a given
 * button *does*, just two ways to trigger it. Deliberately a fixed-width, self-contained block
 * (not stretched to fill its container) so the identical view can be repositioned into a
 * landscape side-margin later without changing its own internal layout at all.
 */
class VirtualClusterView(
    context: Context,
    private val topLeftLabel: String,
    private val topRightLabel: String,
    private val onTopLeftTap: () -> Unit,
    private val onTopRightTap: () -> Unit,
    private val shoulderLeftLabel: String,
    private val shoulderRightLabel: String,
    private val onShoulderLeftTap: () -> Unit,
    private val onShoulderRightTap: () -> Unit,
    private val mainControl: MainControl,
    // Optional true hold-state pairing for onTop*/onShoulder*Tap above -- default no-op, since
    // every one of these 4 pills except Select (see MainActivity.virtualClusterLeft) only cares
    // about the instant it's pressed, same as a real gamepad button tap. Select needs both halves
    // because it's a *modifier*: MainActivity.handleRotationButton/handleNavigateButton read
    // GamepadVisualState.selectHeld live while a twist/undo button is pressed, so the virtual
    // Select button has to actually hold that flag true for as long as a finger stays on it, not
    // just pulse it once like every other button here does.
    private val onTopLeftRelease: () -> Unit = {},
    private val onTopRightRelease: () -> Unit = {},
    private val onShoulderLeftRelease: () -> Unit = {},
    private val onShoulderRightRelease: () -> Unit = {},
) : View(context) {

    /** [Stick]'s [onChanged] fires continuously while dragging (and once more with (0,0) on
     * release) -- same -1..1 range [GamepadInputHandler]'s real stick already reports, so callers
     * can feed it into [HypercubeRenderer.updateCell4Selection] identically either way.
     * [FaceDiamond]'s 4 taps are discrete, one call per press, matching a real face button. */
    sealed class MainControl {
        class Stick(val onChanged: (x: Float, y: Float) -> Unit) : MainControl()
        class FaceDiamond(
            val topLabel: String,
            val onTopTap: () -> Unit,
            val leftLabel: String,
            val onLeftTap: () -> Unit,
            val rightLabel: String,
            val onRightTap: () -> Unit,
            val bottomLabel: String,
            val onBottomTap: () -> Unit,
        ) : MainControl()
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(150, 150, 165, 200)
    }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 79, 195, 247)
    }
    private val stickDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 255, 255, 255)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 220, 225, 235)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    // Recomputed in onSizeChanged, reused by both onDraw and touch hit-testing so the two can
    // never disagree about where a control actually is.
    private val topLeftRect = RectF()
    private val topRightRect = RectF()
    private val shoulderLeftRect = RectF()
    private val shoulderRightRect = RectF()
    private val mainRect = RectF()
    private var pillCornerRadius = 0f

    // Which pointer (MotionEvent pointer ID, not index -- indices shift as fingers lift) is
    // currently dragging the stick, if any; -1 when the stick is untouched. Only meaningful for
    // MainControl.Stick.
    private var stickPointerId = -1

    // Discrete buttons currently held by some pointer, purely for the lit/unlit redraw -- the
    // *action* already fired on ACTION_DOWN, this is just visual feedback.
    private val heldRegions = HashSet<String>()

    // Which pointer (by ID) is currently holding which of the 4 discrete pill regions --
    // needed (unlike heldRegions above) to fire the *correct* onXRelease when that specific
    // pointer lifts, rather than a blanket "any pointer went up" clear. Only populated for
    // the 4 pill regions, never "faceTop"/etc. -- the face diamond's 4 sub-buttons don't have
    // a release callback to fire.
    private val pointerRegions = HashMap<Int, String>()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec).toFloat()
        setMeasuredDimension(w.toInt(), heightForWidth(w).toInt())
    }

    companion object {
        /** Mirrors onMeasure's own stacking exactly (see its former inline comment, preserved
         * here: two pills per row with a gap between them, then the same gap again before a
         * square main control -- not stretched to fill whatever height is available, that's the
         * whole point of this cluster's fixed-width-driven layout). Exposed so MainActivity can
         * compute a cluster's rendered height *before* it's actually measured/laid out -- e.g. to
         * size the portrait Start Menu's bounds to stop exactly above the control bar (see
         * MainActivity.menuOverlayParams's doc), without depending on this view's own
         * asynchronous layout pass timing. */
        fun heightForWidth(width: Float): Float {
            val gap = width * 0.05f
            val pillH = (width - gap) / 2f / 2.3f
            return pillH + gap + pillH + gap + width
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w.toFloat()
        val gap = width * 0.05f
        val pillW = (width - gap) / 2f
        val pillH = pillW / 2.3f
        pillCornerRadius = pillH * 0.22f

        topLeftRect.set(0f, 0f, pillW, pillH)
        topRightRect.set(pillW + gap, 0f, width, pillH)
        val shoulderTop = pillH + gap
        shoulderLeftRect.set(0f, shoulderTop, pillW, shoulderTop + pillH)
        shoulderRightRect.set(pillW + gap, shoulderTop, width, shoulderTop + pillH)
        val mainTop = shoulderTop + pillH + gap
        mainRect.set(0f, mainTop, width, mainTop + width)
    }

    override fun onDraw(canvas: Canvas) {
        labelPaint.textSize = width * 0.11f

        drawPill(canvas, topLeftRect, topLeftLabel, "topLeft" in heldRegions)
        drawPill(canvas, topRightRect, topRightLabel, "topRight" in heldRegions)
        drawPill(canvas, shoulderLeftRect, shoulderLeftLabel, "shoulderLeft" in heldRegions)
        drawPill(canvas, shoulderRightRect, shoulderRightLabel, "shoulderRight" in heldRegions)

        when (val control = mainControl) {
            is MainControl.Stick -> drawStick(canvas, control)
            is MainControl.FaceDiamond -> drawFaceDiamond(canvas, control)
        }
    }

    private fun drawPill(canvas: Canvas, r: RectF, label: String, held: Boolean) {
        canvas.drawRoundRect(r, pillCornerRadius, pillCornerRadius, if (held) litPaint else outlinePaint)
        canvas.drawText(label, r.centerX(), r.centerY() + labelPaint.textSize * 0.35f, labelPaint)
    }

    private fun drawStick(canvas: Canvas, control: MainControl.Stick) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val r = mainRect.width() / 2f
        canvas.drawCircle(cx, cy, r, outlinePaint)
        val (dotX, dotY) = currentStickOffset
        canvas.drawCircle(cx + dotX * r * 0.7f, cy + dotY * r * 0.7f, r * 0.22f, stickDotPaint)
    }

    // Kept purely so onDraw can redraw the dot at its live dragged position -- updated in
    // onTouchEvent right before invalidate(), not read anywhere control logic actually cares
    // about (the real -1..1 value goes straight to the MainControl.Stick.onChanged callback).
    private var currentStickOffset = 0f to 0f

    private fun drawFaceDiamond(canvas: Canvas, control: MainControl.FaceDiamond) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val spread = mainRect.width() * 0.27f
        val r = mainRect.width() * 0.16f
        drawFaceButton(canvas, cx, cy - spread, r, control.topLabel, "faceTop" in heldRegions)
        drawFaceButton(canvas, cx - spread, cy, r, control.leftLabel, "faceLeft" in heldRegions)
        drawFaceButton(canvas, cx + spread, cy, r, control.rightLabel, "faceRight" in heldRegions)
        drawFaceButton(canvas, cx, cy + spread, r, control.bottomLabel, "faceBottom" in heldRegions)
    }

    private fun drawFaceButton(canvas: Canvas, cx: Float, cy: Float, r: Float, label: String, held: Boolean) {
        canvas.drawCircle(cx, cy, r, if (held) litPaint else outlinePaint)
        canvas.drawText(label, cx, cy + labelPaint.textSize * 0.35f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handlePointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (event.getPointerId(i) == stickPointerId) {
                        updateStick(event.getX(i), event.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                handlePointerUp(event.getPointerId(index))
            }
            MotionEvent.ACTION_CANCEL -> {
                stickPointerId = -1
                currentStickOffset = 0f to 0f
                (mainControl as? MainControl.Stick)?.onChanged?.invoke(0f, 0f)
                // Fire release for every still-claimed pill, same as a real gamepad losing its
                // input connection mid-hold -- without this, a cancelled Select touch (e.g. an
                // incoming call interrupting the gesture) would leave GamepadVisualState.selectHeld
                // stuck true forever, silently turning every future twist button into a
                // whole-room rotation.
                pointerRegions.values.forEach(::fireRelease)
                pointerRegions.clear()
                heldRegions.clear()
                invalidate()
            }
        }
        return true
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        when {
            topLeftRect.contains(x, y) -> claimPill(pointerId, "topLeft", onTopLeftTap)
            topRightRect.contains(x, y) -> claimPill(pointerId, "topRight", onTopRightTap)
            shoulderLeftRect.contains(x, y) -> claimPill(pointerId, "shoulderLeft", onShoulderLeftTap)
            shoulderRightRect.contains(x, y) -> claimPill(pointerId, "shoulderRight", onShoulderRightTap)
            mainRect.contains(x, y) -> when (val control = mainControl) {
                is MainControl.Stick -> {
                    stickPointerId = pointerId
                    updateStick(x, y)
                }
                is MainControl.FaceDiamond -> handleFaceDiamondDown(control, x, y)
            }
        }
        invalidate()
    }

    private fun claimPill(pointerId: Int, region: String, onPress: () -> Unit) {
        pointerRegions[pointerId] = region
        heldRegions.add(region)
        onPress()
    }

    private fun fireRelease(region: String) {
        when (region) {
            "topLeft" -> onTopLeftRelease()
            "topRight" -> onTopRightRelease()
            "shoulderLeft" -> onShoulderLeftRelease()
            "shoulderRight" -> onShoulderRightRelease()
        }
    }

    private fun handleFaceDiamondDown(control: MainControl.FaceDiamond, x: Float, y: Float) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val dx = x - cx
        val dy = y - cy
        // Whichever of the 4 cardinal directions the touch is closest to -- a plain nearest-of-4
        // by angle, not exact per-circle hit-testing, so a tap anywhere in the diamond's quadrant
        // (not just precisely on a drawn circle) still registers -- more forgiving for a thumb.
        if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx > 0) { heldRegions.add("faceRight"); control.onRightTap() } else { heldRegions.add("faceLeft"); control.onLeftTap() }
        } else {
            if (dy > 0) { heldRegions.add("faceBottom"); control.onBottomTap() } else { heldRegions.add("faceTop"); control.onTopTap() }
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val r = mainRect.width() / 2f
        // A slightly larger drag radius than the drawn circle before saturating -- a bare thumb
        // rarely lands exactly on the visual edge, so this gives a little slack before clamping
        // to a full deflection rather than requiring pixel-precise reach to the rim.
        val dragR = r * 1.4f
        var nx = (x - cx) / dragR
        var ny = (y - cy) / dragR
        val mag = hypot(nx, ny)
        if (mag > 1f) { nx /= mag; ny /= mag }
        currentStickOffset = nx to ny
        (mainControl as MainControl.Stick).onChanged(nx, ny)
        invalidate()
    }

    private fun handlePointerUp(pointerId: Int) {
        if (pointerId == stickPointerId) {
            stickPointerId = -1
            currentStickOffset = 0f to 0f
            (mainControl as MainControl.Stick).onChanged(0f, 0f)
        }
        // Only the exact pointer that claimed a pill releases it -- important for a
        // hold-style button like Select: a *different* finger lifting elsewhere (e.g. the
        // stick, or a face button) must not also fire Select's release.
        pointerRegions.remove(pointerId)?.let { region ->
            heldRegions.remove(region)
            fireRelease(region)
        }
        // Face-diamond taps have no per-pointer tracking (no release action to fire), so just
        // drop any region that isn't still actively claimed by some other, still-down pointer --
        // NOT a blanket clear: with Select held by one finger while a twist button on the other
        // side of the *same* cluster is tapped by another (the modifier's whole reason to exist),
        // lifting the twist-button finger must not also visually un-light Select underneath it.
        heldRegions.removeAll { it !in pointerRegions.values }
        invalidate()
    }
}
