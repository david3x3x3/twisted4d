package dev.twisted4d.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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

    // Set by build4DScreen, read by sceneDumpReceiver -- lets an adb-triggered broadcast query
    // the live scene without any on-screen debug button (see sceneDumpReceiver's doc). Null in 3D
    // mode/before build4DScreen has run.
    private var hypercubeRenderer: HypercubeRenderer? = null

    /** Testing hook, not a user feature: `adb shell am broadcast -a dev.twisted4d.app.DUMP_SCENE`
     * logs every currently-visible sticker's room position and color (via
     * HypercubeRenderer.currentSceneColors), so a test can verify what's actually on screen from
     * native+orientation state directly -- no screenshot, no manual reading required. Added after
     * a debugging session where confirming a fix meant repeatedly asking the user to read specific
     * sticker colors off their own screen; this makes that queryable by adb instead. */
    private val sceneDumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val surfaceView = glSurfaceView ?: return
            val renderer = hypercubeRenderer ?: return
            surfaceView.queueEvent {
                renderer.currentSceneColors().forEach { s ->
                    Log.i(TAG, "SCENEDUMP wall=(${s.roomAxis},${s.roomSign}) pos=(${s.x},${s.y},${s.z}) color=${s.colorCell}")
                }
            }
        }
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // Twist history for the currently-built screen's mode -- written from the GL thread
    // (onTwistApplied) and read/cleared from the UI thread (undo/scramble/reset/log buttons),
    // so both need to be synchronizedList plus explicit `synchronized(...)` around compound
    // check-then-act sequences. Instance fields (not locals inside build3DScreen/build4DScreen)
    // so onPause can read the active mode's history to persist it -- see saveState/loadState.
    private val moveHistory3D = Collections.synchronizedList(mutableListOf<Pair<Face, Boolean>>())
    private val moveHistory4D = Collections.synchronizedList(mutableListOf<TwistRecord>())

    // How many of moveHistory4D's *leading* entries are scramble moves (see requestScramble) as
    // opposed to moves the player actually made -- lets mc4dLogFile mark that boundary with a
    // real MC4D-style "m|" token, matching what Edit > Go to Beginning jumps to in real MC4D, and
    // lets formatTwistLog4D's human-readable notation skip the scramble entirely. Written from
    // the GL thread inside onScramble's queueEvent block, read from the UI thread -- @Volatile
    // for the same reason moveHistory4D itself is a synchronizedList.
    @Volatile private var scrambleMoveCount4D = 0

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
        // EXPORTED, not NOT_EXPORTED -- adb's `am broadcast` couldn't reach a non-exported
        // receiver on this device/API level. Fine for what this is: a debug-only, read-only
        // logging hook (dumps sticker colors to logcat, never touches puzzle state), so another
        // app being able to trigger it is a non-issue.
        ContextCompat.registerReceiver(
            this, sceneDumpReceiver, IntentFilter("dev.twisted4d.app.DUMP_SCENE"), ContextCompat.RECEIVER_EXPORTED,
        )
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
        hypercubeRenderer = null
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
        rootLayout.addView(buildNumberLabel(), bottomEndParams())
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
        hypercubeRenderer = renderer
        val initiallySolved: Boolean
        if (pendingRestoreState4D == null) {
            moveHistory4D.clear()
            scrambleMoveCount4D = 0
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

        // STICK (default): left stick selects a cell continuously. PAD: the left stick is unused;
        // the dpad/L1/L2/select instead step a persistent selection one press at a time -- see
        // HypercubeRenderer.navigateCell4Selection and NavigationButton's doc. RKT: no selection
        // at all -- the left stick is unused and dpad/L1/L2 twist the room's current I slot
        // directly, right-hand buttons act on R as if it were selected -- see
        // HypercubeRenderer.requestRktITwist and GamepadInputMode's doc. UI-thread-local so the
        // toggle button's label updates immediately; renderer.inputMode is the GL-thread source of
        // truth these closures defer to once queued.
        var inputMode = GamepadInputMode.STICK

        gamepadInput = GamepadInputHandler(
            onLeftStick = { x, y ->
                if (inputMode == GamepadInputMode.STICK) surfaceView.queueEvent { renderer.updateCell4Selection(x, y) }
            },
            onRightStick = { x, y -> renderer.stickX = x; renderer.stickY = y },
            onFaceButton = { _, _ -> },
            on4DRotationButton = { button ->
                surfaceView.queueEvent {
                    // Twisting without actively re-selecting via the stick (e.g. pressing a
                    // rotation button while it's centered, reusing the last selection) should
                    // still realign the view -- see HypercubeRenderer.snapViewToNearestCardinalOrientation.
                    // Works unchanged for RKT too: renderer.selectedCell4/selectedRoomCell both
                    // already resolve to R there, since setInputMode pins selectedRoomAxis/Sign to
                    // R's slot.
                    renderer.snapViewToNearestCardinalOrientation()
                    val cell = renderer.selectedCell4
                    val fixAxis2 = renderer.resolveRotationButtonFixAxis2(button.literalAxis)
                    // rotationInvertedForCell corrects for a rendering property of the *wall* the
                    // twist is happening in, not the native cell occupying it -- see
                    // HypercubeRenderer.selectedRoomCell's doc for why this must be the room slot,
                    // not `cell` (native), once the room's been rotated away from default.
                    val roomCell = renderer.selectedRoomCell
                    val roomFixAxis2 = renderer.roomFixAxis2For(button.literalAxis)
                    val rawPrime = button.primaryPrime != Notation.rotationInvertedForCell(button, roomCell)
                    // rawPrime is the room-level, orientation-independent community-notation
                    // intent -- correctedPrimeForDisplay resolves it into that intent once here;
                    // correctedNativePrimeForRoomTwist then finds whichever native prime actually
                    // renders that intent for the *current* orientation (see its own doc for why a
                    // room-keyed table alone isn't enough once reoriented -- the confirmed
                    // reoriented-LU-renders-CCW bug).
                    val displayApostrophe = Notation.correctedPrimeForDisplay(roomCell, roomFixAxis2, rawPrime)
                    val prime = renderer.correctedNativePrimeForRoomTwist(cell, fixAxis2, roomCell, roomFixAxis2, displayApostrophe)
                    renderer.requestTwist(cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe)
                }
            },
            on4DNavigate = { button ->
                surfaceView.queueEvent {
                    when (inputMode) {
                        GamepadInputMode.STICK ->
                            // Only the left stick click does anything, moving the stick-selected
                            // cell to I (see HypercubeRenderer.requestMoveSelectedCellToI's doc).
                            if (button == NavigationButton.THUMB_L) {
                                renderer.snapViewToNearestCardinalOrientation()
                                renderer.requestMoveSelectedCellToI()
                            }
                        GamepadInputMode.PAD ->
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
                                NavigationButton.THUMB_L -> Unit
                            }
                        // Community notation: LEFT=IU, RIGHT=IU', UP=IR, DOWN=IR', BUMPER_L(L1)=IF,
                        // TRIGGER_L(L2)=IF'. SELECT is unbound -- no role specified for RKT mode.
                        // The X/Z axis pairs need prime flipped relative to what their label would
                        // naively suggest -- real-device-confirmed: Y (LEFT/RIGHT) was already
                        // correct, but X (UP/DOWN) and Z (BUMPER_L/TRIGGER_L) both twisted the
                        // right plane in the wrong direction until flipped. Requesting a "non-prime"
                        // twist on I doesn't consistently mean the same rotation sense across
                        // different fixAxis2 choices -- same root cause as the other per-cell/
                        // per-axis correction tables in this file (Cube4::twist's rotating-axis
                        // handedness is a mechanical function of axis index order, not something
                        // that adapts to match an external notation convention).
                        GamepadInputMode.RKT ->
                            when (button) {
                                NavigationButton.LEFT -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Y, false)
                                NavigationButton.RIGHT -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Y, true)
                                NavigationButton.UP -> renderer.requestRktITwist(HypercubeRenderer.AXIS_X, true)
                                NavigationButton.DOWN -> renderer.requestRktITwist(HypercubeRenderer.AXIS_X, false)
                                NavigationButton.BUMPER_L -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Z, true)
                                NavigationButton.TRIGGER_L -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Z, false)
                                NavigationButton.SELECT -> Unit
                                NavigationButton.THUMB_L -> Unit
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

        // Shows the most recent twist in community notation (e.g. "IF'") -- lets the notation
        // convention (see communityNotation's doc) be surveyed twist-by-twist directly against
        // what's on screen, without an MC4D export/import round trip for every single move.
        val lastMoveText = TextView(this).apply {
            textSize = 22f
            setPadding(24, 8, 24, 8)
        }

        renderer.onTwistApplied = { cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe ->
            val record = TwistRecord(cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe)
            moveHistory4D.add(record)
            val label = Notation.communityNotation(record)
            runOnUiThread { lastMoveText.text = label }
        }

        val utilityColumn = utilityRow(
            onScramble = {
                surfaceView.queueEvent {
                    // Scrambles have no button/room context of their own -- treat room as native
                    // (a reasonable fallback since a scramble always starts from a fresh, default
                    // orientation anyway; see the orientation-not-persisted memory note).
                    val scrambleMoves = renderer.requestScramble(SCRAMBLE_MOVE_COUNT_4D).map { (cell, fixAxis2, prime) ->
                        TwistRecord(cell, fixAxis2, prime, cell, fixAxis2.nativeIndex, Notation.correctedPrime(cell, fixAxis2, prime))
                    }
                    synchronized(moveHistory4D) {
                        moveHistory4D.clear()
                        moveHistory4D.addAll(scrambleMoves)
                    }
                    scrambleMoveCount4D = scrambleMoves.size
                }
            },
            onReset = {
                surfaceView.queueEvent { renderer.requestReset() }
                moveHistory4D.clear()
                scrambleMoveCount4D = 0
                lastMoveText.text = ""
            },
            onUndo = {
                synchronized(moveHistory4D) {
                    if (moveHistory4D.isNotEmpty()) {
                        val record = moveHistory4D.removeAt(moveHistory4D.size - 1)
                        surfaceView.queueEvent { renderer.undoTwist(record.cell, record.fixAxis2, record.prime) }
                    }
                }
            },
            onShareLog = {
                // Solve moves only -- hypercubing.xyz-style notation is for documenting/sharing a
                // solve, so this drops the scramble prefix (see mc4dLogFile for the file format
                // that does include it, marked with "m|").
                val snapshot = synchronized(moveHistory4D) { moveHistory4D.toList() }
                shareTwistLog(Notation.formatTwistLog4D(snapshot.drop(scrambleMoveCount4D.coerceAtMost(snapshot.size))))
            },
            orientation = LinearLayout.VERTICAL,
            onExportMC4D = {
                val snapshot = synchronized(moveHistory4D) { moveHistory4D.toList() }
                shareLogFile("twisted4d.log", Notation.mc4dLogFile(snapshot, scrambleMoveCount4D.coerceAtMost(snapshot.size)))
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
        // Cycles STICK -> PAD -> RKT -> STICK (see GamepadInputMode's doc for what each does) --
        // purely a left-hand input scheme choice, so it only needs to update inputMode (for these
        // closures) and the renderer's own mirrored state (for highlightedCell/
        // navigateCell4Selection/requestRktITwist); nothing else about the screen changes.
        fun inputModeLabel(mode: GamepadInputMode) = when (mode) {
            GamepadInputMode.STICK -> "Input: Stick"
            GamepadInputMode.PAD -> "Input: Pad"
            GamepadInputMode.RKT -> "Input: RKT"
        }
        val inputModeButton = Button(this).apply {
            text = inputModeLabel(inputMode)
            setOnClickListener {
                inputMode = when (inputMode) {
                    GamepadInputMode.STICK -> GamepadInputMode.PAD
                    GamepadInputMode.PAD -> GamepadInputMode.RKT
                    GamepadInputMode.RKT -> GamepadInputMode.STICK
                }
                text = inputModeLabel(inputMode)
                surfaceView.queueEvent { renderer.setInputMode(inputMode) }
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
        rootLayout.addView(lastMoveText, topStartParams())
        rootLayout.addView(leftColumn, centerStartParams())
        rootLayout.addView(rightColumn, centerEndParams())
        rootLayout.addView(gamepadOverlayView(), bottomStartParams())
        rootLayout.addView(buildNumberLabel(), bottomEndParams())
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

    /** Like [shareTwistLog], but shares [content] as an actual file named [filename] (via
     * [FileProvider], declared in AndroidManifest.xml/res/xml/file_paths.xml) instead of loose
     * clipboard text -- for the MC4D export specifically, where every receiving app having to
     * invent its own filename/extension for plain text meant it always came out as "....txt",
     * requiring a manual rename before MC4D would recognize it. Still also copies to the
     * clipboard, same as [shareTwistLog], since that's still handy on its own. */
    private fun shareLogFile(filename: String, content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(filename, content))
        val file = File(cacheDir, filename)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share $filename"))
    }

    /** e.g. "R F' U2" -- standard face notation, prime marks a counterclockwise twist, doubled
     * moves collapse via [Notation.consolidateDoubles]. */
    private fun formatTwistLog3D(history: List<Pair<Face, Boolean>>): String =
        Notation.consolidateDoubles(history) { (face, prime) -> face.label + (if (prime) "'" else "") }
            .joinToString(" ")

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

    private fun bottomEndParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.END,
    ).apply { bottomMargin = 12; rightMargin = 12 }

    /** Small, always-present build-identity label -- [BuildConfig.GIT_VERSION] is the exact
     * commit this APK was built from (short hash, "-dirty" suffix if the working tree had
     * uncommitted changes at build time -- see build.gradle.kts's `gitVersion`), so a bug report
     * that includes it can be traced straight back to source, and a tester can confirm on-screen
     * that the build they're looking at is actually the one just installed, instead of an
     * unnoticed stale/not-yet-synced APK (see the discuss_theories_before_acting memory for why
     * that ambiguity is worth eliminating). Deliberately dim/unobtrusive -- this is a testing aid,
     * not a feature. */
    private fun buildNumberLabel(): TextView = TextView(this).apply {
        text = "Build ${BuildConfig.GIT_VERSION}"
        textSize = 11f
        alpha = 0.4f
    }

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
                // Only the native fields round-trip -- room context isn't persisted (orientation
                // itself isn't restored across launches either; see the orientation-not-persisted
                // memory note), so restored history falls back to room==native, same as scrambles.
                synchronized(moveHistory4D) {
                    moveHistory4D.forEach { record ->
                        historyJson.put(JSONArray().put(record.cell.ordinal).put(record.fixAxis2.ordinal).put(record.prime))
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
                .put("scrambleCount4D", scrambleMoveCount4D)
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
                scrambleMoveCount4D = root.optInt("scrambleCount4D", 0)
                for (i in 0 until historyJson.length()) {
                    val entry = historyJson.getJSONArray(i)
                    val cell = Cell4.entries[entry.getInt(0)]
                    val fixAxis2 = Axis4.entries[entry.getInt(1)]
                    val prime = entry.getBoolean(2)
                    moveHistory4D.add(TwistRecord(cell, fixAxis2, prime, cell, fixAxis2.nativeIndex, Notation.correctedPrime(cell, fixAxis2, prime)))
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
        unregisterReceiver(sceneDumpReceiver)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Twisted4D"
        private const val DRAG_SENSITIVITY = 0.4f
        private const val SCRAMBLE_MOVE_COUNT_3D = 25
        private const val SCRAMBLE_MOVE_COUNT_4D = 250
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
Three selectable input modes &#8212; cycle with the on-screen "Input: Stick" / "Input: Pad" /
"Input: RKT" button.<br>
<br>
<b>Mode 1 &#8212; Stick Select (default)</b><br>
&#8226; Left stick: select a cell (deflect toward it, release to keep the selection)<br>
&#8226; Right stick: orbit the view<br>
&#8226; Y / A / X / B: twist the selected cell (Up / Down / Left / Right)<br>
&#8226; R1 / R2 (bumper / trigger): twist the selected cell around its third axis<br>
&#8226; Left stick click (L3): rotate the puzzle so the selected cell moves to I<br>
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
<b>Mode 3 &#8212; RKT</b><br>
For the final phase of a solve, where every twist is either an I-cell rotation or R itself &#8212;
no cell selection needed, so nothing is highlighted.<br>
&#8226; Left stick: unused<br>
&#8226; Right stick: orbit the view, same as the other modes<br>
&#8226; Y / A / X / B, R1 / R2: twist R, same as if it were selected in mode 1/2<br>
&#8226; D-pad left / right: twist I as IU / IU'<br>
&#8226; D-pad up / down: twist I as IR / IR'<br>
&#8226; L1 / L2 (bumper / trigger): twist I as IF / IF'<br>
&#8226; Select button: unused<br>
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
