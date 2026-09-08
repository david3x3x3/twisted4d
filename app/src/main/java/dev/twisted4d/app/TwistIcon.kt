package dev.twisted4d.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small isometric cube-with-wrapping-arrow icon indicating a twist's rotation axis/direction --
 * prototype for the face-diamond buttons' live per-cell-twist display (replacing the RO/Rx text
 * notation debate with a picture instead, per HactarCE's suggestion in the 2026-09-07 Discord
 * conversation: "my first choice would be a 3D curved arrow indicating the rotation direction").
 *
 * Uses the exact same isometric camera as the puzzle's own default view
 * (HypercubeRenderer.INITIAL_YAW_DEG/INITIAL_PITCH_DEG, -45/35.264 degrees) so the icon's cube
 * reads as "the same cube" the puzzle itself shows at rest, per David's "should I use the same
 * orientation for the virtual arrows?" / HactarCE's "definitely".
 *
 * [Axis.X]/[Axis.Y]/[Axis.Z] are the 3 spin axes a twist can actually have in ordinary 3D space
 * (the axis that stays fixed while the other two spin, matching a ridge twist's `fixAxis2` when it
 * doesn't involve W -- see rotationButtonLiveLabel's doc). A twist whose spin plane includes W (no
 * real on-screen direction -- W is rendered via a shrink/eyeW-divide proxy, not a spatial axis)
 * doesn't have a literal 3D rotation to depict; per 2026-09-07 product decision, those are given a
 * stylized approximation rather than derived real projected geometry -- see [forFixAxis]'s doc for
 * the exact mapping chosen.
 */
object TwistIcon {
    enum class Axis { X, Y, Z }

    private const val YAW_DEG = -45.0
    private const val PITCH_DEG = 35.264

    private data class Vec3(val x: Float, val y: Float, val z: Float)

    private fun rotateY(p: Vec3, deg: Double): Vec3 {
        val r = Math.toRadians(deg)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
    }

    private fun rotateX(p: Vec3, deg: Double): Vec3 {
        val r = Math.toRadians(deg)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
    }

    /** The same view transform as the puzzle's own default camera -- yaw first, then pitch,
     * matching HypercubeRenderer.onSurfaceCreated's `applyScreenRelativeRotation(yaw, 0);
     * applyScreenRelativeRotation(0, pitch)` composition order. */
    private fun view(p: Vec3): Vec3 = rotateX(rotateY(p, YAW_DEG), PITCH_DEG)

