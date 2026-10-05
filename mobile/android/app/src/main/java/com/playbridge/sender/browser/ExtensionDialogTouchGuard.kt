package com.playbridge.sender.browser

import android.app.AlertDialog
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.playbridge.sender.R

/** Reject the entire gesture after any full/partial overlay, until a clean new down. */
internal class ExtensionDialogTouchGuard(private val onBlockedChanged: (Boolean) -> Unit = {}) {
    private var blockedGesture = false

    fun allows(action: Int, flags: Int): Boolean {
        val wasBlocked = blockedGesture
        if (action == MotionEvent.ACTION_DOWN) blockedGesture = false
        if (flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) {
            blockedGesture = true
        }
        if (blockedGesture != wasBlocked) onBlockedChanged(blockedGesture)
        return !blockedGesture
    }
}

/** Filters before dispatch to any dialog child, including opt-ins and both buttons. */
internal class TouchProtectedExtensionDialog(context: Context) : AlertDialog(context) {
    private var overlayWarning: TextView? = null
    private val touchGuard = ExtensionDialogTouchGuard { blocked ->
        overlayWarning?.apply {
            importantForAccessibility = if (blocked) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Live-region visibility updates announce once per blocking episode.
            visibility = if (blocked) View.VISIBLE else View.INVISIBLE
        }
    }

    fun setApprovalContent(content: View) {
        val warning = TextView(context).apply {
            setText(R.string.extension_overlay_touch_warning)
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        overlayWarning = warning
        setView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
            // Keep the notice outside scrolling permissions and reserve its height.
            // Clearing on DOWN must not move any touch targets during that gesture.
            addView(warning, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (touchGuard.allows(event.actionMasked, event.flags)) return super.dispatchTouchEvent(event)

        // Clear pressed/drag state without delivering the potentially activating event.
        // Use a clean CANCEL: View-level obscured-touch filters must not discard it.
        val cancel = MotionEvent.obtain(
            event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, event.x, event.y, event.metaState,
        )
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        return true
    }
}
