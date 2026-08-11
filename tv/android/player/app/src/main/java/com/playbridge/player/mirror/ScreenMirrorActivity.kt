package com.playbridge.player.mirror

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.playbridge.player.server.ServerService
import org.webrtc.SurfaceViewRenderer

class ScreenMirrorActivity : ComponentActivity() {
    private lateinit var renderer: SurfaceViewRenderer

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
        })
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
        attachRenderer()
    }

    override fun onDestroy() {
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
}
