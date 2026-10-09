package com.example.tvremote

import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Locale

/**
 * Translates command strings into Android input, using the best path available at runtime:
 *
 *  1. PRIVILEGED  – Instrumentation().sendKeyDownUpSync(): works system-wide, but only if the app
 *                   holds INJECT_EVENTS (platform-signed / priv-app builds). Otherwise it throws
 *                   SecurityException as soon as another app's window has focus.
 *  2. PLATFORM APIs – AudioManager for volume + media keys (no special permission).
 *  3. ACCESSIBILITY – D-pad, Back, Home, Recents, cursor clicks/scroll (user enables the service once).
 *
 * Returns null on success or an error code string that is relayed to the phone.
 */
class CommandDispatcher(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    // Instrumentation.sendKeyDownUpSync() throws if called on the main thread.
    private val injectorThread = HandlerThread("input-injector").apply { start() }
    private val injectorHandler = Handler(injectorThread.looper)
    private val instrumentation by lazy { Instrumentation() }

    val hasPrivilegedInjection: Boolean
        get() = context.checkSelfPermission("android.permission.INJECT_EVENTS") ==
            PackageManager.PERMISSION_GRANTED

    fun capabilities(): JSONObject = JSONObject()
        .put("privileged_injection", hasPrivilegedInjection)
        .put("accessibility", RemoteAccessibilityService.instance != null)

    fun handleCommand(raw: String): String? {
        val command = raw.trim().uppercase(Locale.ROOT)

        // Pointer commands (accessibility only)
        when (command) {
            "MOUSE_CLICK" -> return withAccessibility { it.clickAtCursor() }
            "SCROLL_DOWN" -> return withAccessibility { it.scrollAtCursor(+1) }
            "SCROLL_UP" -> return withAccessibility { it.scrollAtCursor(-1) }
        }

        val keyCode = KEY_MAP[command] ?: return ERR_UNKNOWN_COMMAND

        // 1. System-wide injection, if we are privileged
        if (hasPrivilegedInjection) {
            injectPrivileged(keyCode)
            return null
        }

        // 2. Volume / media via public APIs
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> return adjustVolume(AudioManager.ADJUST_RAISE)
            KeyEvent.KEYCODE_VOLUME_DOWN -> return adjustVolume(AudioManager.ADJUST_LOWER)
            KeyEvent.KEYCODE_VOLUME_MUTE -> return adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
        }
        if (keyCode in MEDIA_KEYS) {
            dispatchMediaKey(keyCode)
            return null
        }

        // 3. Accessibility service
        val service = RemoteAccessibilityService.instance ?: return ERR_ACCESSIBILITY_DISABLED
        if (keyCode !in RemoteAccessibilityService.SUPPORTED_KEYS) return ERR_NEEDS_PRIVILEGE
        main.post { service.handleKey(keyCode) }
        return null
    }

    /** mode "rel": x/y are pixel deltas. mode "abs": x/y are 0..1 fractions of the screen. */
    fun handleMouse(x: Double, y: Double, mode: String) {
        if (x.isNaN() || y.isNaN()) return
        val service = RemoteAccessibilityService.instance ?: return
        main.post {
            if (mode.equals("abs", ignoreCase = true)) service.cursorTo(x.toFloat(), y.toFloat())
            else service.cursorBy(x.toFloat(), y.toFloat())
        }
    }

    fun release() {
        injectorThread.quitSafely()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun injectPrivileged(keyCode: Int) {
        injectorHandler.post {
            try {
                instrumentation.sendKeyDownUpSync(keyCode)
            } catch (e: SecurityException) {
                Log.e(TAG, "INJECT_EVENTS not effective", e)
            }
        }
    }

    private fun adjustVolume(direction: Int): String? {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return null
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun withAccessibility(block: (RemoteAccessibilityService) -> Unit): String? {
        val service = RemoteAccessibilityService.instance ?: return ERR_ACCESSIBILITY_DISABLED
        main.post { block(service) }
        return null
    }

    private companion object {
        const val TAG = "CommandDispatcher"
        const val ERR_UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
        const val ERR_ACCESSIBILITY_DISABLED = "ACCESSIBILITY_DISABLED"
        const val ERR_NEEDS_PRIVILEGE = "REQUIRES_PRIVILEGED_INJECTION"

        val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

        val KEY_MAP: Map<String, Int> = mapOf(
            "DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "DPAD_CENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
            "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
            "ENTER" to KeyEvent.KEYCODE_ENTER,
            "BACK" to KeyEvent.KEYCODE_BACK,
            "HOME" to KeyEvent.KEYCODE_HOME,
            "RECENTS" to KeyEvent.KEYCODE_APP_SWITCH,
            "MENU" to KeyEvent.KEYCODE_MENU,
            "SETTINGS" to KeyEvent.KEYCODE_SETTINGS,
            "SEARCH" to KeyEvent.KEYCODE_SEARCH,
            "CHANNEL_UP" to KeyEvent.KEYCODE_CHANNEL_UP,
            "CHANNEL_DOWN" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "VOLUME_UP" to KeyEvent.KEYCODE_VOLUME_UP,
            "VOLUME_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
            "VOLUME_MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "PLAY_PAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "PLAY" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "PAUSE" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "STOP" to KeyEvent.KEYCODE_MEDIA_STOP,
            "NEXT" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "PREVIOUS" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "REWIND" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "FAST_FORWARD" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        ) + (0..9).associate { "NUM_$it" to KeyEvent.KEYCODE_0 + it }
    }
}
