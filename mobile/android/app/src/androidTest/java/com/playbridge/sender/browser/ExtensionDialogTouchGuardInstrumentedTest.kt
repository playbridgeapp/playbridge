package com.playbridge.sender.browser

import android.app.AlertDialog
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.playbridge.sender.R
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compile-only without explicit authorization to execute gesture instrumentation. */
class ExtensionDialogTouchGuardInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialogRejectsOverlayTouchesForOptInsAndBothButtonsThenAcceptsCleanInput() {
        lateinit var dialog: TouchProtectedExtensionDialog
        lateinit var privateMode: CheckBox
        lateinit var technicalData: CheckBox
        var approved = false
        var cancelled = false
        compose.runOnIdle {
            val owner = compose.activity
            privateMode = CheckBox(owner).apply { text = "Private browsing" }
            technicalData = CheckBox(owner).apply { text = "Technical data" }
            dialog = TouchProtectedExtensionDialog(owner).apply {
                setTitle("Permission touch guard fixture")
                setApprovalContent(LinearLayout(owner).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(privateMode)
                    addView(technicalData)
                })
                setButton(AlertDialog.BUTTON_POSITIVE, "Approve") { _, _ -> approved = true }
                setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ -> cancelled = true }
                show()
                window!!.decorView.filterTouchesWhenObscured = true
            }
        }
        compose.waitForIdle()
        try {
            compose.runOnIdle {
                val warning = warning(dialog)
                assertTrue(warning.visibility == View.INVISIBLE)
                assertTrue(warning.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                assertTrue(warning.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE)
                val controls = listOf(privateMode, technicalData,
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE), dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                for (control in controls) {
                    assertTrue(control.width > 0 && control.height > 0)
                    for (flag in listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
                        gesture(dialog, control, downFlags = flag)
                        gesture(dialog, control, moveFlags = flag)
                        gesture(dialog, control, upFlags = flag)
                        assertFalse(control.isPressed)
                        assertTrue(warning.visibility == View.VISIBLE)
                        assertTrue(warning.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES)
                    }
                }
            }
            compose.runOnIdle {
                assertFalse(privateMode.isChecked)
                assertFalse(technicalData.isChecked)
                assertFalse(approved)
                assertFalse(cancelled)
                assertTrue(dialog.isShowing)
                gesture(dialog, privateMode)
            }
            compose.runOnIdle {
                assertTrue(privateMode.isChecked)
                assertTrue(warning(dialog).visibility == View.INVISIBLE)
                assertTrue(warning(dialog).importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                gesture(dialog, technicalData)
            }
            compose.runOnIdle {
                assertTrue(technicalData.isChecked)
                gesture(dialog, privateMode, downFlags = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
                assertTrue(privateMode.isChecked)
                assertTrue(technicalData.isChecked)
                gesture(dialog, dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            }
            compose.runOnIdle { assertTrue(approved); assertFalse(cancelled) }
        } finally {
            compose.runOnIdle { dialog.dismiss() }
        }
    }

    private fun warning(dialog: TouchProtectedExtensionDialog): TextView {
        val message = compose.activity.getString(R.string.extension_overlay_touch_warning)
        fun find(view: View): TextView? {
            if (view is TextView && view.text.toString() == message) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return checkNotNull(find(dialog.window!!.decorView))
    }

    private fun gesture(
        dialog: TouchProtectedExtensionDialog,
        target: View,
        downFlags: Int = 0,
        moveFlags: Int = 0,
        upFlags: Int = 0,
    ) {
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val x = location[0] + target.width / 2f
        val y = location[1] + target.height / 2f
        val downTime = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
            val flags = when (action) {
                MotionEvent.ACTION_DOWN -> downFlags
                MotionEvent.ACTION_MOVE -> moveFlags
                else -> upFlags
            }
            val pointer = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
            val event = MotionEvent.obtain(downTime, downTime + index, action, 1,
                arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, flags)
            try {
                dialog.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
