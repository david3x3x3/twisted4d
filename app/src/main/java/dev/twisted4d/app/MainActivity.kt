package dev.twisted4d.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var inputManager: InputManager
    private lateinit var gamepadInput: GamepadInputHandler

    private var glSurfaceView: GLSurfaceView? = null
    private var is4DMode = true

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // Twist history for the currently-built screen's mode -- written from the GL thread
    // (onTwistApplied) and read/cleared from the UI thread (undo/scramble/reset/log buttons),
    // so both need to be synchronizedList plus explicit `synchronized(...)` around compound
    // check-then-act sequences. Instance fields (not locals inside build3DScreen/build4DScreen)
    // so onPause can read the active mode's history to persist it -- see saveState/loadState.
    private val moveHistory3D = Collections.synchronizedList(mutableListOf<Pair<Face, Boolean>>())
    private val moveHistory4D = Collections.synchronizedList(mutableListOf<Triple<Cell4, Axis4, Boolean>>())

    // Set by loadState() (called once, before the first rebuildUi()) when a saved puzzle state
    // exists for that mode; consumed (and nulled) by build3DScreen/build4DScreen the first time
    // they run afterward, restoring native state instead of the fresh solved() reset that
    // onSurfaceCreated always performs. See restoreState on both renderers.
    private var pendingRestoreState3D: IntArray? = null
    private var pendingRestoreState4D: IntArray? = null

    // Persisted alongside state (see saveState) so the status label can be initialized correctly
    // on restore -- onSurfaceCreated's restore path runs on the GL thread with no reliable
    // signal back to the UI thread before the first frame, so this can't just be queried live.
    private var pendingRestoreSolved3D: Boolean? = null
    private var pendingRestoreSolved4D: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "puzzle-core version: ${NativeLib.coreVersion()}")

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        loadState()
        rootLayout = FrameLayout(this)
        setContentView(rootLayout)
        rebuildUi()
    }

    /** Tears down and rebuilds the whole screen for the current mode. GLSurfaceView only
     * accepts one `setRenderer` call ever, so switching modes means a fresh instance. */
    private fun rebuildUi() {
        rootLayout.removeAllViews()
        if (::gamepadInput.isInitialized) {
            inputManager.unregisterInputDeviceListener(gamepadInput)
        }

        if (is4DMode) build4DScreen() else build3DScreen()
    }

    // --- 3D mode ---------------------------------------------------------------------------

    private fun build3DScreen() {
        val renderer = CubeRenderer()
        val initiallySolved: Boolean
        if (pendingRestoreState3D == null) {
            moveHistory3D.clear()
            initiallySolved = true
        } else {
            renderer.pendingRestoreState = pendingRestoreState3D
            pendingRestoreState3D = null
            initiallySolved = pendingRestoreSolved3D ?: true
            pendingRestoreSolved3D = null
        }
        val surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
        }
        glSurfaceView = surfaceView

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    renderer.zoomBy(detector.scaleFactor)
                    return true
                }
            },
        )
        surfaceView.setOnTouchListener { _, event -> handle3DDrag(surfaceView, renderer, event) }

        gamepadInput = GamepadInputHandler(
            onLeftStick = { x, y -> renderer.stickX = x; renderer.stickY = y },
            onRightStick = { _, _ -> },
            onFaceButton = { index, invert ->
                surfaceView.queueEvent { renderer.requestScreenRelativeTwist(Face.entries[index], invert) }
            },
        )
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        val statusText = statusTextView(initiallySolved)
        renderer.onStateChanged = { solved ->
            runOnUiThread { statusText.text = if (solved) SOLVED_LABEL else "" }
        }

        renderer.onTwistApplied = { face, prime -> moveHistory3D.add(face to prime) }

        val twistRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            Face.entries.forEach { face ->
                addView(
                    Button(this@MainActivity).apply {
                        text = face.label
                        setOnClickListener { surfaceView.queueEvent { renderer.requestScreenRelativeTwist(face, false) } }
                        setOnLongClickListener {
                            surfaceView.queueEvent { renderer.requestScreenRelativeTwist(face, true) }
                            true
                        }
                    },
                )
            }
        }

        val utilityRow = utilityRow(
            onScramble = { surfaceView.queueEvent { renderer.requestScramble(SCRAMBLE_MOVE_COUNT_3D) }; moveHistory3D.clear() },
            onReset = { surfaceView.queueEvent { renderer.requestReset() }; moveHistory3D.clear() },
            onUndo = {
                synchronized(moveHistory3D) {
                    if (moveHistory3D.isNotEmpty()) {
                        val (face, prime) = moveHistory3D.removeAt(moveHistory3D.size - 1)
                        surfaceView.queueEvent { renderer.undoTwist(face, prime) }
                    }
                }
            },
            onShareLog = {
                val snapshot = synchronized(moveHistory3D) { moveHistory3D.toList() }
                shareTwistLog(formatTwistLog3D(snapshot))
            },
        )

        rootLayout.addView(surfaceView)
        rootLayout.addView(statusText, topCenterParams())
        rootLayout.addView(twistRow, bottomCenterParams(bottomMargin = 48))
        rootLayout.addView(modeToggleButton(), topStartParams())
        rootLayout.addView(utilityRow, topEndParams())
        rootLayout.addView(gamepadOverlayView(), bottomStartParams())
    }

    private fun handle3DDrag(surfaceView: GLSurfaceView, renderer: CubeRenderer, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scaleGestureDetector.isInProgress) {
                    renderer.addDragDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                    surfaceView.requestRender()
                }
            }
        }
        return true
    }

    // --- 4D mode -----------------------------------------------------------------------------

    private fun build4DScreen() {
        val renderer = HypercubeRenderer()
        val initiallySolved: Boolean
        if (pendingRestoreState4D == null) {
            moveHistory4D.clear()
            initiallySolved = true
        } else {
            renderer.pendingRestoreState = pendingRestoreState4D
            pendingRestoreState4D = null
            initiallySolved = pendingRestoreSolved4D ?: true
            pendingRestoreSolved4D = null
        }
        val surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
        }
        glSurfaceView = surfaceView

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    renderer.zoomBy(detector.scaleFactor)
                    return true
                }
            },
        )
        surfaceView.setOnTouchListener { _, event -> handle4DDrag(surfaceView, renderer, event) }

        // Mode 1 (default): left stick selects a cell continuously, matching the existing
        // scheme. Mode 2: the left stick is unused; the dpad/L1/L2/select instead step a
        // persistent selection one press at a time -- see HypercubeRenderer.navigateCell4Selection
        // and NavigationButton's doc. UI-thread-local so the toggle button's label updates
        // immediately; renderer.mode2Active is the GL-thread source of truth these closures defer
        // to once queued.
        var inputMode2Active = false

        gamepadInput = GamepadInputHandler(
            onLeftStick = { x, y ->
                if (!inputMode2Active) surfaceView.queueEvent { renderer.updateCell4Selection(x, y) }
            },
            onRightStick = { x, y -> renderer.stickX = x; renderer.stickY = y },
            onFaceButton = { _, _ -> },
            on4DRotationButton = { button ->
                surfaceView.queueEvent {
                    // Twisting without actively re-selecting via the stick (e.g. pressing a
                    // rotation button while it's centered, reusing the last selection) should
                    // still realign the view -- see HypercubeRenderer.snapViewToNearestCardinalOrientation.
                    renderer.snapViewToNearestCardinalOrientation()
                    val cell = renderer.selectedCell4
                    val fixAxis2 = if (button.literalAxis == cell.axis) Axis4.W else button.literalAxis
                    renderer.requestTwist(cell, fixAxis2, button.primaryPrime)
                }
            },
            on4DNavigate = { button ->
                surfaceView.queueEvent {
                    if (!renderer.mode2Active) {
                        // Mode 1: only the trigger does anything, moving the stick-selected
                        // cell to I (see HypercubeRenderer.requestMoveSelectedCellToI's doc).
                        if (button == NavigationButton.TRIGGER_L) {
                            renderer.snapViewToNearestCardinalOrientation()
                            renderer.requestMoveSelectedCellToI()
                        }
                    } else {
                        when (button) {
                            NavigationButton.LEFT -> renderer.navigateMode2Selection(HypercubeRenderer.AXIS_X, -1)
                            NavigationButton.RIGHT -> renderer.navigateMode2Selection(HypercubeRenderer.AXIS_X, 1)
                            NavigationButton.UP -> renderer.navigateMode2Selection(HypercubeRenderer.AXIS_Y, 1)
                            NavigationButton.DOWN -> renderer.navigateMode2Selection(HypercubeRenderer.AXIS_Y, -1)
                            NavigationButton.BUMPER_L -> renderer.navigateMode2Selection(HypercubeRenderer.AXIS_Z, 1)
                            NavigationButton.TRIGGER_L -> renderer.navigateMode2Selection(HypercubeRenderer.AXIS_Z, -1)
                            NavigationButton.SELECT -> {
                                renderer.snapViewToNearestCardinalOrientation()
                                renderer.requestMoveSelectedCellToI()
                            }
                        }
                    }
                }
            },
        )
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        val statusText = statusTextView(initiallySolved)
        renderer.onStateChanged = { solved ->
            runOnUiThread { statusText.text = if (solved) SOLVED_LABEL else "" }
        }

        renderer.onTwistApplied = { cell, fixAxis2, prime -> moveHistory4D.add(Triple(cell, fixAxis2, prime)) }

        val utilityColumn = utilityRow(
            onScramble = { surfaceView.queueEvent { renderer.requestScramble(SCRAMBLE_MOVE_COUNT_4D) }; moveHistory4D.clear() },
            onReset = { surfaceView.queueEvent { renderer.requestReset() }; moveHistory4D.clear() },
            onUndo = {
                synchronized(moveHistory4D) {
                    if (moveHistory4D.isNotEmpty()) {
                        val (cell, fixAxis2, prime) = moveHistory4D.removeAt(moveHistory4D.size - 1)
                        surfaceView.queueEvent { renderer.undoTwist(cell, fixAxis2, prime) }
                    }
                }
            },
            onShareLog = {
                val snapshot = synchronized(moveHistory4D) { moveHistory4D.toList() }
                shareTwistLog(formatTwistLog4D(snapshot))
            },
            orientation = LinearLayout.VERTICAL,
            onExportMC4D = {
                val snapshot = synchronized(moveHistory4D) { moveHistory4D.toList() }
                shareTwistLog(mc4dLogFile(snapshot))
            },
        )

        val filterColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun filterToggle(label: String, apply: (Boolean) -> Unit): Button =
                Button(this@MainActivity).apply {
                    text = label
                    alpha = 0.5f
                    var hidden = false
                    setOnClickListener {
                        hidden = !hidden
                        apply(hidden)
                        alpha = if (hidden) 1f else 0.5f
                        surfaceView.requestRender()
                    }
                }.also { addView(it) }
            filterToggle("Hide 4c") { renderer.hideCorners = it }
            filterToggle("Hide 3c") { renderer.hideEdges = it }
        }

        // Cell twists, camera rotation, and axis selection are gamepad-only now (see
        // GamepadInputHandler) -- the on-screen buttons for them were dropped because their
        // fixed two-sub-column-per-side layout didn't fit shorter/wider screens like the Retroid
        // Pocket (see retroid-twisted.png). What's left is just the handful of actions gamepad
        // input doesn't cover: mode switch, input scheme toggle, piece filtering, and the
        // scramble/reset/undo/export utility row.
        // Toggles between gamepad input mode 1 (stick-based selection) and mode 2 (dpad/L1/L2
        // step navigation, see the on4DNavigate wiring above) -- purely a left-hand input scheme
        // choice, so it only needs to update inputMode2Active (for these closures) and the
        // renderer's own mirrored flag (for highlightedCell/navigateCell4Selection); nothing
        // else about the screen changes.
        val inputModeButton = Button(this).apply {
            text = "Input: Stick"
            setOnClickListener {
                inputMode2Active = !inputMode2Active
                text = if (inputMode2Active) "Input: Pad" else "Input: Stick"
                surfaceView.queueEvent { renderer.setMode2Active(inputMode2Active) }
            }
        }

        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeToggleButton())
            addView(inputModeButton)
        }
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(utilityColumn)
            addView(filterColumn)
        }
        listOf(leftColumn, rightColumn).forEach { it.compactChildren() }

        rootLayout.addView(surfaceView)
        rootLayout.addView(statusText, topCenterParams())
        rootLayout.addView(leftColumn, centerStartParams())
        rootLayout.addView(rightColumn, centerEndParams())
        rootLayout.addView(gamepadOverlayView(), bottomStartParams())
    }

    /** Drag on the main view controls the ordinary 3D-feeling rotation, same as [handle3DDrag]. */
    private fun handle4DDrag(surfaceView: GLSurfaceView, renderer: HypercubeRenderer, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scaleGestureDetector.isInProgress) {
                    renderer.addDragDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                    surfaceView.requestRender()
                }
            }
        }
        return true
    }

    // --- shared UI helpers -------------------------------------------------------------------

    private fun statusTextView(initiallySolved: Boolean = true): TextView = TextView(this).apply {
        text = if (initiallySolved) SOLVED_LABEL else ""
        textSize = 18f
        setPadding(24, 16, 24, 16)
    }

    private fun modeToggleButton(): Button = Button(this).apply {
        text = if (is4DMode) "Go 3D" else "Go 4D"
        setOnClickListener {
            is4DMode = !is4DMode
            rebuildUi()
        }
    }

    private fun utilityRow(
        onScramble: () -> Unit,
        onReset: () -> Unit,
        onUndo: () -> Unit,
        onShareLog: () -> Unit,
        orientation: Int = LinearLayout.HORIZONTAL,
        onExportMC4D: (() -> Unit)? = null,
    ): LinearLayout =
        LinearLayout(this).apply {
            this.orientation = orientation
            addView(Button(this@MainActivity).apply { text = "Scramble"; setOnClickListener { onScramble() } })
            addView(Button(this@MainActivity).apply { text = "Reset"; setOnClickListener { onReset() } })
            addView(Button(this@MainActivity).apply { text = "Undo"; setOnClickListener { onUndo() } })
            addView(Button(this@MainActivity).apply { text = "Log"; setOnClickListener { onShareLog() } })
            if (onExportMC4D != null) {
                addView(Button(this@MainActivity).apply { text = "MC4D"; setOnClickListener { onExportMC4D() } })
            }
            addView(Button(this@MainActivity).apply { text = "Help"; setOnClickListener { showHelpDialog() } })
        }

    /** A scrollable documentation popup covering touch controls, both 3D and 4D gamepad schemes
     * (including 4D's two selectable input modes), the on-screen buttons, and the gamepad
     * overlay HUD -- the same content regardless of which screen the Help button was tapped
     * from, so switching between 3D/4D doesn't need separate variants. */
    private fun showHelpDialog() {
        val scroll = ScrollView(this)
        val textView = TextView(this).apply {
            text = Html.fromHtml(HELP_HTML, Html.FROM_HTML_MODE_LEGACY)
            textSize = 15f
            setPadding(32, 24, 32, 24)
        }
        scroll.addView(textView)
        AlertDialog.Builder(this)
            .setTitle("Help")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Shrinks every [Button] nested anywhere under this [ViewGroup] (recursing into nested
     * layouts, e.g. the small per-group columns build4DScreen nests into its two side columns) --
     * used for the 4D screen's side-column buttons, since the platform's default button padding/
     * text size is sized for a handful of buttons in a row, not 6-8 stacked down a short
     * landscape screen (this app's actual target form factor -- gamepad handhelds, not portrait
     * phones). */
    private fun ViewGroup.compactChildren() {
        for (i in 0 until childCount) {
            when (val child = getChildAt(i)) {
                is Button -> {
                    child.setPadding(20, 22, 20, 22)
                    child.textSize = 13f
                    child.minimumHeight = 0
                    child.minHeight = 0
                }
                is ViewGroup -> child.compactChildren()
            }
        }
    }

    /** Puts [log] on the clipboard and offers the Android share sheet for it -- used by both
     * modes' "Log" button to export their twist history (see `todo-controller-input.md`'s
     * milestone-6 notes for why this is our own plain-text notation, not a literal MagicCube4D
     * log file). */
    private fun shareTwistLog(log: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("twisted4d move log", log))
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(shareIntent, "Share move log"))
    }

    /** Collapses exactly-two-consecutive-*identical* moves (same move, same prime) into a single
     * "&lt;base&gt;2" token -- e.g. two "RU" moves in a row become "RU2" -- matching standard
     * twisty-puzzle double-turn notation. Two consecutive *opposite*-prime moves on the same
     * axis aren't a double turn (they're most of a cancellation), so those are deliberately left
     * alone: only exact repeats consolidate. */
    private fun <T> consolidateDoubles(moves: List<T>, baseNotation: (T) -> String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < moves.size) {
            if (i + 1 < moves.size && moves[i] == moves[i + 1]) {
                out.add(baseNotation(moves[i]).trimEnd('\'') + "2")
                i += 2
            } else {
                out.add(baseNotation(moves[i]))
                i += 1
            }
        }
        return out
    }

    /** e.g. "R F' U2" -- standard face notation, prime marks a counterclockwise twist, doubled
     * moves collapse via [consolidateDoubles]. */
    private fun formatTwistLog3D(history: List<Pair<Face, Boolean>>): String =
        consolidateDoubles(history) { (face, prime) -> face.label + (if (prime) "'" else "") }
            .joinToString(" ")

    /** Canonical single-cell representative for each axis, used to name fixAxis2 in
     * hypercubing.xyz notation -- an arbitrary but consistent choice, since either of an axis's
     * two cells names the same physical twist (just with the prime flipped). */
    private fun axisRepresentativeCell(axis: Axis4): Cell4 = when (axis) {
        Axis4.X -> Cell4.R
        Axis4.Y -> Cell4.U
        Axis4.Z -> Cell4.F
        Axis4.W -> Cell4.O
    }

    /** MC4D's own cell order, empirically reverse-engineered (not documented in MC4D's source --
     * it comes from an external geometry library's traversal order): both the "which 27-grip
     * block" index for a cell *and* the within-block ordering of ridge-piece grips (see
     * [mc4dGrip]) are positions in this exact sequence. */
    private val MC4D_CELL_ORDER = listOf(Cell4.I, Cell4.D, Cell4.F, Cell4.L, Cell4.R, Cell4.B, Cell4.U, Cell4.O)

    private fun mc4dOpposite(cell: Cell4): Cell4 = Cell4.entries.first { it.axis == cell.axis && it.sign == -cell.sign }

    /** MC4D's grip index for the "2c ridge" piece straddling [cell] and [axisRepresentativeCell]
     * of [fixAxis2] -- reverse-engineered from real MC4D log files (see the mc4d_log_compatibility
     * memory for the full derivation): `cellIndex*27 + 20 + position`, where 20 is the fixed
     * offset to the 6-slot "ridge" tier within a cell's 27-grip block, and position is where the
     * representative cell falls in [MC4D_CELL_ORDER] once [cell] and its own opposite are
     * removed (both cell index and ridge position use that same master order). */
    private fun mc4dGrip(cell: Cell4, fixAxis2: Axis4): Int {
        val cellIndex = MC4D_CELL_ORDER.indexOf(cell)
        val opposite = mc4dOpposite(cell)
        val remaining = MC4D_CELL_ORDER.filter { it != cell && it != opposite }
        val position = remaining.indexOf(axisRepresentativeCell(fixAxis2))
        return cellIndex * 27 + 20 + position
    }

    /** A real MagicCube4D `.log` file for [history], byte-for-byte in the format MC4D itself
     * reads/writes (confirmed against real MC4D output) -- unlike [formatTwistLog4D], this is
     * meant to be opened directly in MagicCube4D, not read by a person. Always uses the same
     * canonical representative cell per axis ([axisRepresentativeCell]) for every twist, since
     * `dir`'s sign is relative to *which grip* was clicked, not a universal CW/CCW -- e.g. `RD`
     * (non-prime) is `RU`'s (non-prime) inverse, confirmed against real MC4D, so consistently
     * using the same representative (never switching between an axis's two cells) is what keeps
     * this app's own `prime` flag mapping to a consistent `dir` sign throughout. `slicemask` is
     * always 1 (a single outer-layer twist, this app's only twist granularity). A 180-degree
     * double twist is two separate identical triples, not a special encoding -- confirmed real
     * MC4D does the same and doesn't consolidate them, even though its own turn counter and this
     * app's [formatTwistLog4D] both display doubled moves as a single "X2" for readability. The
     * view-orientation lines MC4D's header expects are filled with a fixed identity matrix --
     * they only restore the camera angle on load, not puzzle state, so any valid orientation
     * works. */
    private fun mc4dLogFile(history: List<Triple<Cell4, Axis4, Boolean>>): String {
        val header = "MagicCube4D 3 0 ${history.size} {4,3,3} 3"
        val identityViewMatrix = listOf(
            "1.0 0.0 0.0 0.0",
            "0.0 1.0 0.0 0.0",
            "0.0 0.0 1.0 0.0",
            "0.0 0.0 0.0 1.0",
        )
        val moves = history.joinToString(" ") { (cell, fixAxis2, prime) ->
            "${mc4dGrip(cell, fixAxis2)},${if (prime) 1 else -1},1"
        }
        return (listOf(header) + identityViewMatrix + listOf("*", "$moves.")).joinToString("\n")
    }

    /** e.g. "RU' RF RU2" -- hypercubing.xyz community notation: the twisted cell, then a
     * representative cell for the fixed second axis, prime for counterclockwise, doubled moves
     * collapsed via [consolidateDoubles]. Our own notation for undo/clipboard/share purposes --
     * see [mc4dLogFile] for actual MagicCube4D file compatibility, which needs MC4D's own
     * internal grip numbering, not this. */
    private fun formatTwistLog4D(history: List<Triple<Cell4, Axis4, Boolean>>): String =
        consolidateDoubles(history) { (cell, fixAxis2, prime) ->
            cell.label + axisRepresentativeCell(fixAxis2).label + (if (prime) "'" else "")
        }.joinToString(" ")

    private fun topCenterParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
    ).apply { topMargin = 24 }

    private fun topStartParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.START,
    ).apply { topMargin = 24; leftMargin = 24 }

    private fun topEndParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.END,
    ).apply { topMargin = 24; rightMargin = 24 }

    private fun bottomCenterParams(bottomMargin: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { this.bottomMargin = bottomMargin }

    private fun centerStartParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL or Gravity.START,
    ).apply { leftMargin = 12 }

    private fun centerEndParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL or Gravity.END,
    ).apply { rightMargin = 12 }

    private fun bottomStartParams() = FrameLayout.LayoutParams(
        (150 * resources.displayMetrics.density).toInt(),
        (130 * resources.displayMetrics.density).toInt(),
        Gravity.BOTTOM or Gravity.START,
    ).apply { leftMargin = 12; bottomMargin = 12 }

    /** A low-detail live gamepad HUD (see [GamepadOverlayView]) for confirming, after the fact
     * from a screen recording, exactly which physical control produced a given twist -- both
     * modes get one since both accept gamepad input. */
    private fun gamepadOverlayView(): GamepadOverlayView = GamepadOverlayView(this)

    // --- lifecycle / input dispatch ----------------------------------------------------------

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInput.handleMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        gamepadInput.handleKeyEvent(event)

        // Consume every gamepad-sourced key event ourselves rather than falling through to
        // super.dispatchKeyEvent(): GamepadInputHandler is the sole intended handler for
        // gamepad input, but an unconsumed event still reaches Android's default view-focus/
        // click handling, which can silently activate whatever on-screen Button currently has
        // focus. Confirmed via logcat: every "Up" rotation-button press was also clicking the
        // on-screen "XW" camera-rotate button (a 90-degree cubeOrientation4 rotation) through
        // this exact fallthrough -- fully deterministic, no touch involved, and specifically
        // what broke the second twist in a row (it silently reassigned which native cell the
        // already-selected room slot pointed at). This subsumes the older, narrower special
        // case for KEYCODE_BACK (some gamepads, e.g. the Retroid Pocket's controller, alias
        // face buttons like B with a synthetic BACK keycode for launcher-navigation
        // compatibility -- without consuming it, pressing B to twist R also exits the app via
        // the default Back behavior).
        // event.device?.sources, not event.source -- see GamepadInputHandler.handleKeyEvent's
        // matching comment: a gamepad's own D-pad events individually classify as SOURCE_DPAD,
        // not SOURCE_GAMEPAD, so checking only event.source let every D-pad press fall through
        // to super.dispatchKeyEvent() here -- i.e. exactly the same default-focus-navigation bug
        // this whole override exists to prevent, just for D-pad specifically.
        if (GamepadInputHandler.isGamepadSource(event.device?.sources ?: event.source)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        saveState()
    }

    /** Persists the active mode's puzzle state + twist history to [SAVE_FILE_NAME] in
     * [filesDir], so [loadState] can restore it on the next launch even after the process was
     * killed outright (home + swipe-away, not just backgrounding) -- onPause is used rather than
     * onStop/onDestroy since only onPause is reliably called in that case. Native state itself
     * is read directly via NativeLib (safe from any thread -- see cube3()/cube4()'s Mutex on the
     * Rust side), not through the renderer's GL-thread-only cached `currentTransforms`. */
    private fun saveState() {
        try {
            val stateJson = JSONArray()
            val historyJson = JSONArray()
            if (is4DMode) {
                NativeLib.cube4GetState().forEach { stateJson.put(it) }
                synchronized(moveHistory4D) {
                    moveHistory4D.forEach { (cell, fixAxis2, prime) ->
                        historyJson.put(JSONArray().put(cell.ordinal).put(fixAxis2.ordinal).put(prime))
                    }
                }
            } else {
                NativeLib.cubeGetState().forEach { stateJson.put(it) }
                synchronized(moveHistory3D) {
                    moveHistory3D.forEach { (face, prime) ->
                        historyJson.put(JSONArray().put(face.ordinal).put(prime))
                    }
                }
            }
            val solved = if (is4DMode) NativeLib.cube4IsSolved() else NativeLib.cubeIsSolved()
            val root = JSONObject()
                .put("mode4D", is4DMode)
                .put("state", stateJson)
                .put("history", historyJson)
                .put("solved", solved)
            File(filesDir, SAVE_FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save puzzle state", e)
        }
    }

    /** Inverse of [saveState] -- called once from [onCreate], before the first [rebuildUi],
     * setting [is4DMode] and the pending-restore fields that [build3DScreen]/[build4DScreen]
     * consume. Leaves everything untouched (fresh solved puzzle) if there's no save file or it
     * fails to parse. */
    private fun loadState() {
        try {
            val file = File(filesDir, SAVE_FILE_NAME)
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            is4DMode = root.getBoolean("mode4D")
            val stateJson = root.getJSONArray("state")
            val state = IntArray(stateJson.length()) { stateJson.getInt(it) }
            val historyJson = root.getJSONArray("history")
            val solved = root.optBoolean("solved", true)
            if (is4DMode) {
                pendingRestoreState4D = state
                pendingRestoreSolved4D = solved
                for (i in 0 until historyJson.length()) {
                    val entry = historyJson.getJSONArray(i)
                    moveHistory4D.add(
                        Triple(Cell4.entries[entry.getInt(0)], Axis4.entries[entry.getInt(1)], entry.getBoolean(2)),
                    )
                }
            } else {
                pendingRestoreState3D = state
                pendingRestoreSolved3D = solved
                for (i in 0 until historyJson.length()) {
                    val entry = historyJson.getJSONArray(i)
                    moveHistory3D.add(Face.entries[entry.getInt(0)] to entry.getBoolean(1))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load puzzle state", e)
        }
    }

    override fun onDestroy() {
        if (::gamepadInput.isInitialized) {
            inputManager.unregisterInputDeviceListener(gamepadInput)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Twisted4D"
        private const val DRAG_SENSITIVITY = 0.4f
        private const val SCRAMBLE_MOVE_COUNT_3D = 25
        private const val SCRAMBLE_MOVE_COUNT_4D = 25
        private const val SOLVED_LABEL = "SOLVED"
        private const val SAVE_FILE_NAME = "puzzle_state.json"

        private const val HELP_HTML = """
<b>TOUCH CONTROLS</b><br>
&#8226; Drag the puzzle to rotate the view<br>
&#8226; Pinch to zoom<br>
&#8226; Tap a cell/face button to twist; long-press for the reverse direction<br>
<br>
<b>3D MODE &#8212; GAMEPAD</b><br>
&#8226; Left stick: rotate the view<br>
&#8226; Y / A / X / B: twist U / D / L / R (screen-relative)<br>
&#8226; L1 / R1: twist F / B<br>
&#8226; Hold L2: reverse direction (prime) for any of the above<br>
<br>
<b>4D MODE &#8212; GAMEPAD</b><br>
Two selectable input modes &#8212; switch with the on-screen "Input: Stick" / "Input: Pad" button.<br>
<br>
<b>Mode 1 &#8212; Stick Select (default)</b><br>
&#8226; Left stick: select a cell (deflect toward it, release to keep the selection)<br>
&#8226; Right stick: orbit the view<br>
&#8226; Y / A / X / B: twist the selected cell (Up / Down / Left / Right)<br>
&#8226; R1 / R2 (bumper / trigger): twist the selected cell around its third axis<br>
&#8226; L2 (trigger): rotate the puzzle so the selected cell moves to I<br>
<br>
<b>Mode 2 &#8212; Pad Navigate</b><br>
&#8226; Left stick: unused<br>
&#8226; Right stick: orbit the view, same as mode 1<br>
&#8226; Y / A / X / B, R1 / R2: same as mode 1 &#8212; twist the highlighted cell<br>
&#8226; D-pad left / right: step the highlight between L, I, and R<br>
&#8226; D-pad up / down: step the highlight between U, I, and D<br>
&#8226; L1 (bumper): step the highlight toward F<br>
&#8226; L2 (trigger): step the highlight toward B<br>
&#8226; Each direction stops at its endpoint &#8212; no wraparound, and O can never be reached this way<br>
&#8226; Select button: rotate the puzzle so the highlighted cell moves to I<br>
&#8226; The highlighted cell is remembered separately per mode &#8212; switching away and back restores it<br>
<br>
<b>4D ON-SCREEN BUTTONS</b><br>
Cell twists and puzzle rotation are gamepad-only (see above) -- what's left on screen:<br>
&#8226; Hide 4c / Hide 3c: hide corner / edge pieces, useful early in a solve<br>
&#8226; Scramble / Reset / Undo<br>
&#8226; Log: copy/share the twist history in hypercubing.xyz notation (e.g. "RU'")<br>
&#8226; MC4D: export the twist history as a real MagicCube4D .log file, openable in the actual MagicCube4D software<br>
<br>
<b>GAMEPAD OVERLAY</b><br>
The small controller diagram in the bottom-left corner lights up buttons and sticks live as they're used &#8212; handy for confirming exactly which input produced a twist, e.g. when reviewing a screen recording.
"""
    }
}
