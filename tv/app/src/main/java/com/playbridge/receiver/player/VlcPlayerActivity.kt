package com.playbridge.receiver.player

import android.content.Intent
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.playbridge.receiver.server.ServerService
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

class VlcPlayerActivity : PlayerActivity(), IVLCVout.Callback {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var surfaceView: SurfaceView

    override fun play() { mediaPlayer?.play() }
    override fun pause() { mediaPlayer?.pause() }
    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    override fun getMediaDuration(): Long = mediaPlayer?.length ?: 0L
    override fun getCurrentPosition(): Long = (mediaPlayer?.time) ?: 0L
    override fun seekTo(position: Long) { mediaPlayer?.time = position }

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ServerService.ACTION_REMOTE) {
                val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                when (key) {
                    "up", "down", "left", "right", "enter", "back" -> {
                        // Handle remote dpad keys
                    }
                    else -> {}
                }
            } else if (intent?.action == ServerService.ACTION_CONTROL) {
                when (intent.getStringExtra(ServerService.EXTRA_COMMAND)) {
                    "play_pause" -> if (isPlaying()) pause() else play()
                    "stop" -> finish()
                    "seek_fwd" -> seekTo((getCurrentPosition() + 10000).coerceAtMost(getMediaDuration()))
                    "seek_rev" -> seekTo((getCurrentPosition() - 10000).coerceAtLeast(0))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup simple layout for VLC
        val frameLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frameLayout.addView(surfaceView)
        setContentView(frameLayout)

        // Setup VLC
        val args = ArrayList<String>().apply {
            add("-vvv") // Verbosity
            add("--drop-late-frames")
            add("--skip-frames")
        }
        libVLC = LibVLC(this, args)
        mediaPlayer = MediaPlayer(libVLC)

        mediaPlayer?.vlcVout?.apply {
            setVideoView(surfaceView)
            addCallback(this@VlcPlayerActivity)
            attachViews()
        }

        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_CONTROL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(remoteReceiver, filter)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(ServerService.EXTRA_URL)
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)

        @Suppress("UNCHECKED_CAST")
        val headers = intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? HashMap<String, String>

        if (url == null) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (title != null) {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
        }

        playVideo(url, headers)
    }

    private fun playVideo(url: String, headers: Map<String, String>?) {
        val media = Media(libVLC, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)

            // Apply headers to VLC
            headers?.forEach { (key, value) ->
                when (key.lowercase()) {
                    "user-agent" -> addOption(":http-user-agent=$value")
                    "referer" -> addOption(":http-referrer=$value")
                }
            }

            // Reconstruct remaining custom headers to VLC's format if they are not agent/referer
            val customHeaders = headers?.filter { entry ->
                val lowerKey = entry.key.lowercase()
                lowerKey != "user-agent" && lowerKey != "referer"
            }?.map { "${it.key}: ${it.value}" }?.joinToString("\r\n")

            if (!customHeaders.isNullOrBlank()) {
                addOption(":http-custom-headers=$customHeaders")
            }
        }

        mediaPlayer?.media = media
        media.release()

        mediaPlayer?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(remoteReceiver)
        mediaPlayer?.vlcVout?.apply {
            removeCallback(this@VlcPlayerActivity)
            detachViews()
        }
        mediaPlayer?.release()
        libVLC?.release()
    }

    // IVLCVout.Callback
    override fun onSurfacesCreated(vout: IVLCVout?) {}
    override fun onSurfacesDestroyed(vout: IVLCVout?) {}
}