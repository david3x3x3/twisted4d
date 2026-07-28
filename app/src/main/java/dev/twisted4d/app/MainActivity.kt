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
    }

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
         * A no-op at the very start of history. */
        fun performUndo() {
            synchronized(moveHistory4D) {
                if (historyIndex4D > 0) {
                    historyIndex4D--
                    val record = moveHistory4D[historyIndex4D]
                    surfaceView.queueEvent { renderer.undoTwist(record.cell, record.fixAxis2, record.prime) }
                }
            }
            updateTurnCount()
        }

        /** Re-applies whatever move undo last stepped back over and advances [historyIndex4D]
         * again -- see [historyIndex4D]'s doc. A no-op once caught back up to the end of history
         * (nothing to redo, either because nothing was undone or a new move already overwrote the
         * abandoned branch). */
        fun performRedo() {
            synchronized(moveHistory4D) {
                if (historyIndex4D < moveHistory4D.size) {
                    val record = moveHistory4D[historyIndex4D]
                    historyIndex4D++
                    surfaceView.queueEvent { renderer.redoTwist(record.cell, record.fixAxis2, record.prime) }
                }
            }
            updateTurnCount()
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

        gamepadInput = GamepadInputHandler(
            onLeftStick = leftStick@{ x, y ->
                val menu = activeMenu()
                if (menu != null) {
                    // Radial-style menu selection (see StartMenuView.setHighlightFromStick's
                    // doc): hold the stick toward a tile, the highlight follows immediately, then
                    // press Confirm -- not an incremental "move the cursor one step" scheme, so no
                    // edge/arm-detection is needed here, unlike RKT's push-and-return below.
                    menu.setHighlightFromStick(x, y)
                    menuWasOpenLastStick = true
                    return@leftStick
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
            },
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
            on4DRotationButton = rotationButton@{ button ->
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
                    return@rotationButton
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
                        // Y and Z were correct with reverse = primaryPrime directly; X was
                        // confirmed backwards on real-device testing (the Y/A button pair) --
                        // same real-device-handedness-correction pattern as every other
                        // per-axis table in this file (rotationInvertedForCell, the RKT X/Z
                        // prime flip, etc.), not a one-off guess this time.
                        val reverse = if (button.literalAxis == Axis4.X) !button.primaryPrime else button.primaryPrime
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
            },
            on4DNavigate = navigate@{ button ->
                // Start always toggles the *whole* menu system open/closed, regardless of how
                // deep a submenu is open -- takes priority over everything else below, including
                // the Select-held modifier. See toggleTopLevelMenu's doc for how this differs from
                // Back (which steps up one level at a time instead).
                if (button == NavigationButton.START) {
                    toggleTopLevelMenu()
                    return@navigate
                }
                // Swallow everything else while a menu's open (Select-held undo/redo, RKT-mode
                // twists, etc. below) -- highlight movement itself is handled continuously by
                // onLeftStick/onDpadStick above, not by these discrete button presses.
                if (activeMenu() != null) return@navigate
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
            },
        )
        inputManager.registerInputDeviceListener(gamepadInput, null)
        gamepadInput.logAlreadyConnectedDevices()

        // Polled, not event-driven -- see GamepadInputHandler.currentGamepadBatteryFraction's
        // doc. Blank (not "N/A" or similar) when nothing to show, matching statusText's own
        // empty-when-nothing-to-say convention -- most controllers connected over USB, or devices
        // below API 31, will simply never populate this.
        val batteryText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 8, 24, 0)
        }
        val pollBattery = object : Runnable {
            override fun run() {
                val fraction = gamepadInput.currentGamepadBatteryFraction()
                batteryText.text = if (fraction != null) "Battery: ${(fraction * 100).roundToInt()}%" else ""
                batteryPollHandler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
            }
        }
        batteryPollRunnable = pollBattery
        pollBattery.run()

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
        // Blank (matching this column's other blank-when-nothing-to-say fields) when neither
        // filter is active.
        val filterStatusText = TextView(this).apply {
            textSize = 14f
            alpha = 0.6f
            setPadding(24, 0, 24, 8)
        }
        fun updateFilterStatusText() {
            val active = listOfNotNull(
                "Centers".takeIf { renderer.hideCenters },
                "Ridges".takeIf { renderer.hideRidges },
                "3c Edges".takeIf { renderer.hideEdges },
                "4c Corners".takeIf { renderer.hideCorners },
            )
            filterStatusText.text = if (active.isEmpty()) "" else "Filter: ${active.joinToString(", ")}"
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
            val label = Notation.communityNotation(record)
            runOnUiThread { lastMoveText.text = label; updateTurnCount() }
        }

        val lastMoveColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(turnCountText)
            addView(lastMoveText)
            addView(selectedCellText)
            addView(filterStatusText)
            addView(inputModeText)
            addView(batteryText)
        }

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
            lastMoveText.text = ""
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
                MenuTile("Stick\nMode", onSelect = {
                    inputMode = GamepadInputMode.STICK
                    surfaceView.queueEvent { renderer.setInputMode(inputMode) }
                    updateInputModeText()
                    closeAllMenus()
                }),
                MenuTile("Play 3D\nPuzzle", onSelect = {
                    closeAllMenus()
                    is4DMode = false
                    rebuildUi()
                }),
                MenuTile("SETTINGS", onSelect = { rebuildSettingsTiles(); openSettingsMenu() }),
                MenuTile("RKT\nMode", onSelect = {
                    inputMode = GamepadInputMode.RKT
                    surfaceView.queueEvent { renderer.setInputMode(inputMode) }
                    updateInputModeText()
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
        rootLayout.addView(lastMoveColumn, topStartParams())
        rootLayout.addView(gamepadOverlayView(), bottomStartParams())
        rootLayout.addView(buildNumberLabel(), bottomEndParams())
        // Persistent, always-tappable entry point for controller-less users (see the design
        // doc's accessibility section and updateMenuButtonLabel's doc) -- always visible, relabeled
        // Menu/Close/Back by current menu depth. The gamepad Start button opens/closes the same
        // menu via toggleTopLevelMenu() in on4DNavigate above.
        updateMenuButtonLabel()
        rootLayout.addView(menuButton, bottomCenterParams(bottomMargin = 12))
        // Added last so each draws (and, once VISIBLE, receives touch) above every view before it.
        rootLayout.addView(startMenuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(filtersMenuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(settingsMenuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
&#8226; In 4D mode, twisting and cell selection are gamepad-only (see below)<br>
<br>
<b>3D MODE &#8212; GAMEPAD</b><br>
&#8226; Left stick: rotate the view<br>
&#8226; Y / A / X / B: twist U / D / L / R (screen-relative)<br>
&#8226; L1 / R1: twist F / B<br>
&#8226; Hold L2: reverse direction (prime) for any of the above<br>
<br>
<b>4D MODE &#8212; GAMEPAD</b><br>
Two selectable input modes &#8212; pick one from the Start Menu's "Stick Mode" / "RKT Mode"
tiles (see below).<br>
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
&#8226; Button C: unused (Select still does whole-room snap rotation/undo/redo, see above)<br>
<br>
<b>4D TOP-LEFT STATUS</b><br>
&#8226; Turns: twists made since the last scramble (or reset)<br>
&#8226; The last twist performed, in hypercubing.xyz notation (e.g. "RU'")<br>
&#8226; Selected X: showing Y -- the room slot that will actually twist right now, and which native
cell currently occupies it<br>
&#8226; Filter: which piece-type filters (if any) are currently hiding pieces<br>
&#8226; Mode: Stick or RKT -- the active input mode (see the Start Menu's Stick Mode/RKT Mode
tiles)<br>
&#8226; Battery: N% -- the connected gamepad's battery level, if it reports one (many wired
controllers, and Android versions before 12, never do -- blank when unavailable)<br>
<br>
<b>4D START MENU</b><br>
Press Start (or tap the on-screen button in the bottom center, for controller-less use) to open
it. Move the highlight with the left stick or D-pad; Confirm/Back are whichever face buttons sit
physically bottom/right on your pad. Press Start again to close it from anywhere, or Back to step
out one level at a time (submenu &#8594; menu &#8594; resume puzzle). That same on-screen button
relabels itself to match: "Menu" when closed (tap to open), "Close" with the Start Menu open (tap
to exit), "Back" inside a submenu like Filters or Settings (tap to step up one level) -- so it's a
full touch-only substitute for Start/Back, no controller or system gesture required.<br>
&#8226; Filters: opens a submenu of piece-type toggles (Centers / Ridges / 3c Edges / 4c Corners,
by how many colors a piece shows) &#8212; drawn as a small see-through panel so the puzzle stays
visible and draggable while you adjust them<br>
&#8226; Scramble / Reset<br>
&#8226; Stick Mode / RKT Mode: pick the active input mode (see above)<br>
&#8226; Play 3D Puzzle: switch modes<br>
&#8226; Settings: Nintendo ABXY, Z Dir Left/Right, Export Format (MC4D or hypercubing.xyz-style
Log), and Confirm Scramble/Reset -- see below<br>
&#8226; Export: runs whichever format Settings' Export Format row specifies<br>
&#8226; Help: this screen<br>
<br>
<b>4D SETTINGS</b><br>
&#8226; Controller: shows which controller these three rows apply to -- whichever one most
recently sent input, saved per-controller so different pads can have different settings
(press a button on the one you want to change before adjusting it)<br>
&#8226; Nintendo ABXY: swaps A&#8596;B and X&#8596;Y (also affects 3D mode's twist buttons), for
controllers/modes reporting face buttons in Nintendo's layout instead of Xbox's<br>
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
