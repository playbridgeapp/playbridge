package com.playbridge.receiver.server

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

private const val TAG = "OverlayWindowHelper"

/**
 * Manages a tiny 1x1 invisible overlay window (TYPE_APPLICATION_OVERLAY).
 *
 * WHY THIS EXISTS:
 * Android 14+ enforces strict Background Activity Launch (BAL) restrictions that block
 * startActivity() from background services, even foreground services. However, one of the
 * BAL exemptions is: "the app has a non-app visible window" (callingUidHasNonAppVisibleWindow).
 * A TYPE_APPLICATION_OVERLAY window counts as a "non-app visible window" and makes the
 * app's UID exempt from BAL — allowing the ServerService to launch PlayerActivity or
 * BrowserActivity when a play/browser command arrives even if the TV app is backgrounded.
 *
 * LIFECYCLE:
 * - show() when a phone connects (called from ServerService)
 * - hide() when the phone disconnects or service is destroyed
 */
internal class OverlayWindowHelper(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    /** Returns true if SYSTEM_ALERT_WINDOW permission is currently granted. */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Shows the invisible overlay window.
     * Safe to call from any thread. No-op if already shown or permission not granted.
     */
    fun show() {
        mainHandler.post { showOnMainThread() }
    }

    private fun showOnMainThread() {
        if (overlayView != null) return
        if (!canDrawOverlays()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay skipped")
            return
        }

        val params = WindowManager.LayoutParams(
            1, 1,                        // 1x1 px — effectively invisible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val view = View(context).apply { alpha = 0f }
        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay window shown — startActivity() from service now allowed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window", e)
        }
    }

    /**
     * Removes the invisible overlay window.
     * Safe to call from any thread. No-op if not currently shown.
     */
    fun hide() {
        mainHandler.post { hideOnMainThread() }
    }

    private fun hideOnMainThread() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
            Log.d(TAG, "Overlay window removed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay window", e)
        } finally {
            overlayView = null
        }
    }
}
