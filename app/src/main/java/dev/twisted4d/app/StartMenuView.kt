package dev.twisted4d.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * One of the grid's slots, row-major (top-left first). [isActive], if given, renders a live
 * on/off indicator (e.g. for a Hide-4c-style toggle) -- queried at draw time rather than baked
 * into the tile, so a single static tile list stays correct as the underlying state changes
 * elsewhere. [onSelect] is null for a reserved/empty slot -- confirming it is a no-op.
 */
data class MenuTile(
    val label: String,
    val enabled: Boolean = true,
    val isActive: (() -> Boolean)? = null,
    val onSelect: (() -> Unit)? = null,
)

/**
 * A controller-navigable grid of [MenuTile]s (see the menu/settings design doc -- memory files
 * `menu_settings_design_doc`/`menu_system_plan` -- for the full spec this implements), gradually
 * replacing the old scattered on-screen buttons. Two rendering styles, picked at construction:
 *
 * - **Fullscreen** (`compact = false`, the default -- used for the Start Menu itself): opaque
 *   backdrop, big centered grid with a title, consumes every touch while open so it also blocks
 *   drags from reaching the puzzle underneath.
 * - **Compact** (`compact = true` -- used for the Filters submenu): a small, mostly-transparent
 *   panel anchored to a screen edge, sized to just its own tiles. Deliberately does NOT consume
 *   touches outside that panel -- [onTouchEvent] returns false for them, so Android's normal touch
 *   dispatch falls through to whatever's behind this view in the same FrameLayout (the puzzle's
 *   GLSurfaceView) -- letting the puzzle stay visible *and* interactive (drag-to-rotate) while
 *   adjusting filters, which is the whole reason this style exists (see the "can't see the puzzle
 *   while adjusting filters" feedback that prompted it).
 *
 * Default highlight is the grid's middle index (e.g. the true center for the Start Menu's 3x3, a
 * middle-ish item for a compact panel's Nx1 list) -- matches the design doc's "Settings is the
 * default highlighted tile" for the 3x3 case, generalized for any grid size.
 *
 * Gamepad navigation is driven externally (see MainActivity's on4DNavigate/onLeftStick/
 * on4DRotationButton wiring) rather than this view listening for input itself -- gamepad events
 * arrive at the Activity level, not through normal View focus dispatch, since this app's gamepad
 * handling deliberately bypasses that (see GamepadInputHandler's class doc). Touch, by contrast,
 * IS handled directly here via [onTouchEvent], since that's ordinary View input and every tile
 * must stay tap/click-accessible for controller-less users (see the design doc's accessibility
 * section).
 */
