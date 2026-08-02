package dev.twisted4d.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var inputManager: InputManager

    // Real device pixels the system bars (status bar, gesture/3-button nav bar) occupy right now
    // -- populated by the OnApplyWindowInsetsListener registered in onCreate, read by every
    // screen-edge-anchored *Params helper below (topStartParams, virtualClusterParams, etc.) so
    // their margins clear the system bars instead of overlapping them. Needed as of the API 36
    // target bump (2026-08-01): targeting 35+ enforces edge-to-edge display, so the OS no longer
    // auto-reserves system bar space the way it used to -- confirmed as a real regression via
    // emulator screenshot (the bottom-right build-number label and the portrait virtual
    // controller's own bottom edge both started rendering under/through the gesture nav bar).
    // Starts at NONE (zero on every side) since insets aren't known yet on the very first
    // `build4DScreen` call (they're dispatched asynchronously, after the initial layout pass) --
    // the listener re-triggers a layout refresh once the real values arrive.
    private var systemBarInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE
    private lateinit var gamepadInput: GamepadInputHandler

    private var glSurfaceView: GLSurfaceView? = null
    private var is4DMode = true

    // Built once and re-added to rootLayout on every rebuildUi() (rather than recreated), so their
    // open/highlight state would survive a rebuild if one ever happened while open -- see
    // build4DScreen's tile actions for why a rebuild-triggering tile (Play 3D Puzzle) explicitly
    // closes everything first regardless, since the *tiles* do need rebuilding (they close over
    // the old screen's renderer/surfaceView). 4D-only for now -- see StartMenuView's doc; 3D mode
    // keeps its existing on-screen buttons untouched pending its own planned rework.
    //
    // Two levels deep: startMenuView is the top-level Start Menu; filtersMenuView and
    // settingsMenuView are its two submenus so far (siblings, not nested under each other).
    // filtersMenuView is compact/puzzle-still-visible (see StartMenuView's doc on why Filters
    // specifically needs that style); settingsMenuView is fullscreen like the Start Menu itself,
    // a plain 1-column list of rows (gridCols=1). [activeMenu], [openMainMenu], [openFiltersMenu],
    // [openSettingsMenu], [closeAllMenus], and [goBackOneLevel] are the only places that should
    // ever call open()/close() on any of these -- they keep menuButton's visibility and the
    // "which one's open" invariant (at most one at a time) in one place instead of scattered
    // across every gamepad/touch/back-button entry point.
    private lateinit var startMenuView: StartMenuView
    private lateinit var filtersMenuView: StartMenuView
    private lateinit var settingsMenuView: StartMenuView

    // Persistent on-screen entry point into the Start Menu for controller-less users (see the
    // design doc's accessibility section) -- hidden while any menu is already open (per feedback:
    // it just sat there uselessly, and confusingly overlapped the Filters panel's own tiles).
    private lateinit var menuButton: Button

    // Class-level references (not just build3DScreen/build4DScreen locals) purely so
    // reapplyEdgeAnchoredLayouts can re-apply their LayoutParams once systemBarInsets is actually
    // known -- see that function's doc. Null in whichever mode/before-first-build isn't currently
    // active, same as the other nullable view fields.
    private var statusTextLabel: TextView? = null
    private var buildNumberLabelView: TextView? = null

    // Portrait-only interactive touch controller (see VirtualClusterView's class doc) -- built
    // once per build4DScreen call, alongside the landscape gamepadOverlayView/menuButton pair,
    // with only one of the two pairs visible at a time (see updateControlVisibility).
    // Null in 3D mode/before build4DScreen has run -- 3D mode keeps its own existing on-screen
    // buttons untouched, this is 4D-only for now, same scoping as startMenuView/filtersMenuView.
    private var virtualClusterLeft: VirtualClusterView? = null
    private var virtualClusterRight: VirtualClusterView? = null
    private var gamepadOverlay: GamepadOverlayView? = null

    // Needs a class-level reference (not just a build4DScreen local, like most of its siblings)
    // so updateControlVisibility can re-apply topStartParams() on every rotation --
    // see that function's doc for why a fresh LayoutParams every time (not just at build time)
    // matters: its landscape top margin depends on the virtual controller's current cluster
    // height, which is meaningless (and would go stale) for whichever orientation was active when
    // build4DScreen last ran. Null in 3D mode/before build4DScreen has run.
    private var lastMoveColumn: LinearLayout? = null

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

    /** Set on a fresh [MotionEvent.ACTION_DOWN] whose origin falls within the system's reserved
     * back/home gesture margins, so a swipe the OS treats as "reveal the hidden nav bar" doesn't
     * also get read as a camera-rotate drag -- see [isNearSystemGestureEdge]. */
    private var dragBlockedByEdgeSwipe = false

    // Drives the 4D screen's "Battery: N%" label -- see
    // GamepadInputHandler.currentGamepadBatteryFraction's doc for why this has to be polling
    // rather than a callback. batteryPollRunnable is nulled/cancelled at the top of rebuildUi so
    // a stale poll loop from a torn-down screen doesn't keep running (and doesn't stack up if the
    // user toggles 3D/4D repeatedly).
    private val batteryPollHandler = Handler(Looper.getMainLooper())
    private var batteryPollRunnable: Runnable? = null

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

    // How many of moveHistory4D's *leading* entries are currently applied to the puzzle -- added
    // 2026-07-26 for undo/redo. Decoupled from moveHistory4D.size once redo exists: undo just
    // moves this back without deleting anything (so redo can move it forward again); a genuinely
    // new move made while this isn't already at moveHistory4D.size truncates the list back to it
    // first, discarding whatever redo branch was pending -- standard undo/redo-stack semantics.
    // Always equal to moveHistory4D.size except mid-undo/redo. Same GL-thread-write
    // (onTwistApplied)/UI-thread-write (performUndo/performRedo/onScramble/onReset) split as
    // scrambleMoveCount4D, so @Volatile for the same reason.
    @Volatile private var historyIndex4D = 0

    // Guards performUndo/performRedo against overlapping GL-thread requests -- set true right
    // before queueing an undo/redo, cleared once that queued work actually runs (whether it
    // succeeded or was rejected because a previous twist/room-rotation was still animating).
    // Without this, historyIndex4D used to be advanced immediately/optimistically on the UI
    // thread for every press, regardless of whether the queued GL-thread call would actually
    // succeed -- a real repro (2026-07-30) sent 5 rapid undo presses (faster than the ~220ms
    // twist animation) and left historyIndex4D claiming "fully undone" while the puzzle was
    // still visibly scrambled, because some of those undos were silently dropped by
    // HypercubeRenderer.applyTwistInternal's own animating guard. Read/written from both threads
    // (UI thread checks/sets it before queueing; the GL thread clears it once the queued work
    // runs), so @Volatile for the same reason as historyIndex4D/scrambleMoveCount4D.
    @Volatile private var undoRedoInFlight = false

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

    /** The currently-open menu, if any -- at most one of [startMenuView]/[filtersMenuView]/
     * [settingsMenuView] is ever open at a time, enforced by only ever opening/closing them
     * through this file's [openMainMenu]/[openFiltersMenu]/[openSettingsMenu]/[closeAllMenus]
     * quartet. */
    private fun activeMenu(): StartMenuView? = when {
        filtersMenuView.isOpen -> filtersMenuView
        settingsMenuView.isOpen -> settingsMenuView
        startMenuView.isOpen -> startMenuView
        else -> null
    }

    /** Keeps [menuButton] visible at all times (unlike the original design, which hid it while
     * any menu was open -- reversed 2026-07-28: a controller-less player had no touch-only way to
     * close the Start Menu without picking a tile, or to back out of a submenu at all, once
     * hidden). Its label and tap action both change with menu depth instead: "Menu" opens the
     * Start Menu from closed; "Close" exits it entirely; "Back" steps up one level from a
     * submenu -- see the click listener in [rebuildUi]'s caller ([onCreate]) for the matching
     * action logic, which mirrors [goBackOneLevel]'s own one-level-at-a-time semantics. */
    private fun updateMenuButtonLabel() {
        menuButton.text = when {
            filtersMenuView.isOpen || settingsMenuView.isOpen -> "Back"
            startMenuView.isOpen -> "Close"
            else -> "Menu"
        }
        // See onMenuStateChangedForVirtualController's own doc for why this lives inside the one
        // function every menu open/close path already calls, rather than adding a new call at
        // each of openMainMenu/openFiltersMenu/openSettingsMenu/closeAllMenus individually.
        onMenuStateChangedForVirtualController?.invoke()
    }

    /** Set once by build4DScreen (to its local applyInputModeToVirtualController) -- lets
     * [updateMenuButtonLabel] (already the one function every menu open/close path in this file
     * calls) also keep the portrait virtual controller's left-cluster control in sync with menu
     * state, without those menu functions needing to reach into build4DScreen's local closures
     * directly. Null in 3D mode/before build4DScreen has run, same as the other virtual-
     * controller fields -- see applyInputModeToVirtualController's own doc for what this actually
     * does once invoked. */
    private var onMenuStateChangedForVirtualController: (() -> Unit)? = null

    private fun openMainMenu() {
        filtersMenuView.close()
        settingsMenuView.close()
        startMenuView.open()
        updateMenuButtonLabel()
    }

    private fun openFiltersMenu() {
        startMenuView.close()
        settingsMenuView.close()
        filtersMenuView.open()
        updateMenuButtonLabel()
    }

    private fun openSettingsMenu() {
        startMenuView.close()
        filtersMenuView.close()
        settingsMenuView.open()
        updateMenuButtonLabel()
    }

    private fun closeAllMenus() {
        startMenuView.close()
        filtersMenuView.close()
        settingsMenuView.close()
        updateMenuButtonLabel()
    }

    /** Start's gamepad binding: opens the Start Menu from fully closed, or closes everything
     * (regardless of how deep a submenu is open) if anything's already open -- a submenu's own
     * Back button is what steps up one level at a time instead (see [goBackOneLevel]). */
    private fun toggleTopLevelMenu() {
        if (activeMenu() != null) closeAllMenus() else openMainMenu()
    }

    /** Back's gamepad binding, and the OS/hardware Back button's (see [onBackPressed]) -- steps
     * up exactly one level of the menu hierarchy per the design doc's accessibility section
     * (submenu -> parent menu -> close menu -> resume puzzle), rather than closing everything at
     * once the way [toggleTopLevelMenu] does. */
    private fun goBackOneLevel() {
        if (filtersMenuView.isOpen || settingsMenuView.isOpen) openMainMenu() else closeAllMenus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "puzzle-core version: ${NativeLib.coreVersion()}")

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        PerControllerSettings.init(this)
        loadAppSettings()
        loadState()
        rootLayout = FrameLayout(this)
        setContentView(rootLayout)
        // See systemBarInsets's own doc for why this exists. Doesn't consume the insets (returns
        // them unchanged) -- the GL surface itself should stay genuinely full-screen/edge-to-edge
        // for the puzzle rendering, only the UI controls need to dodge the system bars, and they
        // each read systemBarInsets directly rather than relying on any padding applied here.
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, windowInsets ->
            systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            updateControlVisibility()
            updateMenuOverlayBounds()
            windowInsets
        }
        startMenuView = StartMenuView(this)
        // 3x3, not a 1x4 list -- a D-pad-as-stick controller (see GamepadInputHandler.onDpadStick's
        // doc) only ever reports -1/0/+1 per axis, never an intermediate value, so a single-axis
        // 4-item list has one structurally unreachable item on such controllers (3 possible
        // y-positions can't address 4 slots). 3x3 matches the main Start Menu's own grid, which
        // already relies on exactly this -1/0/+1-per-axis property working out to 3 bins per axis.
        filtersMenuView = StartMenuView(this, gridCols = 3, gridRows = 3, compact = true)
        // Also 3x3 for exactly the same D-pad-reachability reason as filtersMenuView above (a
        // 1-column list of 5 rows would leave 2 of them unreachable) -- fullscreen style, though,
        // like the main Start Menu, since Settings is a deliberate full screen rather than a
        // puzzle-still-visible overlay like Filters. Its 5 real rows land on the 4 cardinal
        // positions plus center -- see the tile list in build4DScreen for which setting goes where
        // and why (each single-direction-reachable, no diagonal needed).
        settingsMenuView = StartMenuView(this, gridCols = 3, gridRows = 3)
        menuButton = Button(this).apply {
            text = "Menu"
            // Same one-level-at-a-time semantics as goBackOneLevel(), reused directly: from
            // closed, opens the Start Menu; from any menu, steps up one level (or fully closes,
            // once already at the top) -- see updateMenuButtonLabel's doc for why this button is
            // now always visible, relabeled per state, rather than hidden while open.
            setOnClickListener { if (activeMenu() != null) goBackOneLevel() else openMainMenu() }
        }
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
        batteryPollRunnable?.let { batteryPollHandler.removeCallbacks(it) }
        batteryPollRunnable = null
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
        statusTextLabel = statusText
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
        gamepadOverlay = gamepadOverlayView()
        rootLayout.addView(gamepadOverlay, bottomStartParams())
        val buildLabel = buildNumberLabel()
        buildNumberLabelView = buildLabel
        rootLayout.addView(buildLabel, bottomEndParams())
    }

    private fun handle3DDrag(surfaceView: GLSurfaceView, renderer: CubeRenderer, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                dragBlockedByEdgeSwipe = isNearSystemGestureEdge(surfaceView, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scaleGestureDetector.isInProgress && !dragBlockedByEdgeSwipe) {
                    renderer.addDragDelta(dx * DRAG_SENSITIVITY, dy * DRAG_SENSITIVITY)
                    surfaceView.requestRender()
                }
            }
        }
        return true
    }

    /** True when ([x], [y]) falls inside the margin the OS reserves for back/home swipe
     * gestures -- e.g. the edge-reveal swipe for a nav bar hidden by fullscreen mode. Used to stop
     * that swipe from also being read as a drag-to-rotate gesture (see [dragBlockedByEdgeSwipe]).
     * Uses [WindowInsetsCompat.Type.mandatorySystemGestures] rather than a guessed dp margin since
     * the actual reserved size varies by device/OEM. */
    private fun isNearSystemGestureEdge(view: View, x: Float, y: Float): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            ?: return false
        return x < insets.left || x > view.width - insets.right ||
            y < insets.top || y > view.height - insets.bottom
    }

    // --- 4D mode -----------------------------------------------------------------------------

    private fun build4DScreen() {
        val renderer = HypercubeRenderer()
        hypercubeRenderer = renderer
        val initiallySolved: Boolean
        if (pendingRestoreState4D == null) {
            moveHistory4D.clear()
            scrambleMoveCount4D = 0
            historyIndex4D = 0
            initiallySolved = true
        } else {
            // A restored save never carries a partial undo/redo pointer -- see saveState/
            // loadState, which persist moveHistory4D itself but not a separate index -- so the
            // restored history is always treated as fully applied.
            historyIndex4D = moveHistory4D.size
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

        // Twists currently applied since the scramble (historyIndex4D, not moveHistory4D.size --
        // see historyIndex4D's doc for why those differ once undo/redo exist). Declared here,
        // ahead of gamepadInput below, so the Select-held undo/redo gamepad binding can call
        // performUndo/performRedo directly; the on-screen Undo button (in utilityColumn, further
        // down) uses the exact same two functions, so there's only one undo/redo implementation.
        val turnCountText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 8, 24, 0)
        }
        fun updateTurnCount() {
            turnCountText.text = "Turns: ${(historyIndex4D - scrambleMoveCount4D).coerceAtLeast(0)}"
        }
        updateTurnCount()

        /** Steps [historyIndex4D] back one and reverses that move -- see [historyIndex4D]'s doc.
         * A no-op once back at [scrambleMoveCount4D] -- the scramble itself was never meant to be
         * a real, undoable move sequence (it's a single instantaneous shuffle, not something the
         * player did one twist at a time), so undo stops at "freshly scrambled," not "solved."
         * Confirmed as a real bug via a repro (2026-07-30): undoing was able to walk all the way
         * back past the scramble to the original solved state, which shouldn't be reachable via
         * undo at all. Also a no-op while [undoRedoInFlight] -- see that field's doc: historyIndex4D
         * only advances once the queued GL-thread undo reports back that it actually applied. */
        fun performUndo() {
            if (undoRedoInFlight) return
            val targetIndex: Int
            val record: TwistRecord
            synchronized(moveHistory4D) {
                if (historyIndex4D <= scrambleMoveCount4D) return
                targetIndex = historyIndex4D - 1
                record = moveHistory4D[targetIndex]
            }
            undoRedoInFlight = true
            surfaceView.queueEvent {
                val applied = renderer.undoTwist(record.cell, record.fixAxis2, record.prime)
                if (applied) historyIndex4D = targetIndex
                undoRedoInFlight = false
                runOnUiThread { updateTurnCount() }
            }
        }

        /** Re-applies whatever move undo last stepped back over and advances [historyIndex4D]
         * again -- see [historyIndex4D]'s doc. A no-op once caught back up to the end of history
         * (nothing to redo, either because nothing was undone or a new move already overwrote the
         * abandoned branch), or while [undoRedoInFlight] -- same reasoning as [performUndo]. */
        fun performRedo() {
            if (undoRedoInFlight) return
            val targetIndex: Int
            val record: TwistRecord
            synchronized(moveHistory4D) {
                if (historyIndex4D >= moveHistory4D.size) return
                record = moveHistory4D[historyIndex4D]
                targetIndex = historyIndex4D + 1
            }
            undoRedoInFlight = true
            surfaceView.queueEvent {
                val applied = renderer.redoTwist(record.cell, record.fixAxis2, record.prime)
                if (applied) historyIndex4D = targetIndex
                undoRedoInFlight = false
                runOnUiThread { updateTurnCount() }
            }
        }

        // STICK (default): left stick (and/or d-pad, see GamepadInputHandler.onDpadStick)
        // selects a cell continuously. RKT: no selection at all -- the left stick is unused and
        // dpad/L1/L2 twist the room's current I slot directly, right-hand buttons act on R as if
        // it were selected -- see HypercubeRenderer.requestRktITwist and GamepadInputMode's doc.
        // (A third mode, PAD, existed until 2026-07-26 -- removed once STICK's d-pad support made
        // it redundant.) UI-thread-local so the toggle button's label updates immediately;
        // renderer.inputMode is the GL-thread source of truth these closures defer to once queued.
        var inputMode = GamepadInputMode.STICK

        // RKT mode's stick-driven I-twist (added 2026-07-28, alternative to the D-pad below) needs
        // its own edge/arm-detection, same hysteresis pattern GamepadInputHandler's own hat-axis
        // handling uses: pushing the stick to (near) full deflection in a direction twists once;
        // it must then return to (near) center before pushing that direction again twists a
        // second time -- position-based, not speed-based, so a slow deliberate push-return-push
        // works exactly the same as a fast one. To an 8BitDo-Micro-style D-pad user this should
        // feel identical to two separate D-pad presses. Independent per axis, same lifetime as
        // inputMode above.
        var rktStickArmedX = true
        var rktStickArmedY = true

        // Set on every onLeftStick call while a menu is open, so the *next* call after it closes
        // can detect the transition and resync the arm flags to the stick's actual current
        // position -- otherwise, confirming a tile while still holding the stick toward it (e.g.
        // holding right to pick RKT Mode) reads as "already past FIRE, armed" the instant RKT mode
        // takes over, and fires an unwanted twist immediately, even though the player never
        // pushed-and-returned. Bug reported 2026-07-28: switching to RKT mode via the menu while
        // holding the stick performed a twist nobody asked for.
        var menuWasOpenLastStick = false

        /** Left stick's continuous handling -- extracted (2026-08-01) so both the real gamepad's
         * onLeftStick wiring below and the portrait virtual controller's left-cluster stick (see
         * virtualClusterLeft further down) drive cell selection/menu highlight/RKT arm-disarm
         * through the exact same code, rather than two copies that could silently drift apart. */
        fun handleLeftStickInput(x: Float, y: Float) {
            val menu = activeMenu()
            if (menu != null) {
                // Radial-style menu selection (see StartMenuView.setHighlightFromStick's
                // doc): hold the stick toward a tile, the highlight follows immediately, then
                // press Confirm -- not an incremental "move the cursor one step" scheme, so no
                // edge/arm-detection is needed here, unlike RKT's push-and-return below.
                menu.setHighlightFromStick(x, y)
                menuWasOpenLastStick = true
                return
            }
            if (menuWasOpenLastStick) {
                // Resync, don't blindly re-arm: if the stick is still held past FIRE (toward
                // whichever tile was just confirmed), start disarmed on that axis so it can't
                // fire immediately -- only a genuine return-to-center afterward re-arms it.
                // Harmless (a no-op re-sync) in STICK mode, which doesn't read these flags.
                rktStickArmedX = abs(x) < RKT_STICK_FIRE_THRESHOLD
                rktStickArmedY = abs(y) < RKT_STICK_FIRE_THRESHOLD
                menuWasOpenLastStick = false
            }
            if (inputMode == GamepadInputMode.STICK) {
                surfaceView.queueEvent { renderer.updateCell4Selection(x, y) }
            } else if (inputMode == GamepadInputMode.RKT) {
                // A second control path to the *exact same* requestRktITwist calls the D-pad
                // branch below already makes -- same axis/prime mapping (LEFT/RIGHT -> Y-axis,
                // UP/DOWN -> X-axis), just triggered by push-to-near-full-and-return instead of
                // a button press. Not a separate feature, just an alternate way to reach it.
                if (abs(x) < RKT_STICK_REARM_THRESHOLD) {
                    rktStickArmedX = true
                } else if (rktStickArmedX && abs(x) > RKT_STICK_FIRE_THRESHOLD) {
                    surfaceView.queueEvent { renderer.requestRktITwist(HypercubeRenderer.AXIS_Y, x > 0) }
                    rktStickArmedX = false
                }
                if (abs(y) < RKT_STICK_REARM_THRESHOLD) {
                    rktStickArmedY = true
                } else if (rktStickArmedY && abs(y) > RKT_STICK_FIRE_THRESHOLD) {
                    surfaceView.queueEvent { renderer.requestRktITwist(HypercubeRenderer.AXIS_X, y > 0) }
                    rktStickArmedY = false
                }
            }
        }

        /** [RotationButton] dispatch (menu confirm/back, twists, Select-held whole-room
         * rotation) -- extracted (2026-08-01), same reasoning as [handleLeftStickInput]: shared
         * verbatim by the real gamepad's on4DRotationButton wiring below and the portrait virtual
         * controller's Y/X/B/A/R1/R2 taps (see virtualClusterRight further down). */
        fun handleRotationButton(button: RotationButton) {
            val menu = activeMenu()
            if (menu != null) {
                // Confirm/Back reuse the same already-Nintendo/Xbox-normalized A/B mapping
                // ROTATION_BUTTON_MAP always has (see GamepadInputHandler.layoutSwappedKeyCode's
                // doc): normalized-A always fires as RotationButton.DOWN, normalized-B as
                // RotationButton.RIGHT, regardless of GamepadVisualState.nintendoLayout --
                // that normalization is exactly what makes "physically-bottom button =
                // Confirm" hold on both Xbox- and Nintendo-position pads with no separate
                // menu-specific layout handling needed. Back steps up one level (see
                // goBackOneLevel's doc), not necessarily closing everything.
                when (button) {
                    RotationButton.DOWN -> menu.confirm()
                    RotationButton.RIGHT -> goBackOneLevel()
                    else -> Unit
                }
                return
            }
            // Read *now*, on the UI thread, at the exact instant the button was pressed --
            // not inside queueEvent's deferred block below, which runs on the GL thread
            // whenever it next gets a frame. Confirmed as a real bug via an adb
            // `input keycombination SELECT Y`-style repro: Select's own release (also
            // dispatched on the UI thread) could flip selectHeld back to false *before* the
            // GL thread got around to running Y's queued job, if the two were pressed and
            // released within the same handful of milliseconds -- exactly the fast
            // hold-then-tap-then-release pattern a real modifier-key press produces, so this
            // wasn't just an artifact of adb timing.
            val selectHeldAtPress = GamepadVisualState.selectHeld
            surfaceView.queueEvent {
                // Twisting without actively re-selecting via the stick (e.g. pressing a
                // rotation button while it's centered, reusing the last selection) should
                // still realign the view -- see HypercubeRenderer.snapViewToNearestCardinalOrientation.
                // Works unchanged for RKT too: renderer.selectedCell4/selectedRoomCell both
                // already resolve to R there, since setInputMode pins selectedRoomAxis/Sign to
                // R's slot.
                renderer.snapViewToNearestCardinalOrientation()

                // Select-held modifier (added 2026-07-26): reuses the same 6 physical
                // buttons/axis-sense pairing an individual twist uses, but rotates the *whole
                // room* 90 degrees instead of the selected cell -- same "feel" as the
                // Y/A/X/B/R1/R2 axis pairing already has, generalized from one piece to
                // everything at once (the 4D-room equivalent of WCA cube-rotation notation's
                // x/y/z, as opposed to a face turn). button.literalAxis is the one axis
                // excluded from the rotation (X excluded -> spins Y/Z; Y excluded -> spins
                // X/Z; Z excluded -> spins X/Y) -- deliberately never W/I/O, unlike "move to
                // I," since this is meant to feel like re-gripping the physical puzzle, not
                // reaching into it (confirmed correct 2026-07-26: I never moves under any of
                // these, and each axis's snap-rotated sense matches the sense that same
                // button already gives I when twisting it directly, unmodified).
                if (selectHeldAtPress) {
                    val spatialAxes = listOf(HypercubeRenderer.AXIS_X, HypercubeRenderer.AXIS_Y, HypercubeRenderer.AXIS_Z)
                    val (axisA, axisB) = spatialAxes.filter { it != button.literalAxis.nativeIndex }
                    // Y was correct with reverse = primaryPrime directly; X was confirmed
                    // backwards on real-device testing (the Y/A button pair), and Z was
                    // *assumed* correct rather than independently confirmed the same way --
                    // now also reported backwards on real-device testing (R1/R2, both of
                    // which share literalAxis Z) -- same real-device-handedness-correction
                    // pattern as every other per-axis table in this file
                    // (rotationInvertedForCell, the RKT X/Z prime flip, etc.).
                    val reverse = if (button.literalAxis == Axis4.X || button.literalAxis == Axis4.Z) {
                        !button.primaryPrime
                    } else {
                        button.primaryPrime
                    }
                    renderer.requestCameraRotate90(axisA, axisB, reverse = reverse)
                    return@queueEvent
                }

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
        }

        /** [NavigationButton] dispatch (Start, Select-held undo/redo, move-to-I, RKT twists) --
         * extracted (2026-08-01), same reasoning as [handleLeftStickInput]: shared verbatim by
         * the real gamepad's on4DNavigate wiring below and the portrait virtual controller's
         * Start/C/L1/L2 taps (see virtualClusterLeft/virtualClusterRight further down). */
        fun handleNavigateButton(button: NavigationButton) {
            // Start always toggles the *whole* menu system open/closed, regardless of how
            // deep a submenu is open -- takes priority over everything else below, including
            // the Select-held modifier. See toggleTopLevelMenu's doc for how this differs from
            // Back (which steps up one level at a time instead).
            if (button == NavigationButton.START) {
                toggleTopLevelMenu()
                return
            }
            // Swallow everything else while a menu's open (Select-held undo/redo, RKT-mode
            // twists, etc. below) -- highlight movement itself is handled continuously by
            // onLeftStick/onDpadStick above, not by these discrete button presses.
            if (activeMenu() != null) return
            // Same UI-thread-capture-before-queueEvent pattern as on4DRotationButton above,
            // and the same reason: checking a live GamepadVisualState flag from inside a
            // deferred GL-thread block risks a fast press-release window missing the modifier.
            val selectHeldAtPress = GamepadVisualState.selectHeld
            if (selectHeldAtPress && (button == NavigationButton.BUMPER_L || button == NavigationButton.TRIGGER_L)) {
                // Select-held modifier, undo/redo (added 2026-07-26) -- which trigger is which
                // doesn't matter functionally, L1=undo/L2=redo was an arbitrary pick. Applies
                // in every input mode, same as the Select+twist-button snap-rotation modifier
                // above (this check isn't gated on inputMode either), so it overrides
                // BUMPER_L/TRIGGER_L's normal RKT-mode IF/IF' twist while held.
                if (button == NavigationButton.BUMPER_L) performUndo() else performRedo()
            } else {
                surfaceView.queueEvent {
                    when (inputMode) {
                        GamepadInputMode.STICK ->
                            // Moves the stick-selected cell to I (see
                            // HypercubeRenderer.requestMoveSelectedCellToI's doc). THUMB_L
                            // (stick click) is the original control; BUTTON_C is a second, for
                            // controllers with no stick click to fall back on -- see
                            // NavigationButton.BUTTON_C's doc.
                            if (button == NavigationButton.THUMB_L || button == NavigationButton.BUTTON_C) {
                                renderer.snapViewToNearestCardinalOrientation()
                                renderer.requestMoveSelectedCellToI()
                            }
                        // Community notation: LEFT=IU, RIGHT=IU', UP=IR', DOWN=IR, BUMPER_L(L1)=IF,
                        // TRIGGER_L(L2)=IF'. SELECT/START/BUTTON_C/THUMB_L are unbound -- no role
                        // specified for RKT mode (no cell selection exists there to move to I).
                        // The X/Z axis pairs need prime flipped relative to what their label would
                        // naively suggest -- real-device-confirmed: Y (LEFT/RIGHT) was already
                        // correct, and Z (BUMPER_L/TRIGGER_L) needed flipping. X (UP/DOWN) was
                        // ALSO re-confirmed backwards a second time on real-hardware testing
                        // (2026-07-28, Retroid D-pad) after an earlier fix had flipped it the
                        // wrong way -- this is the corrected mapping. Requesting a "non-prime"
                        // twist on I doesn't consistently mean the same rotation sense across
                        // different fixAxis2 choices -- same root cause as the other per-cell/
                        // per-axis correction tables in this file (Cube4::twist's rotating-axis
                        // handedness is a mechanical function of axis index order, not something
                        // that adapts to match an external notation convention).
                        GamepadInputMode.RKT ->
                            when (button) {
                                NavigationButton.LEFT -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Y, false)
                                NavigationButton.RIGHT -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Y, true)
                                NavigationButton.UP -> renderer.requestRktITwist(HypercubeRenderer.AXIS_X, false)
                                NavigationButton.DOWN -> renderer.requestRktITwist(HypercubeRenderer.AXIS_X, true)
                                NavigationButton.BUMPER_L -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Z, true)
                                NavigationButton.TRIGGER_L -> renderer.requestRktITwist(HypercubeRenderer.AXIS_Z, false)
                                NavigationButton.SELECT -> Unit
                                NavigationButton.THUMB_L -> Unit
                                NavigationButton.START -> Unit
                                NavigationButton.BUTTON_C -> Unit
                            }
                    }
                }
            }
        }

        gamepadInput = GamepadInputHandler(
            onLeftStick = { x, y -> handleLeftStickInput(x, y) },
            onRightStick = { x, y -> renderer.stickX = x; renderer.stickY = y },
            // Lets controllers without a left stick still drive STICK mode's selection (and, while
            // a menu's open, the same radial highlight selection as the stick) -- always active
            // alongside the stick itself rather than a separate mode, since the two don't conflict
            // -- see GamepadInputHandler.onDpadStick's doc.
            onDpadStick = { x, y ->
                val menu = activeMenu()
                if (menu != null) {
                    menu.setHighlightFromStick(x, y)
                } else if (inputMode == GamepadInputMode.STICK) {
                    surfaceView.queueEvent { renderer.updateCell4Selection(x, y) }
                }
            },
            onFaceButton = { _, _ -> },
            on4DRotationButton = { button -> handleRotationButton(button) },
            on4DNavigate = { button -> handleNavigateButton(button) },
            // Swaps between the virtual touch controller and the real-gamepad HUD the instant a
            // pad connects/disconnects (see updateControlVisibility's doc) --
            // confirmed as a real gap via testing (2026-08-01): connecting a Bluetooth controller
            // did nothing until this existed, leaving the virtual controls on screen (and the
            // real-input HUD hidden) even with a pad actively in use.
            onGamepadConnectionChanged = { updateControlVisibility() },
        )
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        // Polled, not event-driven -- see GamepadInputHandler.currentGamepadBatteryFraction's
        // doc. GONE (not just blank text) when nothing to show, matching statusText's own
        // empty-when-nothing-to-say convention -- most controllers connected over USB, or devices
        // below API 31, will simply never populate this. GONE rather than merely-blank per
        // feedback (2026-08-01): a LinearLayout child with empty text still reserves its own
        // line height, leaving a visible gap and pushing inputModeText down for no reason --
        // every conditionally-blank field in lastMoveColumn below follows the same GONE pattern.
        val batteryText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 8, 24, 0)
            visibility = View.GONE
        }
        val pollBattery = object : Runnable {
            override fun run() {
                val fraction = gamepadInput.currentGamepadBatteryFraction()
                batteryText.text = if (fraction != null) "Battery: ${(fraction * 100).roundToInt()}%" else ""
                batteryText.visibility = if (fraction != null) View.VISIBLE else View.GONE
                batteryPollHandler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
            }
        }
        batteryPollRunnable = pollBattery
        pollBattery.run()

        val statusText = statusTextView(initiallySolved)
        statusTextLabel = statusText
        renderer.onStateChanged = { solved ->
            runOnUiThread { statusText.text = if (solved) SOLVED_LABEL else "" }
        }

        // Troubleshooting aid: which room the app will actually twist right now, and which native
        // cell currently occupies it -- see HypercubeRenderer.onSelectedCellChanged's doc. This
        // MUST always agree with the next twist's community-notation first letter, since both
        // come from the same selectedRoomCell value -- confirmed via a real repro where an
        // earlier version (showing the stick's raw, uncorrected wedge instead) visibly diverged
        // from the actual rotation. Updated live in any input mode, not just on twists.
        val selectedCellText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 0, 24, 8)
        }
        renderer.onSelectedCellChanged = { roomCell, nativeCell ->
            runOnUiThread { selectedCellText.text = "Selected ${roomCell.label}: showing ${nativeCell.label}" }
        }

        // Added once Hide 4c/Hide 3c became reachable from the Start Menu (see StartMenuView's
        // tiles below), not just the always-visible filterColumn buttons -- without this, a
        // filter left on has no on-screen reminder once its toggle isn't permanently visible.
        // GONE (see batteryText's doc for why) when neither filter is active.
        val filterStatusText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 0, 24, 8)
            visibility = View.GONE
        }
        fun updateFilterStatusText() {
            val active = listOfNotNull(
                "Centers".takeIf { renderer.hideCenters },
                "Ridges".takeIf { renderer.hideRidges },
                "3c Edges".takeIf { renderer.hideEdges },
                "4c Corners".takeIf { renderer.hideCorners },
            )
            filterStatusText.text = if (active.isEmpty()) "" else "Filter: ${active.joinToString(", ")}"
            filterStatusText.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
        }

        // Added once the Start Menu's Stick Mode/RKT Mode tiles stopped showing an active-state
        // color (per feedback that the green indicator was confusing) -- without this, there was
        // no on-screen way at all to tell which input mode is currently active.
        val inputModeText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 0, 24, 8)
        }
        fun updateInputModeText() {
            inputModeText.text = "Mode: " + when (inputMode) {
                GamepadInputMode.STICK -> "Stick"
                GamepadInputMode.RKT -> "RKT"
            }
        }
        updateInputModeText()

        renderer.onTwistApplied = { cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe ->
            val record = TwistRecord(cell, fixAxis2, prime, roomCell, roomFixAxis2, displayApostrophe)
            synchronized(moveHistory4D) {
                // A genuinely new move made while historyIndex4D isn't at the end (i.e. after one
                // or more undos with no matching redo) abandons whatever redo branch was pending
                // -- standard undo/redo-stack semantics, see historyIndex4D's doc.
                while (moveHistory4D.size > historyIndex4D) moveHistory4D.removeAt(moveHistory4D.size - 1)
                moveHistory4D.add(record)
                historyIndex4D = moveHistory4D.size
            }
            runOnUiThread { updateTurnCount() }
        }

        // The big last-move notation display (e.g. "RU'") that used to live here was removed
        // 2026-08-01 -- it was only ever a cell-selection-debugging aid (see communityNotation's
        // doc history) and David confirmed it's no longer needed day-to-day. Every remaining
        // field here is GONE (not just blank) when it has nothing to say -- see batteryText's doc
        // -- so this column always sits flush at the very top-left with no dead gaps between
        // whichever fields currently have content.
        val lastMoveColumnView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(turnCountText)
            addView(selectedCellText)
            addView(filterStatusText)
            addView(inputModeText)
            addView(batteryText)
        }
        lastMoveColumn = lastMoveColumnView

        /** Shared by the utility column's "MC4D" button and the Start Menu's "Export" tile (see
         * the menu design doc: Export always runs whichever format Settings specifies, which for
         * now -- until the Settings screen and its Export-format picker actually exist -- is
         * unconditionally MC4D, matching the doc's stated new default). Same historyIndex4D-not-
         * full-list reasoning as onShareLog. */
        fun doExportMC4D() {
            val snapshot = synchronized(moveHistory4D) { moveHistory4D.take(historyIndex4D) }
            shareLogFile("twisted4d.log", Notation.mc4dLogFile(snapshot, scrambleMoveCount4D.coerceAtMost(snapshot.size)))
        }

        /** The Settings screen's "Export Format" row picks between this and [doExportMC4D] --
         * hypercubing.xyz-style community notation, for sharing/documenting a solve rather than
         * opening in real MagicCube4D software. Same historyIndex4D-not-full-list/scramble-
         * dropping reasoning as doExportMC4D (this was the old on-screen "Log" button's logic,
         * unchanged). */
        fun doExportLog() {
            val snapshot = synchronized(moveHistory4D) { moveHistory4D.take(historyIndex4D) }
            shareTwistLog(Notation.formatTwistLog4D(snapshot.drop(scrambleMoveCount4D.coerceAtMost(snapshot.size))))
        }

        fun doScramble() {
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
                historyIndex4D = scrambleMoves.size
                runOnUiThread { updateTurnCount() }
            }
        }

        fun doReset() {
            surfaceView.queueEvent { renderer.requestReset() }
            moveHistory4D.clear()
            scrambleMoveCount4D = 0
            historyIndex4D = 0
            updateTurnCount()
        }

        // Settings submenu -- same cross-shaped 3x3 reachability layout as filtersMenuView (see
        // its doc), but fullscreen style: this is a deliberate full screen, not a puzzle-visible
        // overlay. Each row's label spells out its own current value directly (rebuilt on every
        // change via rebuildSettingsTiles) rather than relying on the border-color "active"
        // convention alone -- a settings list benefits from being unambiguous at a glance more than
        // the main menu's mode-select tiles do. D-pad-controls-cell-selection/D-pad-assignment
        // (the design doc's rows 1-2) are deliberately not here yet -- still an evolving,
        // unresolved design question per menu_system_plan memory, not an oversight.
        // Nintendo ABXY / Z Dir Left / Z Dir Right are per-controller (see PerControllerSettings'
        // doc) -- they read/write whichever controller most recently sent input, shown by name in
        // the top-left corner's info tile so it's always clear which pad a change will apply to.
        // Export Format/Confirm Scramble/Reset stay app-wide (AppSettings), since they're not
        // about any particular controller's quirks. Defined here, before the main tile list below,
        // so the SETTINGS tile can call it fresh on every open (see its onSelect) -- otherwise the
        // "Controller:" info could go stale if a different pad sent input between menu visits.
        fun rebuildSettingsTiles() {
            fun onOff(value: Boolean) = if (value) "On" else "Off"
            val activeDescriptor = PerControllerSettings.lastActiveDescriptor
            val activeEntry = PerControllerSettings.current()

            fun controllerToggleTile(
                label: String,
                get: (PerControllerSettings.Entry) -> Boolean,
                set: (PerControllerSettings.Entry, Boolean) -> Unit,
            ): MenuTile {
                val currentValue = activeEntry?.let(get) ?: false
                return MenuTile(
                    "$label\n${onOff(currentValue)}",
                    onSelect = controllerToggle@{
                        val descriptor = activeDescriptor
                        val entry = activeEntry
                        if (descriptor == null || entry == null) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(label)
                                .setMessage("Press a button on the controller you want to configure first, then reopen Settings.")
                                .setPositiveButton("OK", null)
                                .show()
                            return@controllerToggle
                        }
                        set(entry, !get(entry))
                        PerControllerSettings.save(descriptor, entry)
                        rebuildSettingsTiles()
                    },
                )
            }

            settingsMenuView.setTiles(
                listOf(
                    MenuTile(
                        "Controller:\n${activeEntry?.deviceName?.takeIf { it.isNotBlank() } ?: "(none yet)"}",
                        enabled = false,
                    ),
                    controllerToggleTile("Nintendo ABXY", { it.nintendoLayout }, { e, v -> e.nintendoLayout = v }),
                    null,
                    controllerToggleTile("Z Dir Left", { it.zDirLeft }, { e, v -> e.zDirLeft = v }),
                    MenuTile("Confirm Scramble/Reset\n${onOff(AppSettings.confirmBeforeScrambleReset)}", onSelect = {
                        AppSettings.confirmBeforeScrambleReset = !AppSettings.confirmBeforeScrambleReset
                        saveAppSettings()
                        rebuildSettingsTiles()
                    }),
                    controllerToggleTile("Z Dir Right", { it.zDirRight }, { e, v -> e.zDirRight = v }),
                    null,
                    MenuTile("Export Format\n${if (AppSettings.exportFormatIsMC4D) "MC4D" else "Log"}", onSelect = {
                        AppSettings.exportFormatIsMC4D = !AppSettings.exportFormatIsMC4D
                        saveAppSettings()
                        rebuildSettingsTiles()
                    }),
                    null,
                ),
            )
        }
        rebuildSettingsTiles()

        /** Wraps a virtual-button action so pressing it marks the on-screen controller as the
         * "current" one for [PerControllerSettings] purposes -- same as how every real gamepad
         * button/stick event already calls [PerControllerSettings.noteActiveDevice], so Settings'
         * "Controller:" display and its per-controller toggles (Z Dir Right, used for R1/R2
         * further down) follow touch input exactly the way they already follow whichever real pad
         * sent input most recently. Declared here (ahead of rebuildStartMenuTiles below, which
         * needs applyInputModeToVirtualController just below) rather than down where
         * virtualClusterLeft/Right actually get built -- Kotlin local functions must be declared
         * before whatever references them, even inside a not-yet-invoked closure. */
        fun virtualAction(action: () -> Unit): () -> Unit = {
            PerControllerSettings.noteVirtualControllerActive()
            action()
        }
        fun virtualStickAction(action: (Float, Float) -> Unit): (Float, Float) -> Unit = { x, y ->
            PerControllerSettings.noteVirtualControllerActive()
            action(x, y)
        }

        /** Swaps the left cluster's main control between [VirtualClusterView.MainControl.Stick]
         * (STICK mode -- drives cell selection; also used in RKT mode *while a menu is open*, see
         * below) and [VirtualClusterView.MainControl.DPad] (RKT mode with no menu open -- RKT has
         * no cell-selection role at all, so a stick would just sit there inert; a D-pad instead
         * reaches the exact same requestRktITwist calls the real D-pad already does, via
         * handleNavigateButton's RKT branch).
         *
         * The stick-while-a-menu's-open exception (added 2026-08-01, David's idea) works around a
         * real gap the D-pad has no fix for: a real gamepad's D-pad drives menu navigation through
         * a separate always-on channel (GamepadInputHandler.onDpadStick) that works regardless of
         * inputMode, but the virtual D-pad's 4 arrow buttons call handleNavigateButton directly,
         * which explicitly swallows everything while a menu's open -- so RKT mode's virtual D-pad
         * had no way to navigate menus at all (see the virtual_dpad_no_menu_nav memory). Rather
         * than teach the D-pad to synthesize a stick-like value (it has no held/combined-direction
         * state to do that with -- see that memory for why), just show the actual stick while any
         * menu's open: handleLeftStickInput already drives menu.setHighlightFromStick before it
         * ever looks at inputMode, so this needs zero new dispatch logic, only this swap.
         *
         * Called whenever inputMode changes (Start Menu's Mode toggle tile's onSelect) AND
         * whenever menu open/close state changes (via onMenuStateChangedForVirtualController,
         * itself invoked from updateMenuButtonLabel -- see its doc) -- both need to re-evaluate
         * this same STICK-vs-DPad decision. virtualClusterLeft is still null the first time this
         * runs (called once further down, right after it's actually built, to cover the merely-
         * hypothetical case of a future non-STICK initial inputMode -- today inputMode always
         * starts STICK with no menu open, so that first call is a no-op). */
        fun applyInputModeToVirtualController() {
            val showDpad = inputMode == GamepadInputMode.RKT && activeMenu() == null
            virtualClusterLeft?.mainControl = if (showDpad) {
                VirtualClusterView.MainControl.DPad(
                    onUpTap = virtualAction { handleNavigateButton(NavigationButton.UP) },
                    onLeftTap = virtualAction { handleNavigateButton(NavigationButton.LEFT) },
                    onRightTap = virtualAction { handleNavigateButton(NavigationButton.RIGHT) },
                    onDownTap = virtualAction { handleNavigateButton(NavigationButton.DOWN) },
                )
            } else {
                VirtualClusterView.MainControl.Stick(onChanged = virtualStickAction { x, y -> handleLeftStickInput(x, y) })
            }
        }
        onMenuStateChangedForVirtualController = ::applyInputModeToVirtualController

        // Start Menu tile grid (see StartMenuView's doc and the menu/settings design doc in
        // memory) -- row-major, matching the wireframe's layout. Rebuilt fresh every
        // build4DScreen call since the tiles close over this screen's renderer/surfaceView/
        // inputMode, none of which survive a rebuild. This -- plus filtersMenuView below and
        // gamepad bindings elsewhere -- is now the *only* way to reach any of this: every old
        // on-screen button (mode toggle, input-mode cycle, scramble/reset/undo/log/MC4D/help,
        // filter toggles) has been removed from this screen per feedback ("everything is going to
        // the menus or controller bindings"). Two exceptions still lack a menu home and are a
        // known gap, not an oversight -- see menu_system_plan memory: Z Dir and Nintendo ABXY
        // (belong on the real Settings screen, not built yet) and Undo (deliberately kept menu-
        // less per the design doc -- Select+L1 is its only binding -- but that leaves no touch-only
        // path now that its on-screen button is gone).
        //
        // Stick Mode/RKT Mode used to be two separate tiles (top-right and middle-right); merged
        // into a single toggle at middle-right per feedback (2026-08-01) -- top-right is now a
        // reserved/unused `null` slot rather than repurposed for something else yet. Wrapped in a
        // named, re-callable function (same rebuild-on-change pattern as rebuildSettingsTiles)
        // since -- unlike every other tile here -- the toggle's own label needs to reflect
        // whichever mode is *currently* active, not just fire an action once.
        fun rebuildStartMenuTiles() {
            startMenuView.setTiles(
                listOf(
                    MenuTile("Filters", onSelect = { openFiltersMenu() }),
                    MenuTile("Scramble", onSelect = {
                        if (AppSettings.confirmBeforeScrambleReset) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Scramble?")
                                .setMessage("This will scramble the puzzle.")
                                .setPositiveButton("Scramble") { _, _ -> closeAllMenus(); doScramble() }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            closeAllMenus()
                            doScramble()
                        }
                    }),
                    null,
                    // "Play 3D Puzzle" removed from here 2026-08-01 (per David: 3D mode isn't
                    // ready to show new users yet, and having it one tap away was confusing) --
                    // build3DScreen/CubeRenderer/modeToggleButton etc. are all still fully intact,
                    // just not reachable from this menu for now. Reserved/unused slot, not
                    // repurposed, matching the top-right slot's own null next to the Mode toggle.
                    null,
                    MenuTile("SETTINGS", onSelect = { rebuildSettingsTiles(); openSettingsMenu() }),
                    MenuTile("Mode:\n${if (inputMode == GamepadInputMode.STICK) "Stick" else "RKT"}", onSelect = {
                        inputMode = if (inputMode == GamepadInputMode.STICK) GamepadInputMode.RKT else GamepadInputMode.STICK
                        surfaceView.queueEvent { renderer.setInputMode(inputMode) }
                        updateInputModeText()
                        applyInputModeToVirtualController()
                        rebuildStartMenuTiles()
                        closeAllMenus()
                    }),
                    MenuTile("Export", onSelect = {
                        closeAllMenus()
                        if (AppSettings.exportFormatIsMC4D) doExportMC4D() else doExportLog()
                    }),
                    MenuTile("Help", onSelect = {
                        closeAllMenus()
                        showHelpDialog()
                    }),
                    MenuTile("Reset", onSelect = {
                        if (AppSettings.confirmBeforeScrambleReset) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Reset?")
                                .setMessage("This will reset the puzzle to solved.")
                                .setPositiveButton("Reset") { _, _ -> closeAllMenus(); doReset() }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            closeAllMenus()
                            doReset()
                        }
                    }),
                ),
            )
        }
        rebuildStartMenuTiles()

        // Filters submenu -- compact style (see StartMenuView's doc) so the puzzle stays visible
        // *and* draggable while adjusting these, per feedback that the old fullscreen-style
        // submenu hid it entirely. One toggle per piece type, by sticker count (see
        // HypercubeRenderer.hideCorners's doc): Centers(1)/Ridges(2)/3c Edges(3)/4c Corners(4).
        // Cross-shaped: the 4 real toggles sit at the 4 cardinal positions (each reachable by
        // holding a single D-pad direction, no diagonal needed), center and all 4 corners
        // reserved/empty. See filtersMenuView's construction doc for why this needs to be a 3x3
        // at all rather than a simpler 1x4 list.
        filtersMenuView.setTiles(
            listOf(
                null,
                MenuTile("Centers", isActive = { renderer.hideCenters }, onSelect = {
                    renderer.hideCenters = !renderer.hideCenters
                    surfaceView.requestRender()
                    updateFilterStatusText()
                }),
                null,
                MenuTile("Ridges", isActive = { renderer.hideRidges }, onSelect = {
                    renderer.hideRidges = !renderer.hideRidges
                    surfaceView.requestRender()
                    updateFilterStatusText()
                }),
                null,
                MenuTile("3c Edges", isActive = { renderer.hideEdges }, onSelect = {
                    renderer.hideEdges = !renderer.hideEdges
                    surfaceView.requestRender()
                    updateFilterStatusText()
                }),
                null,
                MenuTile("4c Corners", isActive = { renderer.hideCorners }, onSelect = {
                    renderer.hideCorners = !renderer.hideCorners
                    surfaceView.requestRender()
                    updateFilterStatusText()
                }),
                null,
            ),
        )

        rootLayout.addView(surfaceView)
        rootLayout.addView(statusText, topCenterParams())
        rootLayout.addView(lastMoveColumnView, topStartParams())
        gamepadOverlay = gamepadOverlayView()
        rootLayout.addView(gamepadOverlay, bottomStartParams())
        val buildLabel = buildNumberLabel()
        buildNumberLabelView = buildLabel
        rootLayout.addView(buildLabel, bottomEndParams())
        // Persistent, always-tappable entry point for controller-less users (see the design
        // doc's accessibility section and updateMenuButtonLabel's doc) -- always visible, relabeled
        // Menu/Close/Back by current menu depth. The gamepad Start button opens/closes the same
        // menu via toggleTopLevelMenu() in on4DNavigate above. Hidden in portrait -- see
        // updateControlVisibility's doc for why the virtual controller's own Start
        // pill makes this redundant there.
        updateMenuButtonLabel()
        rootLayout.addView(menuButton, bottomCenterParams(bottomMargin = 12))

        // Portrait-only virtual controller (see VirtualClusterView's class doc and the
        // handleLeftStickInput/handleRotationButton/handleNavigateButton functions above, which
        // this reuses verbatim rather than reimplementing any dispatch logic). Built here,
        // unconditionally, same as the landscape gamepadOverlay/menuButton pair above -- only
        // *visibility* differs by orientation (see updateControlVisibility), so
        // switching orientation never needs to tear down/rebuild either pair. virtualAction/
        // virtualStickAction/applyInputModeToVirtualController are declared earlier (see their
        // docs, up by rebuildStartMenuTiles) rather than here where they're first *used*.

        /** R1/R2's *labels* stay fixed to the 8BitDo Micro's own physical layout (R2 left of R1),
         * but which literal twist each performs is resolved through the exact same per-controller
         * Z Dir Right toggle a real pad's backwards-feeling shoulder buttons already use (see
         * GamepadInputHandler.zDirSwappedKeyCode's doc) -- confirmed backwards on real-device
         * testing (2026-08-01, David's Pixel), and PerControllerSettings.loadEntry now defaults
         * this one descriptor's zDirRight to true specifically because of that, rather than
         * hardcoding a one-off swap here that Settings could never re-expose or undo. */
        fun handleVirtualR1Tap() {
            PerControllerSettings.noteVirtualControllerActive()
            val swapped = PerControllerSettings.current()?.zDirRight == true
            handleRotationButton(if (swapped) RotationButton.TRIGGER_R else RotationButton.BUMPER_R)
        }
        fun handleVirtualR2Tap() {
            PerControllerSettings.noteVirtualControllerActive()
            val swapped = PerControllerSettings.current()?.zDirRight == true
            handleRotationButton(if (swapped) RotationButton.BUMPER_R else RotationButton.TRIGGER_R)
        }

        virtualClusterLeft = VirtualClusterView(
            context = this,
            topLeftLabel = "About",
            topRightLabel = "Select",
            onTopLeftTap = virtualAction { showAboutDialog() },
            // A true hold, not a tap -- writes the same GamepadVisualState.selectHeld flag a
            // real Select button press/release does, so handleRotationButton/handleNavigateButton
            // (both already keyed off that one flag) pick up the modifier with zero extra code:
            // hold Select + tap a twist button for whole-room rotation, or + L1/L2 for undo/redo,
            // exactly like the physical controller.
            onTopRightTap = virtualAction { GamepadVisualState.selectHeld = true },
            onTopRightRelease = virtualAction { GamepadVisualState.selectHeld = false },
            // L1 left of L2, matching the 8BitDo Micro's own physical shoulder layout.
            shoulderLeftLabel = "L1",
            shoulderRightLabel = "L2",
            // Mode-aware, per David's live-testing feedback (2026-08-01): unmodified L1/L2 have
            // no role at all in STICK mode (see handleNavigateButton's doc), so defaulting them
            // to undo/redo there is pure upside -- but in RKT mode they're real, load-bearing
            // controls (the Z/Z' twist), so hardcoding them to undo/redo *always* silently took
            // away touch access to that twist entirely. Routing through handleNavigateButton in
            // RKT mode restores it, Select-held-modifier and all (Select+L1/L2 still means undo/
            // redo even in RKT, exactly like the physical controller -- see its doc).
            onShoulderLeftTap = virtualAction {
                if (inputMode == GamepadInputMode.RKT) handleNavigateButton(NavigationButton.BUMPER_L) else performUndo()
            },
            onShoulderRightTap = virtualAction {
                if (inputMode == GamepadInputMode.RKT) handleNavigateButton(NavigationButton.TRIGGER_L) else performRedo()
            },
            mainControl = VirtualClusterView.MainControl.Stick(onChanged = virtualStickAction { x, y -> handleLeftStickInput(x, y) }),
        )
        virtualClusterRight = VirtualClusterView(
            context = this,
            topLeftLabel = "Start",
            topRightLabel = "C",
            onTopLeftTap = virtualAction { toggleTopLevelMenu() },
            onTopRightTap = virtualAction { handleNavigateButton(NavigationButton.BUTTON_C) },
            // R2 left of R1, matching the 8BitDo Micro's own physical shoulder layout -- see
            // handleVirtualR1Tap/handleVirtualR2Tap's doc for why *which twist* each performs is
            // resolved dynamically instead of hardcoded here.
            shoulderLeftLabel = "R2",
            shoulderRightLabel = "R1",
            onShoulderLeftTap = { handleVirtualR2Tap() },
            onShoulderRightTap = { handleVirtualR1Tap() },
            mainControl = VirtualClusterView.MainControl.FaceDiamond(
                topLabel = "Y", onTopTap = virtualAction { handleRotationButton(RotationButton.UP) },
                leftLabel = "X", onLeftTap = virtualAction { handleRotationButton(RotationButton.LEFT) },
                rightLabel = "B", onRightTap = virtualAction { handleRotationButton(RotationButton.RIGHT) },
                bottomLabel = "A", onBottomTap = virtualAction { handleRotationButton(RotationButton.DOWN) },
            ),
        )
        rootLayout.addView(virtualClusterLeft, virtualClusterParams(startSide = true))
        rootLayout.addView(virtualClusterRight, virtualClusterParams(startSide = false))
        // See applyInputModeToVirtualController's doc (declared earlier, by rebuildStartMenuTiles)
        // -- a no-op today since inputMode always starts STICK, but harmless/correct insurance if
        // that ever changes.
        applyInputModeToVirtualController()
        updateControlVisibility()

        // Added last so each draws (and, once VISIBLE, receives touch) above every view before it.
        // startMenuView/settingsMenuView use menuOverlayParams (see its doc) instead of a blanket
        // MATCH_PARENT, so they never cover the portrait control bar; filtersMenuView keeps plain
        // MATCH_PARENT -- its compact style already doesn't consume touches outside its own small
        // panel (see StartMenuView's class doc), so it was never able to block the control bar to
        // begin with.
        rootLayout.addView(startMenuView, menuOverlayParams())
        rootLayout.addView(filtersMenuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(settingsMenuView, menuOverlayParams())
    }

    /** Drag on the main view controls the ordinary 3D-feeling rotation, same as [handle3DDrag]. */
    private fun handle4DDrag(surfaceView: GLSurfaceView, renderer: HypercubeRenderer, event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                dragBlockedByEdgeSwipe = isNearSystemGestureEdge(surfaceView, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!scaleGestureDetector.isInProgress && !dragBlockedByEdgeSwipe) {
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

    /** A scrollable documentation popup covering 4D's gamepad scheme (including its two
     * selectable input modes), the on-screen buttons, and the gamepad overlay HUD. 3D mode's own
     * section was removed 2026-08-01 alongside its Start Menu entry point (see the tile list's
     * doc) -- this can still be reached directly (build3DScreen's own Help button), so its doc
     * staying generic/mode-agnostic rather than assuming 4D is intentional, not an oversight. */
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

    /** The virtual controller's "About" pill opens this instead of the full [showHelpDialog] --
     * deliberately much shorter (2026-08-01, per David: it used to just open the same full Help
     * screen, which he felt wasn't the right first thing for a brand-new, controller-less player
     * to see). Just enough to orient them: app name/version, a nudge that a real gamepad is the
     * intended/more comfortable way to play (matches the original design intent for this button,
     * from the virtual-controller planning discussion: "show new users the app is usable without
     * a physical controller" -- not that touch play is the *recommended* way), and where to find
     * the actual instructions. About is touch-only -- there's no physical-gamepad binding for it,
     * matching how it only exists on the virtual controller in the first place. */
    private fun showAboutDialog() {
        val message = """
            <b>Twisted 4D</b> &#8212; version ${BuildConfig.VERSION_NAME}<br>
            <br>
            This game is designed for a Bluetooth or USB game controller, and is more precise and
            comfortable to play that way -- these on-screen touch controls are a convenience for
            playing without one.<br>
            <br>
            For full instructions, press <b>Start</b>, then <b>Help</b>.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY))
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
    ).apply { topMargin = 24 + systemBarInsets.top }

    /** In landscape, the virtual controller's left cluster is vertically centered (see
     * [virtualClusterParams]) and tall enough (~68% of a typical landscape height) that pushing
     * this text below its full height would waste most of the screen -- so instead of dodging
     * vertically, this text starts to the *right* of the cluster's own right edge in landscape,
     * keeping its normal top-left position otherwise (confirmed via emulator screenshot that the
     * puzzle itself starts well to the right of that point too, so this doesn't create a new
     * overlap there either). An earlier attempt pushed this down by the cluster's top-edge
     * position instead -- looked right on paper but still overlapped the cluster's top-row pills
     * in practice, since clearing just the cluster's top *edge* isn't the same as clearing the
     * pills' own height. Only applies while the cluster is actually the thing showing there (see
     * [updateControlVisibility]'s doc) -- with a real gamepad connected, the small
     * [gamepadOverlay] HUD box takes its place instead, which doesn't reach anywhere near this
     * corner, so clearing space for the (now-hidden) cluster would just waste it for nothing.
     * Portrait doesn't need any of this: the cluster sits at the bottom there, nowhere near this
     * corner. Harmless for build3DScreen's modeToggleButton (this function's other caller) too --
     * 3D mode has no virtual controller to clear, so this just nudges that button right slightly
     * in landscape for no real reason, not a problem worth special-casing around. */
    private fun topStartParams(): FrameLayout.LayoutParams {
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        )
        params.topMargin = 24 + systemBarInsets.top
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        params.leftMargin = systemBarInsets.left + if (portrait || GamepadInputHandler.anyGamepadConnected()) {
            24
        } else {
            val marginPx = 12 * resources.displayMetrics.density
            (clusterWidthPx() + marginPx * 2 + 24).toInt()
        }
        return params
    }

    private fun topEndParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.END,
    ).apply { topMargin = 24 + systemBarInsets.top; rightMargin = 24 + systemBarInsets.right }

    private fun bottomCenterParams(bottomMargin: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { this.bottomMargin = bottomMargin + systemBarInsets.bottom }

    private fun centerStartParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL or Gravity.START,
    ).apply { leftMargin = 12 + systemBarInsets.left }

    private fun centerEndParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL or Gravity.END,
    ).apply { rightMargin = 12 + systemBarInsets.right }

    private fun bottomStartParams() = FrameLayout.LayoutParams(
        (150 * resources.displayMetrics.density).toInt(),
        (130 * resources.displayMetrics.density).toInt(),
        Gravity.BOTTOM or Gravity.START,
    ).apply { leftMargin = 12 + systemBarInsets.left; bottomMargin = 12 + systemBarInsets.bottom }

    // marginPx used to be a bare "12" here (raw pixels, not dp) -- harmless before the API 36
    // target bump only because the OS auto-reserved system bar space back then, keeping this
    // corner comfortably clear of the nav bar regardless of how tiny the margin actually was.
    // Once edge-to-edge became enforced, that tiny margin put this label's text directly under/
    // through the gesture nav bar (confirmed via emulator screenshot, 2026-08-01) -- fixed to
    // match every other *Params helper's proper dp-scaled margin, plus the same systemBarInsets
    // fold-in the rest of them now use.
    private fun bottomEndParams(): FrameLayout.LayoutParams {
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            bottomMargin = marginPx + systemBarInsets.bottom
            rightMargin = marginPx + systemBarInsets.right
        }
    }

    /** Each virtual-controller cluster's own width -- basis is `min(width, height)`, i.e.
     * whichever screen dimension is currently the *short* one: the full screen width in portrait
     * (today's original formula), but the full screen *height* in landscape once rotated, not the
     * (much larger) width -- naively reusing "45% of width" unchanged in landscape would size each
     * cluster at nearly a whole portrait-screen's width, comically large for a side margin. Using
     * the short dimension both ways keeps each cluster roughly the same *physical* thumb-reachable
     * size in both orientations, matching David's "exactly the same as portrait" ask (2026-08-01)
     * -- ergonomically the same control, just re-anchored to a different pair of edges. Shared by
     * [virtualClusterParams] and [menuOverlayParams], which both need to agree on this size. */
    private fun clusterWidthPx(): Float =
        minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * VirtualClusterView.WIDTH_FRACTION_OF_SCREEN

    /** Each virtual-controller cluster is a fixed-width block (not stretched to fill its half of
     * the screen -- see VirtualClusterView's class doc for why: the same width-driven internal
     * layout drops into either a portrait bottom bar or a landscape side-margin completely
     * unchanged, only this function's *positioning* differs). Needs an explicit pixel width,
     * unlike this file's other *Params helpers -- VirtualClusterView.onMeasure reads the resolved
     * MeasureSpec size directly to lay itself out, which WRAP_CONTENT alone wouldn't reliably
     * supply.
     *
     * Portrait: bottom-anchored, side-by-side (today's original layout). Landscape (added
     * 2026-08-01): left/right-edge-anchored instead, vertically centered -- the direct rotation
     * of the same idea, edge-anchored on the axis the two clusters *aren't* spread along either
     * way (bottom in portrait, since they're spread left-right; vertically centered in landscape,
     * since they're spread by being on opposite edges, not stacked). */
    private fun virtualClusterParams(startSide: Boolean): FrameLayout.LayoutParams {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        val gravity = if (portrait) {
            Gravity.BOTTOM or if (startSide) Gravity.START else Gravity.END
        } else {
            Gravity.CENTER_VERTICAL or if (startSide) Gravity.START else Gravity.END
        }
        return FrameLayout.LayoutParams(clusterWidthPx().toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, gravity).apply {
            leftMargin = marginPx + systemBarInsets.left
            rightMargin = marginPx + systemBarInsets.right
            if (portrait) bottomMargin = marginPx + systemBarInsets.bottom
        }
    }

    /** [startMenuView]/[settingsMenuView]'s bounds -- fullscreen style, per StartMenuView's class
     * doc, meaning it consumes *every* touch within its own bounds while open. Confined here to
     * stop exactly outside the virtual control bar's reserved footprint (bottom strip in portrait,
     * left/right strips in landscape), rather than the old blanket MATCH_PARENT -- confirmed via
     * emulator testing as a real bug otherwise: the menu's own tile grid physically overlapped the
     * same screen region the virtual controller's pills sit in, so a tap meant to reach a control
     * (e.g. the Start pill again, to close the menu) instead landed on whatever tile happened to
     * occupy that position (one test run accidentally triggered Reset this way). Confining the
     * menu's bounds this way also means no dedicated "Close" affordance is needed for touch-only
     * play: the virtual controller (never covered) stays reachable the whole time a menu is open,
     * and its B/Back button already calls goBackOneLevel() via handleRotationButton -- the exact
     * same one-press-closes-from-the-top-level behavior [menuButton]'s own now-unused "Close"
     * label used to describe.
     *
     * This is a first, minimal slice of a larger reserved-control-strip redesign discussed
     * 2026-08-01 (constant-width-relative-to-screen control regions defining an inset "game
     * display" the puzzle's camera could eventually frame itself around too, not just menus) --
     * HypercubeRenderer's landscape viewport doesn't reserve side margins for the new controls yet
     * (its existing fixed-vertical-FOV layout already leaves some natural side margin -- revisit
     * once real-device testing shows whether that's enough). Recomputed on every call (not
     * cached), same reasoning as [virtualClusterParams] -- reserved space is derived from the
     * *current* screen size, which changes on every rotation. */
    private fun menuOverlayParams(): FrameLayout.LayoutParams {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val clusterWidth = clusterWidthPx()
        val marginPx = 12 * resources.displayMetrics.density
        return if (portrait) {
            // Matches virtualClusterParams's own portrait bottomMargin (marginPx + insets.bottom)
            // exactly -- this reserved strip has to be at least as tall as the cluster's actual
            // footprint, insets included, or the menu would creep back into the space the system
            // bar fold-in just cleared for the controls.
            val reservedBottom = (VirtualClusterView.heightForWidth(clusterWidth) + marginPx * 2).toInt() + systemBarInsets.bottom
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels - reservedBottom,
                Gravity.TOP,
            )
        } else {
            // CENTER_HORIZONTAL with a symmetric reduced width can't reserve *different* amounts
            // per side, so this uses whichever side's inset is larger for both -- conservative
            // (never under-reserves), at worst wastes a little width on the smaller-inset side,
            // which in practice is usually 0 anyway (landscape nav-bar insets are typically
            // one-sided or absent, not equal-on-both-sides).
            val sideInset = maxOf(systemBarInsets.left, systemBarInsets.right)
            val reservedSide = (clusterWidth + marginPx * 2).toInt() + sideInset
            FrameLayout.LayoutParams(
                resources.displayMetrics.widthPixels - reservedSide * 2,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL,
            )
        }
    }

    /** Re-applies [menuOverlayParams] to both fullscreen menus -- called once from build4DScreen
     * and again from [onConfigurationChanged], same "recompute, don't just toggle visibility"
     * reasoning as [updateControlVisibility]. A fresh LayoutParams instance per
     * view, deliberately, not one shared object -- Android views don't reliably share a single
     * LayoutParams instance across siblings. */
    private fun updateMenuOverlayBounds() {
        if (!::startMenuView.isInitialized) return
        startMenuView.layoutParams = menuOverlayParams()
        settingsMenuView.layoutParams = menuOverlayParams()
    }

    /** The virtual controller shows in *both* orientations (landscape added 2026-08-01, per
     * David: "exactly the same as portrait but with the two halves moved to either side of the
     * screen") -- [virtualClusterParams] handles the actual repositioning. Which of the virtual
     * clusters vs. the older [gamepadOverlay] HUD is actually *visible* now depends on whether a
     * real gamepad is currently connected ([GamepadInputHandler.anyGamepadConnected], added
     * 2026-08-01 after David found connecting a Bluetooth controller did nothing -- the virtual
     * controls just stayed up, HUD stayed hidden, regardless of a pad actively being used): no
     * pad connected shows the virtual clusters (today's original behavior); a pad connected shows
     * the HUD instead, since the virtual controls would just be redundant screen clutter over
     * whatever's reachable with the pad. [menuButton] stays hidden either way -- the virtual
     * controller's Start pill covers the no-pad case, the pad's own physical Start button covers
     * the other, so it's never actually needed anymore (its "free up more space for the puzzle"
     * removal was explicit, 2026-08-01). The puzzle's own reserved-space math
     * ([HypercubeRenderer]'s viewport, [menuOverlayParams]) is *not* conditioned on this the same
     * way -- it stays constant regardless of which control scheme is currently shown, so the
     * puzzle doesn't jump in size the instant a controller connects or disconnects.
     *
     * Called once right after [build4DScreen] creates both pairs (this also covers a gamepad
     * already connected *before* the app launched, which fires no add/remove event of its own to
     * react to), again from [onConfigurationChanged] on every rotation (clusters still need
     * repositioning even when their visibility doesn't change), and again from
     * [GamepadInputHandler.onGamepadConnectionChanged] on every connect/disconnect. Deliberately
     * never calls [rebuildUi]/[build4DScreen] again itself -- that would wipe moveHistory4D (see
     * [pendingRestoreState4D]'s doc) for no reason other than a layout change; both pairs already
     * exist, only layout/visibility need to change. A no-op in 3D mode/before build4DScreen has
     * run, since the nullable fields are simply still null there. */
    private fun updateControlVisibility() {
        // Re-derived every call, not just at build4DScreen time -- virtualClusterParams's size
        // and position are derived from the *current* screen dimensions/orientation, which swap
        // on every rotation; reusing whichever LayoutParams instance build4DScreen originally
        // created would leave both clusters sized/positioned for whatever orientation was active
        // on first build (confirmed as a real bug via emulator screenshot, pre-dating landscape
        // support: rotating after launch left each cluster ~45% of the old landscape width, which
        // is nearly the *entire* portrait width, so the two fully overlapped instead of sitting
        // in their own halves).
        virtualClusterLeft?.layoutParams = virtualClusterParams(startSide = true)
        virtualClusterRight?.layoutParams = virtualClusterParams(startSide = false)
        val realGamepadConnected = GamepadInputHandler.anyGamepadConnected()
        virtualClusterLeft?.visibility = if (realGamepadConnected) View.GONE else View.VISIBLE
        virtualClusterRight?.visibility = if (realGamepadConnected) View.GONE else View.VISIBLE
        gamepadOverlay?.visibility = if (realGamepadConnected) View.VISIBLE else View.GONE
        if (::menuButton.isInitialized) {
            menuButton.visibility = View.GONE
        }
        // Every *Params call below re-applies LayoutParams that fold in systemBarInsets (see its
        // doc) -- needed not just on rotation (insets can change size, e.g. a landscape nav bar
        // moving to a different edge) but also because this whole function is now *also* called
        // by the OnApplyWindowInsetsListener the moment insets first become known, which happens
        // asynchronously, strictly after these views' *initial* construction-time LayoutParams
        // were already set with systemBarInsets still at NONE. Without this, they'd stay
        // permanently stuck at zero-inset margins from cold start onward -- confirmed as a real
        // bug via emulator screenshot (2026-08-01): the build-number label and gamepadOverlay
        // both stayed put even after systemBarInsets became correctly non-zero elsewhere, since
        // nothing had ever told *them* to recompute.
        lastMoveColumn?.layoutParams = topStartParams()
        statusTextLabel?.layoutParams = topCenterParams()
        buildNumberLabelView?.layoutParams = bottomEndParams()
        gamepadOverlay?.layoutParams = bottomStartParams()
        if (::menuButton.isInitialized) {
            menuButton.layoutParams = bottomCenterParams(bottomMargin = 12)
        }
    }

    /** [AndroidManifest.xml]'s `configChanges="orientation|screenSize|..."` on MainActivity routes
     * rotation here instead of recreating the Activity -- see [updateControlVisibility]'s
     * doc for why that's the only thing a rotation needs to do. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateControlVisibility()
        updateMenuOverlayBounds()
    }

    /** Small, always-present build-identity label. [BuildConfig.VERSION_NAME] is the semantic
     * version (the same string set as `versionName` in build.gradle.kts, e.g. "0.5.0") -- for an
     * actual GitHub release, that's the version the release's git tag names, so a release build's
     * screen should show the same number the tag does. [BuildConfig.GIT_VERSION] is the exact
     * commit this APK was built from (short hash, "-dirty-<timestamp>" suffix if the working tree
     * had uncommitted changes at build time -- see build.gradle.kts's `gitVersion`), so a bug
     * report that includes it can be traced straight back to source, and a tester can confirm
     * on-screen that the build they're looking at is actually the one just installed, instead of
     * an unnoticed stale/not-yet-synced APK (see the discuss_theories_before_acting memory for why
     * that ambiguity is worth eliminating). The timestamp (not just "-dirty") is what makes this
     * work across repeated dirty builds -- a bare hash never changes until the next commit, even
     * across genuinely different uncommitted states. Deliberately dim/unobtrusive -- this is a
     * testing aid, not a feature. */
    private fun buildNumberLabel(): TextView = TextView(this).apply {
        text = "v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.GIT_VERSION}"
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

    /** OS/hardware Back (gesture, button, or a gamepad's BACK-aliased key -- see dispatchKeyEvent's
     * doc on the Retroid's B-aliases-BACK quirk) steps up one menu level instead of exiting the
     * app straight from a nested menu -- see the design doc's accessibility section and
     * [goBackOneLevel]'s doc. Falls through to default (app-exiting) behavior when no menu is
     * open, and only once startMenuView actually exists (before onCreate finishes building it,
     * there's nothing to check). */
    @Suppress("DEPRECATION", "OverrideDeprecatedMigration")
    override fun onBackPressed() {
        if (::startMenuView.isInitialized && activeMenu() != null) {
            goBackOneLevel()
        } else {
            super.onBackPressed()
        }
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

    /** [AppSettings]' fields aren't per-controller (compare [PerControllerSettings], which
     * persists itself) -- just a small flat SharedPreferences file, loaded once here before the
     * first [rebuildUi] so the Settings screen's initial labels are already correct, and saved
     * immediately on every change (see the Settings tiles in [build4DScreen]) rather than
     * deferred to [onPause], since these change rarely and there's no reason to risk losing one. */
    private fun loadAppSettings() {
        val prefs = getSharedPreferences(APP_SETTINGS_PREFS_NAME, MODE_PRIVATE)
        AppSettings.exportFormatIsMC4D = prefs.getBoolean(PREF_EXPORT_FORMAT_MC4D, true)
        AppSettings.confirmBeforeScrambleReset = prefs.getBoolean(PREF_CONFIRM_SCRAMBLE_RESET, false)
    }

    private fun saveAppSettings() {
        getSharedPreferences(APP_SETTINGS_PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_EXPORT_FORMAT_MC4D, AppSettings.exportFormatIsMC4D)
            .putBoolean(PREF_CONFIRM_SCRAMBLE_RESET, AppSettings.confirmBeforeScrambleReset)
            .apply()
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

        // RKT mode's stick-driven I-twist (see build4DScreen's onLeftStick wiring): FIRE is close
        // to full deflection (per David's "full or near-full stick movement" spec) so it can't
        // trigger from an idle stick's ordinary wobble; REARM is a generous margin below that so
        // the stick has to genuinely come back toward center before another push counts.
        private const val RKT_STICK_FIRE_THRESHOLD = 0.8f
        private const val RKT_STICK_REARM_THRESHOLD = 0.4f

        private const val SOLVED_LABEL = "SOLVED"
        private const val SAVE_FILE_NAME = "puzzle_state.json"
        private const val BATTERY_POLL_INTERVAL_MS = 30_000L

        private const val APP_SETTINGS_PREFS_NAME = "app_settings"
        private const val PREF_EXPORT_FORMAT_MC4D = "exportFormatIsMC4D"
        private const val PREF_CONFIRM_SCRAMBLE_RESET = "confirmBeforeScrambleReset"

        // Not `const` -- needs to interpolate BuildConfig.VERSION_NAME (see the CREDITS section
        // below), which isn't a compile-time constant Kotlin's `const val` will accept.
        private val HELP_HTML = """
<b>TOUCH CONTROLS</b><br>
&#8226; Drag the puzzle to rotate the view<br>
&#8226; Pinch to zoom<br>
<br>
<b>GAMEPAD CONTROLS</b><br>
Two selectable input modes &#8212; a single "Mode" tile in the Start Menu toggles between them,
its own label showing whichever one is currently active (see below).<br>
<br>
<b>Mode 1 &#8212; Stick Select (default)</b><br>
&#8226; Left stick: select a cell (deflect toward it, release to keep the selection)<br>
&#8226; D-pad: also selects a cell, same as the left stick &#8212; hold two adjacent directions at
once for a diagonal, for controllers with no left stick<br>
&#8226; Right stick: orbit the view<br>
&#8226; Y / A / X / B: twist the selected cell (Up / Down / Left / Right)<br>
&#8226; R1 / R2 (bumper / trigger): twist the selected cell around its third axis<br>
&#8226; Left stick click (L3) or Button C: rotate the puzzle so the selected cell moves to I
&#8212; Button C exists for controllers with no stick click (e.g. the 8BitDo Micro)<br>
<br>
<b>Mode 2 &#8212; RKT</b><br>
For the final phase of a solve, where every twist is either an I-cell rotation or R itself &#8212;
no cell selection needed, so nothing is highlighted.<br>
&#8226; Left stick: push most of the way left/right or up/down, then let it return to center, to
twist I as IU/IU'/IR/IR' &#8212; same twists as the D-pad below, just an alternate way to reach
them; push again (after returning to center) for another twist<br>
&#8226; Right stick: orbit the view, same as mode 1<br>
&#8226; Y / A / X / B, R1 / R2: twist R, same as if it were selected in mode 1<br>
&#8226; D-pad left / right: twist I as IU / IU'<br>
&#8226; D-pad up / down: twist I as IR / IR'<br>
&#8226; L1 / L2 (bumper / trigger): twist I as IF / IF'<br>
&#8226; Button C: unused (Select still does whole-room snap rotation/undo/redo, see below)<br>
<br>
<b>Select (hold): a second layer of controls</b> &#8212; works the same in every mode.<br>
&#8226; Y / A / X / B / R1 / R2: instead of twisting the selected/highlighted cell, snap-rotates
the *entire puzzle* 90&#176; using the same button-to-axis feel an individual twist already has,
just applied to everything at once (the 4D-room equivalent of a whole-cube rotation, as opposed
to a face turn) &#8212; I never moves under any of these.<br>
&#8226; L1 (bumper): Undo.<br>
&#8226; L2 (trigger): Redo.<br>
Release Select to go back to normal twisting/navigation.<br>
<br>
<b>TOP-LEFT STATUS</b><br>
&#8226; Turns: twists made since the last scramble (or reset)<br>
&#8226; Selected X: showing Y -- the room slot that will actually twist right now, and which native
cell currently occupies it<br>
&#8226; Filter: which piece-type filters (if any) are currently hiding pieces<br>
&#8226; Mode: Stick or RKT -- the active input mode (see the Start Menu's Mode tile)<br>
&#8226; Battery: N% -- the connected gamepad's battery level, if it reports one (many wired
controllers, and Android versions before 12, never do -- blank when unavailable)<br>
<br>
<b>START MENU</b><br>
Press Start (real gamepad) or tap the virtual controller's Start pill (touch) to open it. Move
the highlight with the left stick, the D-pad, or the virtual controller's own stick -- it shows a
stick here even in RKT mode, since RKT's D-pad has no way to navigate a menu. Confirm/Back are
whichever face buttons sit physically bottom/right on a gamepad, or the virtual controller's
matching A/B buttons. Press Start again (or tap it again) to close from anywhere, or Back to step
out one level at a time (submenu &#8594; menu &#8594; resume puzzle).<br>
&#8226; Filters: opens a submenu of piece-type toggles (Centers / Ridges / 3c Edges / 4c Corners,
by how many colors a piece shows) &#8212; drawn as a small see-through panel so the puzzle stays
visible and draggable while you adjust them<br>
&#8226; Scramble / Reset<br>
&#8226; Mode: toggles between Stick and RKT input (see above)<br>
&#8226; Settings: Nintendo ABXY, Z Dir Left/Right, Export Format (MC4D or hypercubing.xyz-style
Log), and Confirm Scramble/Reset -- see below<br>
&#8226; Export: runs whichever format Settings' Export Format row specifies<br>
&#8226; Help: this screen<br>
<br>
<b>4D SETTINGS</b><br>
&#8226; Controller: shows which controller these three rows apply to -- whichever one most
recently sent input, saved per-controller so different pads can have different settings
(press a button on the one you want to change before adjusting it)<br>
&#8226; Nintendo ABXY: swaps A&#8596;B and X&#8596;Y, for controllers/modes reporting face buttons
in Nintendo's layout instead of Xbox's<br>
&#8226; Z Dir Left / Z Dir Right: independently swaps L1&#8596;L2 or R1&#8596;R2, for controllers
whose bumper/trigger arrangement makes one or both sides feel backwards<br>
&#8226; Export Format: MC4D (a real MagicCube4D .log file) or Log (hypercubing.xyz-style community
notation) -- whichever the Start Menu's Export tile runs; app-wide, not per-controller<br>
&#8226; Confirm Scramble/Reset: ask before actually scrambling or resetting; also app-wide<br>
<br>
<b>GAMEPAD OVERLAY</b><br>
The small controller diagram in the bottom-left corner lights up buttons and sticks live as they're used &#8212; handy for confirming exactly which input produced a twist, e.g. when reviewing a screen recording.<br>
<br>
<b>CREDITS</b><br>
Version ${BuildConfig.VERSION_NAME}<br>
twisted4d was developed by David Barr. It was inspired by:<br>
&#8226; Hyperspeedcube, a 3D/4D twisty puzzle simulator by Andrew Farkas (HactarCE)<br>
&#8226; MagicCube4D, Hyperspeedcube's predecessor, by Don Hatch, Melinda Green, Jay Berkenbilt, and
Roice Nelson<br>
&#8226; MagicCube4D's Android port, also by Melinda Green<br>
&#8226; MagicCube4D (Raynefork), Raymond Zhao's fork of that Android port adding features and
modern-Android compatibility<br>
"""
    }
}