    // Unit cube corners, -1..1 on each axis.
    private val CUBE_CORNERS: List<Vec3> = buildList {
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            add(Vec3(sx.toFloat(), sy.toFloat(), sz.toFloat()))
        }
    }

    // 6 faces, each 4 corner indices into CUBE_CORNERS (index = (sx+1)*4+(sy+1)*2+(sz+1with sx,sy,sz
    // in {-1,1} order above -- easier to just list by explicit sign combination than compute indices).
    private data class Face(val corners: List<Vec3>, val shade: Float)

    private fun cubeFaces(): List<Face> {
        fun c(sx: Int, sy: Int, sz: Int) = Vec3(sx.toFloat(), sy.toFloat(), sz.toFloat())
        // shade is a relative brightness multiplier -- top brightest, then the two side faces --
        // classic isometric-icon shading so the 3 visible faces read as distinct planes.
        return listOf(
            // Darkened from the first draft's 1.0/0.85/0.75/etc (2026-09-07 feedback: more contrast
            // against the white arrow).
            Face(listOf(c(-1, 1, -1), c(1, 1, -1), c(1, 1, 1), c(-1, 1, 1)), 0.72f), // +Y top
            Face(listOf(c(-1, -1, -1), c(1, -1, -1), c(1, -1, 1), c(-1, -1, 1)), 0.4f), // -Y bottom
            Face(listOf(c(1, -1, -1), c(1, 1, -1), c(1, 1, 1), c(1, -1, 1)), 0.6f), // +X right
            Face(listOf(c(-1, -1, -1), c(-1, 1, -1), c(-1, 1, 1), c(-1, -1, 1)), 0.46f), // -X left
            Face(listOf(c(-1, -1, 1), c(1, -1, 1), c(1, 1, 1), c(-1, 1, 1)), 0.53f), // +Z front
            Face(listOf(c(-1, -1, -1), c(1, -1, -1), c(1, 1, -1), c(-1, 1, -1)), 0.65f), // -Z back
        )
    }

    /** Which real ridge-twist `fixAxis2` (its representative letter) maps to which icon [Axis] --
     * X/Y/Z map directly (a real, on-screen 3D rotation). W has no on-screen direction (see this
     * object's class doc), so it's given a stylized stand-in: [Axis.Z] (the "front-back" spin,
     * i.e. the depth axis) is reused, on the reasoning that W is this app's own "depth-ish" 4th
     * axis (the eyeW-divide shrink already treats it as a depth cue), so reusing the on-screen
     * depth axis's icon is the least-arbitrary stylized choice available -- not a claim of literal
     * geometric correctness, per the 2026-09-07 "stylized approximation" decision. */
    fun forFixAxis(axis: Axis4): Axis = when (axis) {
        Axis4.X -> Axis.X
        Axis4.Y -> Axis.Y
        Axis4.Z -> Axis.Z
        Axis4.W -> Axis.Z
    }

    /** Draws the icon centered at ([cx], [cy]), scaled to fit within a circle of [radius] --
     * [axis] is the twist's fixed rotation axis (see [forFixAxis]); [reverse] is the twist's prime
     * (displayApostrophe) flag as MainActivity.rotationButtonContent computes it. Confirmed
     * correct as-is for [Axis.Y] against real twists (2026-09-07), but [Axis.X]/[Axis.Z] both came
     * out backwards -- not a per-axis coincidence: [ringPointAt]'s own per-axis parametrization
     * isn't cyclically consistent (Y's traces X-to-Z as t increases, the *opposite* cyclic order
     * from X's Y-to-Z and Z's X-to-Y, which both follow the standard right-hand X->Y->Z->X cycle),
     * so the same boolean [reverse] necessarily lands correct for one parity group and backwards
     * for the other. Flipped here for X/Z instead of rederiving the parametrization to be
     * perfectly cyclic, which would only relocate the same flip rather than remove it. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, axis: Axis, reverse: Boolean) {
        val effectiveReverse = if (axis == Axis.Y) reverse else !reverse

        // Clipped to the icon's own circular bounds -- decoupling the ring's scale from the
        // cube's (see below) means a large ring radius can otherwise project well outside
        // [radius], spilling the dim back arc into neighboring UI (confirmed via a real
        // screenshot, 2026-09-07, after bumping ringRadius to 2.1 -- it fanned out past the
        // button's own circle into the row above/below).
        canvas.save()
        val clip = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(clip)

        val faces = cubeFaces().map { face -> face.copy(corners = face.corners.map(::view)) }

        // Raw (pre-view) ring point at angle [t] radians around [axis] -- t=0 always lands exactly
        // on one visible spinning face's own center direction, and t=90 degrees on the other (e.g.
        // Axis.Y: t=0 -> (1,0,0) = +X = R's direction, t=90 degrees -> (0,0,1) = +Z = F's -- exactly
        // the two faces a Y-fixed twist spins between). This is *why* the arrow's endpoints are
        // now defined as the exact quarter-circle from t=0 to t=90 degrees, not an empirically
        // trimmed fraction of the old full front/back split: a twist genuinely is a 90-degree
        // rotation, so face-center-to-face-center is the one length with an actual geometric
        // meaning, rather than a guessed-and-tuned trim percentage.
        fun ringPointAt(t: Double, r: Float): Vec3 {
            val a = (cos(t) * r).toFloat()
            val b = (sin(t) * r).toFloat()
            return view(
                when (axis) {
                    Axis.Y -> Vec3(a, 0f, b)
                    Axis.X -> Vec3(0f, a, b)
                    Axis.Z -> Vec3(a, b, 0f)
                },
            )
        }

        // Ring radius: comfortably beyond the cube's own half-extent (1) so it visually wraps
        // *around* the cube rather than cutting through its faces. 1.6 was tuned against the full
        // 180-degree front/back split this replaces; kept as a starting point here too, but this
        // quarter-circle reframing (see ringPointAt's doc) is the real fix for "needs more curve"
        // -- t=45 degrees (the arc's midpoint) is provably this ring's single *closest-to-camera*
        // point for every axis (confirmed algebraically: rotated Z after this view transform is
        // proportional to sin(t)+cos(t), maximized at t=45), so the two face-center endpoints are
        // always exactly as far from the flattest part of the curve as they can be while still
        // being a real quarter-turn -- unlike trimming a much larger arc down small, which always
        // eats back into that same flat region from both sides.
        val ringRadius = 1.6f
        val frontSamples = 40
        val front = (0..frontSamples).map { i -> ringPointAt(Math.toRadians(i * 90.0 / frontSamples), ringRadius) }
        // Back reference arc: the remaining 270 degrees, drawn dim/first so the cube (drawn next)
        // occludes whichever part of it its silhouette actually covers -- purely a "this wraps all
        // the way around" visual hint, no exact endpoints to get right the way the front arc's do.
        val backSamples = 96
        val back = (0..backSamples).map { i -> ringPointAt(Math.toRadians(90.0 + i * 270.0 / backSamples), ringRadius) }

        // Scale is derived from the CUBE alone, not the ring -- the ring's whole point is to wrap
        // around a fixed-size cube, so this must not shrink the cube to keep fitting a shared
        // bounding box; the arc extending a bit past the cube's own footprint is fine, same as any
        // button's touch padding already gives some breathing room.
        val maxExtent = faces.flatMap { it.corners }.maxOf { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }
        val scale = radius / maxExtent * 0.92f

        fun screenX(p: Vec3) = cx + p.x * scale
        fun screenY(p: Vec3) = cy - p.y * scale // screen Y grows downward; 3D Y grows upward

        fun strokePathFor(points: List<Vec3>): Path? {
            if (points.size < 2) return null
            val path = Path()
            path.moveTo(screenX(points[0]), screenY(points[0]))
            for (p in points.drop(1)) path.lineTo(screenX(p), screenY(p))
            return path
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.16f
            strokeCap = Paint.Cap.ROUND
            color = android.graphics.Color.argb(230, 255, 255, 255)
        }

        // Back arc first, dimmer -- see its own doc above.
        ringPaint.alpha = 90
        strokePathFor(back)?.let { canvas.drawPath(it, ringPaint) }

        // Cube faces, back-to-front by rotated depth, each a flat shade -- occludes the back arc's
        // middle section.
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.035f
            color = android.graphics.Color.argb(255, 10, 12, 20)
        }
        val sortedFaces = faces.sortedByDescending { face -> face.corners.sumOf { it.z.toDouble() } }
        for (face in sortedFaces.take(3)) {
            val path = Path()
            path.moveTo(screenX(face.corners[0]), screenY(face.corners[0]))
            for (p in face.corners.drop(1)) path.lineTo(screenX(p), screenY(p))
            path.close()
            val v = (face.shade * 230).toInt().coerceIn(0, 255)
            facePaint.color = android.graphics.Color.rgb(v, v, v)
            canvas.drawPath(path, facePaint)
            canvas.drawPath(path, edgePaint)
        }

        // Front arc, full brightness, on top of the cube -- exactly the face-center-to-face-center
        // quarter circle computed above, no further trimming.
        ringPaint.alpha = 230
        strokePathFor(front)?.let { canvas.drawPath(it, ringPaint) }

        // Arrowhead at whichever end matches [reverse]'s direction.
        val tip = if (effectiveReverse) front.first() else front.last()
        val next = if (effectiveReverse) front[1] else front[front.size - 2]
        drawArrowhead(canvas, screenX(tip), screenY(tip), screenX(next), screenY(next), radius * 0.32f, ringPaint.color)
        canvas.restore()
    }

    private fun drawArrowhead(canvas: Canvas, tipX: Float, tipY: Float, fromX: Float, fromY: Float, size: Float, color: Int) {
        val dx = tipX - fromX
        val dy = tipY - fromY
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
        val ux = dx / len
        val uy = dy / len
        // Perpendicular for the arrowhead's base width.
        val px = -uy
        val py = ux
        val path = Path()
        path.moveTo(tipX + ux * size * 0.6f, tipY + uy * size * 0.6f)
        path.lineTo(tipX - ux * size * 0.4f + px * size * 0.5f, tipY - uy * size * 0.4f + py * size * 0.5f)
        path.lineTo(tipX - ux * size * 0.4f - px * size * 0.5f, tipY - uy * size * 0.4f - py * size * 0.5f)
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color; alpha = 255 }
        canvas.drawPath(path, paint)
    }
}
