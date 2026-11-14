package com.playbridge.player.mirror

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.playbridge.player.browser.BrowserChrome
import com.playbridge.player.server.ServerService
import org.webrtc.SurfaceViewRenderer

class ScreenMirrorActivity : ComponentActivity() {
    private lateinit var renderer: SurfaceViewRenderer
    private var chrome: View? = null
    private val fadeChrome = Runnable {
        chrome?.animate()?.alpha(0f)?.setDuration(600L)?.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderer = SurfaceViewRenderer(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                renderer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            addView(buildChrome(), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        })
        showChromeBriefly()
        attachRenderer()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                ServerService.screenMirrorController()?.stop()
                finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showChromeBriefly()
        attachRenderer()
    }

    override fun onDestroy() {
        chrome?.removeCallbacks(fadeChrome)
        ServerService.screenMirrorController()?.detachRenderer(renderer)
        super.onDestroy()
    }

    private fun attachRenderer() {
        val controller = ServerService.screenMirrorController()
        if (controller == null) {
            finish()
            return
        }
        controller.attachRenderer(renderer) {
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    /**
     * On-screen label over the mirrored picture: who is mirroring, and how to stop. It fades after a
     * few seconds and never takes focus or touches input.
     */
    private fun buildChrome(): View {
        // Sizes are written against the 1280 dp design canvas, as in the Compose screens.
        val density = com.playbridge.player.ui.theme.designDensity(resources.displayMetrics)
        fun dp(v: Int) = (v * density).toInt()
        val pill = TextView(this).apply {
            text = "Mirroring your phone"
            setTextColor(BrowserChrome.text)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17f * density)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(BrowserChrome.card)
            }
        }
        val hint = TextView(this).apply {
            text = "This label fades after a few seconds. Press Back to stop, or stop from the phone."
            setTextColor(BrowserChrome.muted)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * density)
            setLineSpacing(0f, 1.2f)
        }
        return FrameLayout(this).apply {
            isFocusable = false
            addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(56), dp(40), 0, 0)
            })
            addView(hint, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(dp(56), 0, 0, dp(44))
            })
            chrome = this
        }
    }

    private fun showChromeBriefly() {
        chrome?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.removeCallbacks(fadeChrome)
            it.postDelayed(fadeChrome, CHROME_VISIBLE_MS)
        }
    }

    private companion object {
        const val CHROME_VISIBLE_MS = 6_000L
    }
}
