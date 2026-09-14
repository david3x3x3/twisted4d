package dev.twisted4d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.min

/**
 * Low-detail, always-on-screen schematic of the gamepad -- every button/stick this app actually
 * uses lights up while held/deflected, purely so a screen recording can be used afterward to
 * confirm exactly which physical control produced a given twist (there's no other record of
 * *input*, only of its effect on the puzzle). Reads [GamepadVisualState] on a short self-driven
 * repaint loop rather than reacting to individual events, since it only ever needs "what's true
 * right now."
 *
 * Layout mirrors a standard modern pad (8BitDo/Xbox-style): L2/R2 top corners, L1/R1 below them,
 * SELECT top-center, left stick upper-left with the d-pad below it lower-left, and the Y/X/B/A
 * face-button diamond upper-right with the right stick below it lower-right -- so it maps onto a
 * viewer's own physical controller at a glance rather than needing a legend.
 */
class GamepadOverlayView(context: Context) : View(context) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(140, 150, 165, 200)
    }
    private val unlitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 150, 165, 200)
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
        color = Color.argb(200, 220, 225, 235)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private val repaintHandler = Handler(Looper.getMainLooper())
    private val repaintTick = object : Runnable {
        override fun run() {
            invalidate()
            repaintHandler.postDelayed(this, 33L) // ~30fps -- plenty for a status HUD
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val unit = min(w, h)
        labelPaint.textSize = unit * 0.10f

        rect.set(w * 0.03f, h * 0.05f, w * 0.97f, h * 0.95f)
        canvas.drawRoundRect(rect, unit * 0.07f, unit * 0.07f, bodyPaint)

        drawShoulder(canvas, w * 0.13f, h * 0.13f, unit, GamepadVisualState.l2Held, "L2")
        drawShoulder(canvas, w * 0.87f, h * 0.13f, unit, GamepadVisualState.r2Held, "R2")
        drawShoulder(canvas, w * 0.13f, h * 0.24f, unit, GamepadVisualState.l1Held, "L1")
        drawShoulder(canvas, w * 0.87f, h * 0.24f, unit, GamepadVisualState.r1Held, "R1")
        drawShoulder(canvas, w * 0.50f, h * 0.17f, unit * 0.85f, GamepadVisualState.selectHeld, "SEL")

        // Left cluster: stick above, d-pad below.
        drawStick(
            canvas, w * 0.24f, h * 0.42f, unit * 0.135f,
            GamepadVisualState.leftStickX, GamepadVisualState.leftStickY, GamepadVisualState.thumbLHeld,
        )
        drawDpad(canvas, w * 0.24f, h * 0.74f, unit)

        // Right cluster: face-button diamond above, stick below.
        val faceR = unit * 0.07f
        val faceCx = w * 0.76f
        val faceCy = h * 0.42f
        val faceSpread = unit * 0.105f
        // Screen position is fixed (top/left/right/bottom), but which raw held-flag belongs at
        // each one depends on Nintendo Layout (its physical positions are X top, Y left, A right,
        // B bottom, vs. Xbox's Y/X/B/A) -- resolved here, at read time, same as the printed
        // letter, rather than baked into the held-flags themselves (see GamepadVisualState's doc
        // for why: normalizing at write time let a mid-press Nintendo Layout toggle strand a
        // held-flag stuck true, fixed 2026-09-14). Per-controller now, so this reflects whichever
        // pad most recently sent input.
        val nintendo = PerControllerSettings.current()?.nintendoLayout == true
        drawFaceButton(canvas, faceCx, faceCy - faceSpread, faceR, if (nintendo) GamepadVisualState.xHeld else GamepadVisualState.yHeld, if (nintendo) "X" else "Y")
        drawFaceButton(canvas, faceCx - faceSpread, faceCy, faceR, if (nintendo) GamepadVisualState.yHeld else GamepadVisualState.xHeld, if (nintendo) "Y" else "X")
        drawFaceButton(canvas, faceCx + faceSpread, faceCy, faceR, if (nintendo) GamepadVisualState.aHeld else GamepadVisualState.bHeld, if (nintendo) "A" else "B")
        drawFaceButton(canvas, faceCx, faceCy + faceSpread, faceR, if (nintendo) GamepadVisualState.bHeld else GamepadVisualState.aHeld, if (nintendo) "B" else "A")
        drawStick(canvas, w * 0.76f, h * 0.74f, unit * 0.135f, GamepadVisualState.rightStickX, GamepadVisualState.rightStickY)
    }

    private fun drawShoulder(canvas: Canvas, cx: Float, cy: Float, unit: Float, held: Boolean, label: String) {
        rect.set(cx - unit * 0.09f, cy - unit * 0.045f, cx + unit * 0.09f, cy + unit * 0.045f)
        canvas.drawRoundRect(rect, unit * 0.02f, unit * 0.02f, if (held) litPaint else unlitPaint)
        canvas.drawText(label, cx, cy + unit * 0.04f, labelPaint)
    }

    private fun drawFaceButton(canvas: Canvas, cx: Float, cy: Float, r: Float, held: Boolean, label: String) {
        canvas.drawCircle(cx, cy, r, if (held) litPaint else unlitPaint)
        canvas.drawText(label, cx, cy + r * 0.4f, labelPaint)
    }

    /** [stickX]/[stickY] are in the same -1..1 range [GamepadInputHandler] already deadzones.
     * [clickHeld] lights the center dot the same blue as every other held control, for sticks
     * whose click (L3/R3) this app actually binds to something -- omitted (always unlit) for
     * sticks with no click binding. */
    private fun drawStick(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        stickX: Float,
        stickY: Float,
        clickHeld: Boolean = false,
    ) {
        canvas.drawCircle(cx, cy, r, unlitPaint)
        val dotX = cx + stickX.coerceIn(-1f, 1f) * (r - r * 0.25f)
        val dotY = cy + stickY.coerceIn(-1f, 1f) * (r - r * 0.25f)
        canvas.drawCircle(dotX, dotY, r * 0.22f, if (clickHeld) litPaint else stickDotPaint)
    }

    /** Four independently-lighting arrow glyphs in a plus arrangement -- mode 2's step
     * navigation (see NavigationButton) uses all four, so each needs its own indicator, unlike
     * the single-button shoulders/face buttons. */
    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val offset = unit * 0.10f
        drawDpadArrow(canvas, cx, cy - offset, unit, GamepadVisualState.dpadUpHeld, "▲")
        drawDpadArrow(canvas, cx, cy + offset, unit, GamepadVisualState.dpadDownHeld, "▼")
        drawDpadArrow(canvas, cx - offset, cy, unit, GamepadVisualState.dpadLeftHeld, "◀")
        drawDpadArrow(canvas, cx + offset, cy, unit, GamepadVisualState.dpadRightHeld, "▶")
    }

    private fun drawDpadArrow(canvas: Canvas, cx: Float, cy: Float, unit: Float, held: Boolean, glyph: String) {
        val r = unit * 0.05f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawRoundRect(rect, unit * 0.015f, unit * 0.015f, if (held) litPaint else unlitPaint)
        canvas.drawText(glyph, cx, cy + r * 0.4f, labelPaint)
    }
}