class StartMenuView(
    context: Context,
    private val gridCols: Int = 3,
    private val gridRows: Int = 3,
    private val compact: Boolean = false,
    private val caption: String = "FILTERS",
) : View(context) {

    var isOpen: Boolean = false
        private set

    private val tileCount = gridCols * gridRows
    private var tiles: List<MenuTile?> = List(tileCount) { null }
    private var highlighted = tileCount / 2

    private val bgPaint = Paint().apply { color = Color.argb(235, 8, 8, 10) }
    private val compactBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 10, 12, 16) }
    private val tileBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 90, 130, 180)
    }
    private val tileFillPaint = Paint().apply { color = Color.argb(255, 20, 28, 42) }
    private val compactTileFillPaint = Paint().apply { color = Color.argb(215, 20, 28, 42) }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(220, 90, 200, 120)
    }
    private val reservedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(90, 150, 150, 150)
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(255, 235, 175, 45)
    }
    private val highlightFillPaint = Paint().apply { color = Color.argb(255, 70, 58, 10) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 235, 238, 245)
        textAlign = Paint.Align.CENTER
    }
    private val disabledLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 150, 150, 150)
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 220, 225, 235)
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    init {
        visibility = GONE
    }

    /** Must be exactly [gridCols] * [gridRows] entries, row-major; use `null` for a reserved/
     * empty slot. */
    fun setTiles(newTiles: List<MenuTile?>) {
        require(newTiles.size == tileCount) { "StartMenuView needs exactly $tileCount tiles (nullable for reserved slots)" }
        tiles = newTiles
    }

    fun open() {
        isOpen = true
        highlighted = tileCount / 2
        visibility = VISIBLE
        invalidate()
    }

    fun close() {
        isOpen = false
        visibility = GONE
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    /** Radial-style selection: [x]/[y] (each roughly -1..1, same convention as
     * [GamepadInputHandler]'s stick/d-pad-as-stick reporting) directly pick a tile by direction
     * -- hold the stick/d-pad toward a tile and press Confirm, rather than incrementally moving a
     * cursor. Centered (both near 0) picks the grid's middle tile. Called on every stick/d-pad
     * update while a menu is open (see MainActivity's onLeftStick/onDpadStick wiring), not just on
     * a discrete "move" press, so the highlight always reflects the current physical direction
     * immediately. */
    fun setHighlightFromStick(x: Float, y: Float) {
        if (!isOpen) return
        val col = binIndex(x, gridCols)
        val row = binIndex(y, gridRows)
        val newHighlighted = row * gridCols + col
        if (newHighlighted != highlighted) {
            highlighted = newHighlighted
            invalidate()
        }
    }

    /** Splits -1..1 into [count] equal-width bins and returns which one [v] falls in (e.g.
     * count=3 -> thirds, so a centered stick lands in the middle bin -- exactly the "9th choice is
     * leaving the stick centered" behavior [setHighlightFromStick]'s doc describes). */
    private fun binIndex(v: Float, count: Int): Int =
        (((v.coerceIn(-1f, 1f) + 1f) / 2f) * count).toInt().coerceIn(0, count - 1)

    /** Activates the currently-highlighted tile, if any and enabled. Does not close the menu
     * itself -- individual tile actions decide that (a toggle like Hide 4c stays open; an action
     * like Play 3D Puzzle closes it), since that varies per tile. */
    fun confirm() {
        if (!isOpen) return
        val tile = tiles.getOrNull(highlighted) ?: return
        if (tile.enabled) tile.onSelect?.invoke()
        invalidate()
    }

    /** The area tiles are actually drawn/hit-tested in -- the whole view for the fullscreen
     * style, a small edge-anchored panel for the compact style. Shared by [onDraw] and
     * [onTouchEvent] so the two can never disagree about where a tile actually is. */
    private fun panelRect(w: Float, h: Float): RectF =
        if (!compact) {
            RectF(w * GRID_SIDE_MARGIN_FRACTION, h * GRID_TOP_FRACTION, w * (1f - GRID_SIDE_MARGIN_FRACTION), h * (1f - GRID_BOTTOM_MARGIN_FRACTION))
        } else {
            // Panel size is fixed regardless of gridCols/gridRows (unlike the fullscreen style,
            // whose per-cell size is fixed and total size grows with the grid) -- individual tile
            // size instead shrinks to fit, exactly like the fullscreen grid's own cellW/cellH
            // division already does in onDraw/onTouchEvent. Keeps a compact panel compact no
            // matter how many reserved/empty slots it has (see e.g. the Filters submenu's
            // cross-shaped 3x3, mostly reserved).
            val panelW = w * COMPACT_PANEL_WIDTH_FRACTION
            val panelH = h * COMPACT_PANEL_HEIGHT_FRACTION
            val margin = h * COMPACT_MARGIN_FRACTION
            val captionH = h * COMPACT_CAPTION_HEIGHT_FRACTION
            val left = w - margin - panelW
            val top = margin + captionH
            RectF(left, top, left + panelW, top + panelH)
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return false
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return !compact
        val rect = panelRect(w, h)
        if (compact && event.action == MotionEvent.ACTION_DOWN && !rect.contains(event.x, event.y)) {
            // Let the touch fall through to whatever's behind this view (the puzzle's
            // GLSurfaceView) -- see the class doc's "compact" section.
            return false
        }
        if (event.action != MotionEvent.ACTION_UP) return true
        val col = (((event.x - rect.left) / rect.width()) * gridCols).toInt().coerceIn(0, gridCols - 1)
        val row = (((event.y - rect.top) / rect.height()) * gridRows).toInt().coerceIn(0, gridRows - 1)
        highlighted = row * gridCols + col
        confirm()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!isOpen) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (!compact) {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            titlePaint.textSize = h * 0.06f
            canvas.drawText("TWISTED 4D", w / 2f, h * 0.10f, titlePaint)
        }

        val rect = panelRect(w, h)
        val cellW = rect.width() / gridCols
        val cellH = rect.height() / gridRows
        val pad = min(cellW, cellH) * (if (compact) 0.05f else 0.08f)

        if (compact) {
            val backdrop = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
            canvas.drawRoundRect(backdrop, 14f, 14f, compactBgPaint)
            captionPaint.textSize = cellH * 0.32f
            canvas.drawText(caption, rect.left, rect.top - pad - captionPaint.textSize * 0.5f, captionPaint)
        }

        labelPaint.textSize = min(cellW, cellH) * (if (compact) 0.24f else 0.15f)
        disabledLabelPaint.textSize = labelPaint.textSize

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val index = row * gridCols + col
                val tile = tiles.getOrNull(index)
                val cellRect = RectF(
                    rect.left + col * cellW + pad,
                    rect.top + row * cellH + pad,
                    rect.left + (col + 1) * cellW - pad,
                    rect.top + (row + 1) * cellH - pad,
                )
                val isHighlighted = index == highlighted

                if (tile == null) {
                    canvas.drawRoundRect(cellRect, 16f, 16f, if (isHighlighted) highlightBorderPaint else reservedBorderPaint)
                    continue
                }

                val fillPaint = if (isHighlighted) highlightFillPaint else if (compact) compactTileFillPaint else tileFillPaint
                canvas.drawRoundRect(cellRect, 16f, 16f, fillPaint)
                val active = tile.isActive?.invoke() == true
                val border = when {
                    isHighlighted -> highlightBorderPaint
                    active -> activeBorderPaint
                    else -> tileBorderPaint
                }
                canvas.drawRoundRect(cellRect, 16f, 16f, border)

                val lines = tile.label.split("\n")
                val paint = if (tile.enabled) labelPaint else disabledLabelPaint
                val lineHeight = paint.textSize * 1.25f
                val startY = (cellRect.top + cellRect.bottom) / 2f - lineHeight * (lines.size - 1) / 2f + paint.textSize * 0.35f
                lines.forEachIndexed { i, line ->
                    canvas.drawText(line, (cellRect.left + cellRect.right) / 2f, startY + i * lineHeight, paint)
                }
            }
        }
    }

    companion object {
        private const val GRID_TOP_FRACTION = 0.18f
        private const val GRID_BOTTOM_MARGIN_FRACTION = 0.06f
        private const val GRID_SIDE_MARGIN_FRACTION = 0.15f

        private const val COMPACT_PANEL_WIDTH_FRACTION = 0.34f
        private const val COMPACT_PANEL_HEIGHT_FRACTION = 0.34f
        private const val COMPACT_MARGIN_FRACTION = 0.05f
        private const val COMPACT_CAPTION_HEIGHT_FRACTION = 0.06f
    }
}
