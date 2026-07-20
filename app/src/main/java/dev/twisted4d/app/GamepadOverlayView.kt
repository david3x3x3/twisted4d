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
 * Layout mirrors a standard Xbox-style pad (L2/R2 top corners, L1/R1 below them, stick circles
 * bottom-left/right, Y/X/B/A face buttons as a diamond in between) so it maps onto a viewer's
 * own physical controller at a glance rather than needing a legend.
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
        labelPaint.textSize = unit * 0.11f

        rect.set(w * 0.04f, h * 0.06f, w * 0.96f, h * 0.94f)
        canvas.drawRoundRect(rect, unit * 0.08f, unit * 0.08f, bodyPaint)

        drawShoulder(canvas, w * 0.14f, h * 0.18f, unit, GamepadVisualState.l2Held, "L2")
        drawShoulder(canvas, w * 0.86f, h * 0.18f, unit, GamepadVisualState.r2Held, "R2")
        drawShoulder(canvas, w * 0.14f, h * 0.34f, unit, GamepadVisualState.l1Held, "L1")
        drawShoulder(canvas, w * 0.86f, h * 0.34f, unit, GamepadVisualState.r1Held, "R1")

        drawStick(canvas, w * 0.22f, h * 0.68f, unit * 0.17f, GamepadVisualState.leftStickX, GamepadVisualState.leftStickY)
        drawStick(canvas, w * 0.78f, h * 0.68f, unit * 0.17f, GamepadVisualState.rightStickX, GamepadVisualState.rightStickY)

        val faceR = unit * 0.075f
        drawFaceButton(canvas, w * 0.50f, h * 0.46f, faceR, GamepadVisualState.yHeld, "Y")
        drawFaceButton(canvas, w * 0.40f, h * 0.63f, faceR, GamepadVisualState.xHeld, "X")
        drawFaceButton(canvas, w * 0.60f, h * 0.63f, faceR, GamepadVisualState.bHeld, "B")
        drawFaceButton(canvas, w * 0.50f, h * 0.80f, faceR, GamepadVisualState.aHeld, "A")
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

    /** [stickX]/[stickY] are in the same -1..1 range [GamepadInputHandler] already deadzones. */
    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, stickX: Float, stickY: Float) {
        canvas.drawCircle(cx, cy, r, unlitPaint)
        val dotX = cx + stickX.coerceIn(-1f, 1f) * (r - r * 0.25f)
        val dotY = cy + stickY.coerceIn(-1f, 1f) * (r - r * 0.25f)
        canvas.drawCircle(dotX, dotY, r * 0.22f, stickDotPaint)
    }
}
