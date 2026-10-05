package com.playbridge.sender.browser

import android.app.AlertDialog
import android.content.Context
import android.view.MotionEvent

/** Reject the entire gesture after any full/partial overlay, until a clean new down. */
internal class ExtensionDialogTouchGuard {
    private var blockedGesture = false

    fun allows(action: Int, flags: Int): Boolean {
        if (action == MotionEvent.ACTION_DOWN) blockedGesture = false
        if (flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) {
            blockedGesture = true
        }
        return !blockedGesture
    }
}

/** Filters before dispatch to any dialog child, including opt-ins and both buttons. */
internal class TouchProtectedExtensionDialog(context: Context) : AlertDialog(context) {
    private val touchGuard = ExtensionDialogTouchGuard()

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
