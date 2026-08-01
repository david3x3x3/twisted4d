package dev.twisted4d.app

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice

/**
 * Per-physical-controller settings for the two toggles that are genuinely about a specific pad's
 * quirks rather than a general app preference (compare [AppSettings], for the ones that aren't):
 * Nintendo ABXY layout and Z Dir Left/Right. Different controllers connected to the same phone
 * can need different values here -- one pad reports Nintendo-position face buttons, another
 * doesn't; one pad's bumper/trigger arrangement feels backwards, another's doesn't -- so a single
 * app-wide value can't satisfy both at once (split into per-controller storage 2026-07-28, per
 * feedback).
 *
 * Keyed by [InputDevice.getDescriptor], which Android documents as a stable identifier for a
 * specific physical device that survives reconnects and reboots (unlike deviceId, which gets
 * reassigned every time a device reconnects). The Settings screen always shows/edits whichever
 * controller most recently sent input (see [current]) -- [noteActiveDevice] must be called on
 * every gamepad key/motion event for that to stay accurate; see
 * [GamepadInputHandler.handleKeyEvent]/[GamepadInputHandler.handleMotionEvent].
 *
 * A controller seen for the first time starts with every toggle off (matching the app's
 * pre-per-controller global defaults), lazily loaded from [prefs] on first sight rather than
 * requiring an upfront index of every known descriptor.
 */
object PerControllerSettings {
    data class Entry(
        var nintendoLayout: Boolean = false,
        var zDirLeft: Boolean = false,
        var zDirRight: Boolean = false,
        var deviceName: String = "",
    )

    private lateinit var prefs: SharedPreferences
    private val cache = mutableMapOf<String, Entry>()

    @Volatile var lastActiveDescriptor: String? = null
        private set

    /** Must be called once, e.g. from `onCreate`, before anything else touches this object.
     * Holds only the application context, so this is safe to keep for the process lifetime. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Call on every gamepad key/motion event so [current] reflects whichever controller is
     * actually in use right now. No-op for devices with no descriptor (some virtual/synthetic
     * input sources report a blank one, which can't be persisted against meaningfully). */
    fun noteActiveDevice(device: InputDevice?) {
        val descriptor = device?.descriptor?.takeIf { it.isNotBlank() } ?: return
        lastActiveDescriptor = descriptor
        val entry = cache.getOrPut(descriptor) { loadEntry(descriptor) }
        if (device.name != null) entry.deviceName = device.name
    }

    /** Same idea as [noteActiveDevice], but for the on-screen touch controller (see
     * VirtualClusterView), which has no real [InputDevice] to read a descriptor from -- a fixed,
     * well-known descriptor instead, since there's only ever one. Call on every virtual button
     * press/stick move so [current] (and the Settings screen's "Controller:" display) follows
     * touch input exactly the way it already follows whichever real pad was used most recently. */
    fun noteVirtualControllerActive() {
        lastActiveDescriptor = VIRTUAL_DESCRIPTOR
        cache.getOrPut(VIRTUAL_DESCRIPTOR) { loadEntry(VIRTUAL_DESCRIPTOR) }.deviceName = "Virtual Controller"
    }

    /** The most-recently-active controller's settings, or null if no gamepad has sent input yet
     * this session (e.g. the app just launched) -- callers should treat that the same as "every
     * toggle off, no controller to attribute a change to". */
    fun current(): Entry? = lastActiveDescriptor?.let { cache[it] }

    /** Persists [entry] for [descriptor] -- callers mutate the [Entry] in place (it's the same
     * object [current] returns) and then call this to write it through. */
    fun save(descriptor: String, entry: Entry) {
        prefs.edit()
            .putBoolean("$descriptor:nintendoLayout", entry.nintendoLayout)
            .putBoolean("$descriptor:zDirLeft", entry.zDirLeft)
            .putBoolean("$descriptor:zDirRight", entry.zDirRight)
            .putString("$descriptor:deviceName", entry.deviceName)
            .apply()
    }

    private fun loadEntry(descriptor: String): Entry = Entry(
        nintendoLayout = prefs.getBoolean("$descriptor:nintendoLayout", false),
        zDirLeft = prefs.getBoolean("$descriptor:zDirLeft", false),
        // The virtual controller's R1/R2 shoulder taps were confirmed backwards vs. their labels
        // on real-device testing (2026-08-01, David's Pixel) -- defaults to swapped for just this
        // one descriptor rather than a code-level swap, so it stays a normal, re-toggleable
        // Settings row like every other per-controller quirk here, not a hardcoded exception.
        zDirRight = prefs.getBoolean("$descriptor:zDirRight", descriptor == VIRTUAL_DESCRIPTOR),
        deviceName = prefs.getString("$descriptor:deviceName", "") ?: "",
    )

    private const val PREFS_NAME = "controller_settings"
    private const val VIRTUAL_DESCRIPTOR = "virtual-controller"
}
