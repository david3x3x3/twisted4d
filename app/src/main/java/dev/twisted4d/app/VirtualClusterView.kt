package dev.twisted4d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    // topLeft/topRightCaption default null -- a null caption means single-line rendering (just
    // About, whose native name and function are the same thing, nothing to split). Non-null for
    // Select/Start/L3-C, matching the shoulder pills' native-name-caption-above-function-label
    // treatment for visual consistency (2026-09-07 feedback) -- Select/Start's *function* never
    // itself changes ("Mod1"/"Menu"), so a plain fixed String suffices there, unlike L3/C's, which
    // previews the Select-held STICK<->RKT toggle or a "move to I" rotation (see MainActivity's
    // l3CLabel) and so needs a supplier.
    private val topLeftLabel: String,
    private val topLeftCaption: String? = null,
    private val topRightLabel: () -> String,
    private val topRightCaption: String? = null,
    private val onTopLeftTap: () -> Unit,
    private val onTopRightTap: () -> Unit,
    // Live caption + live function-label suppliers for the two shoulder pills (L1/L2 on the left
    // cluster, R1/R2 on the right). The *function* at each screen position is fixed (left always
    // fires BUMPER_L/BUMPER_R, right always fires TRIGGER_L/TRIGGER_R -- see MainActivity's
    // l1L2Label/rotationButtonLiveLabel callers), so a Z Dir Left/Right swap never moves what a
    // press *does*; it only moves which native-name caption ("L1" vs "L2", "R1" vs "R2") is shown
    // at which position -- hence the caption is a supplier too, not a fixed string like
    // topLeft/topRightLabel (About/Start/Select/L3-C, which never swap at all).
    private val shoulderLeftCaption: () -> String,
    private val shoulderLeftLabel: () -> FaceButtonContent,
    private val onShoulderLeftTap: () -> Unit,
    private val shoulderRightCaption: () -> String,
    private val shoulderRightLabel: () -> FaceButtonContent,
    private val onShoulderRightTap: () -> Unit,
    mainControl: MainControl,
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
    // Real-gamepad highlight hooks (added alongside always-visible virtual controls retiring
    // GamepadOverlayView from 4D mode) -- read GamepadVisualState directly so a real controller
    // press lights up the matching virtual button too, not just a touch. Default no-op for
    // About/Start, which have no real-gamepad equivalent tracked in GamepadVisualState (same
    // omission GamepadOverlayView itself always had).
    private val topRightRealHeld: () -> Boolean = { false },
    private val shoulderLeftRealHeld: () -> Boolean = { false },
    private val shoulderRightRealHeld: () -> Boolean = { false },
) : View(context) {

    /** [Stick]'s [onChanged] fires continuously while dragging (and once more with (0,0) on
     * release) -- same -1..1 range [GamepadInputHandler]'s real stick already reports, so callers
     * can feed it into [HypercubeRenderer.updateCell4Selection] identically either way.
     * [FaceDiamond]'s 4 taps are discrete, one call per press, matching a real face button.
     * [DPad] is the same 4-discrete-direction shape as [FaceDiamond] (same positions, same hit-
     * testing) but drawn with arrow glyphs instead of letter labels and wired to directions
     * instead of named buttons -- used in place of [Stick] for the left cluster while RKT mode is
     * active, since RKT has no cell-selection role for a stick to drive (see
     * MainActivity.applyInputModeToVirtualController's doc). */
    /** What a face-diamond or R1/R2 shoulder-pill button shows for its current function -- either
     * plain text (room-rotation labels, config-mapped notation, Undo/Redo/Filter/IF-IF', Mod1/
     * Mod2/Menu) or [Icon], the isometric cube-with-wrapping-arrow drawn by [TwistIcon] (only ever
     * used for the live per-cell-twist case -- see MainActivity.rotationButtonContent's doc for
     * exactly when, per the 2026-09-07 product decision to scope the icon to that one case and
     * leave every other text label as-is). */
    sealed class FaceButtonContent {
        data class Text(val text: String) : FaceButtonContent()
        data class Icon(val axis: TwistIcon.Axis, val reverse: Boolean) : FaceButtonContent()
    }

    sealed class MainControl {
        class Stick(val onChanged: (x: Float, y: Float) -> Unit) : MainControl()

        /** [topCaption]/etc. are suppliers for the native button name at that *position*
         * (Y/X/B/A) -- a lambda, not a fixed string, since 2026-09-14: a Nintendo-layout
         * controller has different letters printed at these same 4 positions (X top, Y left, A
         * right, B bottom, vs. Xbox's Y/X/B/A), so this must be re-evaluated live against
         * PerControllerSettings.current()?.nintendoLayout the same way [shoulderLeftCaption]/etc.
         * already re-evaluate against Z Dir. [topLabel]/etc. are suppliers re-invoked every
         * repaint for the currently-active function (see MainActivity.rotationButtonContent and
         * friends -- the precedence is: a menu open (Confirm/Back), else Select-held room
         * rotation, then a selected cell's live twist, then the button-config's Button-C/plain
         * mapping). [topRealHeld]/etc. mirror a real gamepad press of the same *position* (see
         * GamepadVisualState's doc for why resolving which raw button belongs at which position
         * is the caller's job, same reasoning as the caption) so the highlight isn't touch-only. */
        class FaceDiamond(
            val topCaption: () -> String,
            val topLabel: () -> FaceButtonContent,
            val onTopTap: () -> Unit,
            val topRealHeld: () -> Boolean = { false },
            val leftCaption: () -> String,
            val leftLabel: () -> FaceButtonContent,
            val onLeftTap: () -> Unit,
            val leftRealHeld: () -> Boolean = { false },
            val rightCaption: () -> String,
            val rightLabel: () -> FaceButtonContent,
            val onRightTap: () -> Unit,
            val rightRealHeld: () -> Boolean = { false },
            val bottomCaption: () -> String,
            val bottomLabel: () -> FaceButtonContent,
            val onBottomTap: () -> Unit,
            val bottomRealHeld: () -> Boolean = { false },
        ) : MainControl()

        /** [upLabel]/etc. are fixed (RKT-mode-only, never change while the D-pad is showing --
         * see RktMoveLabels); no separate caption, the arrow glyph already stands in for a native
         * name. [upRealHeld]/etc. mirror a real D-pad press. */
        class DPad(
            val upLabel: String,
            val onUpTap: () -> Unit,
            val upRealHeld: () -> Boolean = { false },
            val leftLabel: String,
            val onLeftTap: () -> Unit,
            val leftRealHeld: () -> Boolean = { false },
            val rightLabel: String,
            val onRightTap: () -> Unit,
            val rightRealHeld: () -> Boolean = { false },
            val downLabel: String,
            val onDownTap: () -> Unit,
            val downRealHeld: () -> Boolean = { false },
        ) : MainControl()
    }

    /** Which of the diamond's 4 positions a touch at (x, y) is closest to -- shared by
     * [FaceDiamond] and [DPad], which are positionally identical, just drawn/labeled
     * differently. */
    private enum class DiamondDirection { UP, LEFT, RIGHT, DOWN }

    // Mutable (not the constructor's fixed val this started as) so MainActivity can swap the left
    // cluster between Stick and DPad when RKT mode toggles, without tearing down and recreating
    // the whole view (see applyInputModeToVirtualController's doc). Resets any in-flight stick
    // drag on every change -- a gesture that started against the old control shouldn't silently
    // keep driving whatever replaced it.
    var mainControl: MainControl = mainControl
        set(value) {
            stickPointerId = -1
            currentStickOffset = 0f to 0f
            field = value
            invalidate()
        }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EDGE_INSET * 2f
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
    // Native-button-name caption, drawn smaller/dimmer than labelPaint's in-button function label
    // -- above each face-diamond button and as the top line of each dynamic shoulder pill.
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 170, 180, 200)
        textAlign = Paint.Align.CENTER
    }
    // Smaller than labelPaint so a caption + function label both fit inside one shoulder pill's
    // short height -- see heightForWidth's pillH; topLeft/topRightLabel (About/Start/Select/L3-C)
    // have no caption to share space with, so they keep using labelPaint at full size.
    private val pillLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 220, 225, 235)
        textAlign = Paint.Align.CENTER
    }
    // The 8 fixed compass-direction cell labels drawn around the stick's circumference.
    private val stickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 210, 220, 235)
        textAlign = Paint.Align.CENTER
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 220, 225, 235)
    }
    private val arrowPath = Path()
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

    // Self-driven ~30fps repaint (same idiom as GamepadOverlayView, which this view's always-
    // visible role now folds in) -- needed because function labels/highlighting can change from
    // real-gamepad state or held modifiers with no other UI-thread trigger to invalidate from
    // (a touch-driven invalidate() alone, as before this feature, only ever covered this view's
    // own touch events).
    private val repaintHandler = Handler(Looper.getMainLooper())
    private val repaintTick = object : Runnable {
        override fun run() {
            invalidate()
            repaintHandler.postDelayed(this, 33L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repaintHandler.post(repaintTick)
    }

    override fun onDetachedFromWindow() {
        repaintHandler.removeCallbacks(repaintTick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec).toFloat()
        setMeasuredDimension(w.toInt(), heightForWidth(w).toInt())
    }

    companion object {
        // Half of outlinePaint's stroke width -- Paint.Style.STROKE draws centered on the path,
        // so a rect whose edge sits exactly on this view's own boundary gets half that stroke
        // clipped away by the view's own bounds (confirmed via emulator screenshot: the top row's
        // outline looked cut off right where the puzzle display ends and the control bar begins).
        // Every rect in onSizeChanged is inset by this on all 4 sides so no stroke ever sits
        // exactly on an edge. A plain constant (not derived from outlinePaint.strokeWidth) since
        // heightForWidth below needs it before any instance -- and therefore any Paint -- exists.
        private const val EDGE_INSET = 1.5f

        // How wide each cluster is, as a fraction of the full screen width -- shared by
        // MainActivity (sizing/positioning the actual views, and insetting the portrait Start
        // Menu above them) and HypercubeRenderer (centering the puzzle's own viewport in the
        // leftover space above the control bar). All three need to agree on the same fraction or
        // their reserved-space math would drift out of sync with each other.
        const val WIDTH_FRACTION_OF_SCREEN = 0.45f

        // Wedge 0..7 room-slot letters, mirroring HypercubeRenderer.updateCell4Selection's own
        // wedge table exactly (~HypercubeRenderer.kt:1308-1317) -- wedge 0 = right = I's slot,
        // going counterclockwise. Room-relative (fixed for the app's lifetime), NOT the live
        // native occupant (nativeCellInRoomSlot), which would drift as the puzzle is scrambled --
        // see HypercubeRenderer.effectiveRoomCell's doc for why room identity is what a human
        // reads off a fixed control, not whichever piece currently happens to sit there.
        private val STICK_WEDGE_LABELS = listOf("I", "B", "U", "L", "O", "F", "D", "R")

        /** Mirrors onSizeChanged's own stacking exactly (see its comments there: two pills per
         * row with a gap between them, then the same gap again before a square main control --
         * not stretched to fill whatever height is available, that's the whole point of this
         * cluster's fixed-width-driven layout), including the EDGE_INSET margin on every side.
         * Exposed so MainActivity can compute a cluster's rendered height *before* it's actually
         * measured/laid out -- e.g. to size the portrait Start Menu's bounds to stop exactly above
         * the control bar (see MainActivity.menuOverlayParams's doc), without depending on this
         * view's own asynchronous layout pass timing. */
        fun heightForWidth(width: Float): Float {
            val innerWidth = width - EDGE_INSET * 2f
            val gap = innerWidth * 0.05f
            // 1.7f (was 2.3f) -- the shoulder pills now carry 2 lines (native-name caption +
            // dynamic function label, e.g. "L1"/"Filter−"), which a 2.3f-ratio pill was too short
            // for -- confirmed via emulator screenshot (the caption rendered clipped above the
            // pill's own top edge). Applies uniformly to all 4 pills, including the single-line
            // top row (About/Select/Start/L3-C) -- harmless there, just a little extra vertical
            // centering room.
            val pillH = (innerWidth - gap) / 2f / 1.7f
            return pillH + gap + pillH + gap + innerWidth + EDGE_INSET * 2f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Every rect below is offset by EDGE_INSET on all 4 sides -- see its doc for why (an
        // outline stroke sitting exactly on the view's own boundary gets half clipped away).
        val width = w.toFloat() - EDGE_INSET * 2f
        val gap = width * 0.05f
        val pillW = (width - gap) / 2f
        val pillH = pillW / 1.7f // see heightForWidth's doc for why 1.7f, not 2.3f
        pillCornerRadius = pillH * 0.22f

        topLeftRect.set(EDGE_INSET, EDGE_INSET, EDGE_INSET + pillW, EDGE_INSET + pillH)
        topRightRect.set(EDGE_INSET + pillW + gap, EDGE_INSET, EDGE_INSET + width, EDGE_INSET + pillH)
        val shoulderTop = EDGE_INSET + pillH + gap
        shoulderLeftRect.set(EDGE_INSET, shoulderTop, EDGE_INSET + pillW, shoulderTop + pillH)
        shoulderRightRect.set(EDGE_INSET + pillW + gap, shoulderTop, EDGE_INSET + width, shoulderTop + pillH)
        val mainTop = shoulderTop + pillH + gap
        mainRect.set(EDGE_INSET, mainTop, EDGE_INSET + width, mainTop + width)
    }

    override fun onDraw(canvas: Canvas) {
        labelPaint.textSize = width * 0.11f
        captionPaint.textSize = width * 0.05f
        pillLabelPaint.textSize = width * 0.075f
        stickLabelPaint.textSize = width * 0.055f

        if (topLeftCaption != null) {
            drawShoulderPill(canvas, topLeftRect, topLeftCaption, FaceButtonContent.Text(topLeftLabel), "topLeft" in heldRegions)
        } else {
            drawPill(canvas, topLeftRect, topLeftLabel, "topLeft" in heldRegions)
        }
        if (topRightCaption != null) {
            drawShoulderPill(canvas, topRightRect, topRightCaption, FaceButtonContent.Text(topRightLabel()), ("topRight" in heldRegions) || topRightRealHeld())
        } else {
            drawPill(canvas, topRightRect, topRightLabel(), ("topRight" in heldRegions) || topRightRealHeld())
        }
        drawShoulderPill(canvas, shoulderLeftRect, shoulderLeftCaption(), shoulderLeftLabel(), ("shoulderLeft" in heldRegions) || shoulderLeftRealHeld())
        drawShoulderPill(canvas, shoulderRightRect, shoulderRightCaption(), shoulderRightLabel(), ("shoulderRight" in heldRegions) || shoulderRightRealHeld())

        when (val control = mainControl) {
            is MainControl.Stick -> drawStick(canvas, control)
            is MainControl.FaceDiamond -> drawFaceDiamond(canvas, control)
            is MainControl.DPad -> drawDpad(canvas, control)
        }
    }

    private fun drawPill(canvas: Canvas, r: RectF, label: String, held: Boolean) {
        canvas.drawRoundRect(r, pillCornerRadius, pillCornerRadius, if (held) litPaint else outlinePaint)
        // Auto-shrink-to-fit: labelPaint's size is tuned for short native names (About/Select/
        // Start/L3-C), but this pill's label can now go dynamic (e.g. L3/C previewing "RKT Mode"/
        // "Stick Mode" while Select is held) and outgrow the pill's width at that fixed size --
        // confirmed via emulator screenshot (the text visibly overflowed the pill's border).
        // Temporarily shrinks just for this call, not a permanent mutation, since labelPaint's
        // size is set once per frame in onDraw and shared by every pill/face-button draw this
        // frame.
        val maxWidth = r.width() * 0.88f
        val textWidth = labelPaint.measureText(label)
        val originalSize = labelPaint.textSize
        if (textWidth > maxWidth) labelPaint.textSize = originalSize * (maxWidth / textWidth)
        canvas.drawText(label, r.centerX(), r.centerY() + labelPaint.textSize * 0.35f, labelPaint)
        labelPaint.textSize = originalSize
    }

    private fun drawShoulderPill(canvas: Canvas, r: RectF, caption: String, content: FaceButtonContent, held: Boolean) {
        canvas.drawRoundRect(r, pillCornerRadius, pillCornerRadius, if (held) litPaint else outlinePaint)
        canvas.drawText(caption, r.centerX(), r.top + r.height() * 0.38f, captionPaint)
        when (content) {
            is FaceButtonContent.Text -> {
                // Auto-shrink-to-fit -- see drawPill's identical comment; this label can be as
                // long as "Filter−"/"Filter+", which doesn't reliably fit at pillLabelPaint's
                // tuned-for-"Undo"/"IF'" size.
                val label = content.text
                val maxWidth = r.width() * 0.88f
                val textWidth = pillLabelPaint.measureText(label)
                val originalSize = pillLabelPaint.textSize
                if (textWidth > maxWidth) pillLabelPaint.textSize = originalSize * (maxWidth / textWidth)
                canvas.drawText(label, r.centerX(), r.top + r.height() * 0.8f, pillLabelPaint)
                pillLabelPaint.textSize = originalSize
            }
            is FaceButtonContent.Icon -> {
                val iconR = r.height() * 0.34f
                TwistIcon.draw(canvas, r.centerX(), r.top + r.height() * 0.68f, iconR, content.axis, content.reverse)
            }
        }
    }

    private fun drawStick(canvas: Canvas, control: MainControl.Stick) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val r = mainRect.width() / 2f
        canvas.drawCircle(cx, cy, r, outlinePaint)
        // Radius 0.8f (inside the drawn circle, near its rim), not outside it -- mainRect is
        // exactly as tall/wide as the circle's own diameter, so a label placed *outside* the
        // circle along a cardinal direction (0/90/180/270 degrees) has nowhere left to go before
        // hitting mainRect's own edge and getting clipped -- confirmed via emulator screenshot:
        // only the "up" label survived (the one direction with a little slack from the pill row
        // above), the other 3 cardinal labels (I/O/D) were silently clipped off-screen entirely,
        // while the 4 diagonal ones (L/B/F/R) happened to fit since a diagonal's per-axis
        // component is smaller by a factor of ~0.7. Drawing inside the circle avoids the
        // direction-dependent margin entirely.
        for (wedge in STICK_WEDGE_LABELS.indices) {
            val angleRad = Math.toRadians(wedge * 45.0)
            val lx = cx + (cos(angleRad) * r * 0.8f).toFloat()
            val ly = cy - (sin(angleRad) * r * 0.8f).toFloat()
            canvas.drawText(STICK_WEDGE_LABELS[wedge], lx, ly + stickLabelPaint.textSize * 0.35f, stickLabelPaint)
        }
        // While a finger is actively dragging the virtual stick, show that touch position;
        // otherwise reflect the real gamepad's physical left stick live (GamepadVisualState.
        // leftStickX/Y -- same +x-right/+y-down convention as currentStickOffset, since both
        // ultimately feed HypercubeRenderer.updateCell4Selection's atan2(-y, x)). Falls back to
        // (0,0) with no gamepad connected, same as an untouched virtual stick.
        val (dotX, dotY) = if (stickPointerId != -1) currentStickOffset else GamepadVisualState.leftStickX to GamepadVisualState.leftStickY
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
        drawFaceButton(canvas, cx, cy - spread, r, control.topCaption(), control.topLabel(), ("faceTop" in heldRegions) || control.topRealHeld())
        drawFaceButton(canvas, cx - spread, cy, r, control.leftCaption(), control.leftLabel(), ("faceLeft" in heldRegions) || control.leftRealHeld())
        drawFaceButton(canvas, cx + spread, cy, r, control.rightCaption(), control.rightLabel(), ("faceRight" in heldRegions) || control.rightRealHeld())
        drawFaceButton(canvas, cx, cy + spread, r, control.bottomCaption(), control.bottomLabel(), ("faceBottom" in heldRegions) || control.bottomRealHeld())
    }

    private fun drawFaceButton(canvas: Canvas, cx: Float, cy: Float, r: Float, caption: String, content: FaceButtonContent, held: Boolean) {
        canvas.drawCircle(cx, cy, r, if (held) litPaint else outlinePaint)
        if (content is FaceButtonContent.Icon) {
            TwistIcon.draw(canvas, cx, cy, r, content.axis, content.reverse)
        }
        // Small a fixed gap as this can be -- the top button's own caption has very little
        // clearance before mainRect's own top edge (confirmed via emulator screenshot: a 0.5x
        // gap had the caption visibly touching the circle), since spread+r already consumes most
        // of mainRect's half-width.
        canvas.drawText(caption, cx, cy - r - captionPaint.textSize * 0.15f, captionPaint)
        if (content is FaceButtonContent.Text) {
            canvas.drawText(content.text, cx, cy + labelPaint.textSize * 0.35f, labelPaint)
        }
    }

    private fun drawDpad(canvas: Canvas, control: MainControl.DPad) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val spread = mainRect.width() * 0.27f
        val r = mainRect.width() * 0.16f
        drawArrowButton(canvas, cx, cy - spread, r, DiamondDirection.UP, control.upLabel, ("faceTop" in heldRegions) || control.upRealHeld())
        drawArrowButton(canvas, cx - spread, cy, r, DiamondDirection.LEFT, control.leftLabel, ("faceLeft" in heldRegions) || control.leftRealHeld())
        drawArrowButton(canvas, cx + spread, cy, r, DiamondDirection.RIGHT, control.rightLabel, ("faceRight" in heldRegions) || control.rightRealHeld())
        drawArrowButton(canvas, cx, cy + spread, r, DiamondDirection.DOWN, control.downLabel, ("faceBottom" in heldRegions) || control.downRealHeld())
    }

    private fun drawArrowButton(canvas: Canvas, cx: Float, cy: Float, r: Float, direction: DiamondDirection, label: String, held: Boolean) {
        canvas.drawCircle(cx, cy, r, if (held) litPaint else outlinePaint)
        val s = r * 0.45f
        arrowPath.reset()
        when (direction) {
            DiamondDirection.UP -> {
                arrowPath.moveTo(cx, cy - s)
                arrowPath.lineTo(cx - s, cy + s * 0.6f)
                arrowPath.lineTo(cx + s, cy + s * 0.6f)
            }
            DiamondDirection.DOWN -> {
                arrowPath.moveTo(cx, cy + s)
                arrowPath.lineTo(cx - s, cy - s * 0.6f)
                arrowPath.lineTo(cx + s, cy - s * 0.6f)
            }
            DiamondDirection.LEFT -> {
                arrowPath.moveTo(cx - s, cy)
                arrowPath.lineTo(cx + s * 0.6f, cy - s)
                arrowPath.lineTo(cx + s * 0.6f, cy + s)
            }
            DiamondDirection.RIGHT -> {
                arrowPath.moveTo(cx + s, cy)
                arrowPath.lineTo(cx - s * 0.6f, cy - s)
                arrowPath.lineTo(cx - s * 0.6f, cy + s)
            }
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowFillPaint)
        // Below the button, same tight-margin reasoning as drawFaceButton's caption gap -- the
        // DOWN arrow's label has only the same ~0.07x-mainRect-width clearance to mainRect's own
        // bottom edge that the face diamond's UP button has to its top edge.
        canvas.drawText(label, cx, cy + r + captionPaint.textSize * 0.65f, captionPaint)
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
                is MainControl.DPad -> handleDpadDown(control, x, y)
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

    // Whichever of the 4 cardinal directions (x, y) is closest to -- a plain nearest-of-4 by
    // angle, not exact per-circle hit-testing, so a tap anywhere in the diamond's quadrant (not
    // just precisely on a drawn circle) still registers -- more forgiving for a thumb. Shared by
    // FaceDiamond and DPad, which are positionally identical.
    private fun resolveDiamondDirection(x: Float, y: Float): DiamondDirection {
        val dx = x - mainRect.centerX()
        val dy = y - mainRect.centerY()
        return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx > 0) DiamondDirection.RIGHT else DiamondDirection.LEFT
        } else {
            if (dy > 0) DiamondDirection.DOWN else DiamondDirection.UP
        }
    }

    private fun handleFaceDiamondDown(control: MainControl.FaceDiamond, x: Float, y: Float) {
        when (resolveDiamondDirection(x, y)) {
            DiamondDirection.RIGHT -> { heldRegions.add("faceRight"); control.onRightTap() }
            DiamondDirection.LEFT -> { heldRegions.add("faceLeft"); control.onLeftTap() }
            DiamondDirection.DOWN -> { heldRegions.add("faceBottom"); control.onBottomTap() }
            DiamondDirection.UP -> { heldRegions.add("faceTop"); control.onTopTap() }
        }
    }

    private fun handleDpadDown(control: MainControl.DPad, x: Float, y: Float) {
        when (resolveDiamondDirection(x, y)) {
            DiamondDirection.RIGHT -> { heldRegions.add("faceRight"); control.onRightTap() }
            DiamondDirection.LEFT -> { heldRegions.add("faceLeft"); control.onLeftTap() }
            DiamondDirection.DOWN -> { heldRegions.add("faceBottom"); control.onDownTap() }
            DiamondDirection.UP -> { heldRegions.add("faceTop"); control.onUpTap() }
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val cx = mainRect.centerX()
        val cy = mainRect.centerY()
        val r = mainRect.width() / 2f
        // A slightly larger drag radius than the drawn circle before saturating -- a bare thumb
        // rarely lands exactly on the visual edge, so this gives a little slack before clamping
        // to a full deflection rather than requiring pixel-precise reach to the rim. Was 1.4x
        // (real-device-tested 2026-08-01 as too sluggish -- reaching full deflection took a full-
        // width thumb drag); 0.9x keeps a little forgiveness while tracking the thumb far more
        // directly.
        val dragR = r * 0.9f
        var nx = (x - cx) / dragR
        var ny = (y - cy) / dragR
        val mag = hypot(nx, ny)
        if (mag > 1f) { nx /= mag; ny /= mag }
        currentStickOffset = nx to ny
        (mainControl as? MainControl.Stick)?.onChanged?.invoke(nx, ny)
        invalidate()
    }

    private fun handlePointerUp(pointerId: Int) {
        if (pointerId == stickPointerId) {
            stickPointerId = -1
            currentStickOffset = 0f to 0f
            (mainControl as? MainControl.Stick)?.onChanged?.invoke(0f, 0f)
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
