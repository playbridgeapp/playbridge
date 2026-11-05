package com.playbridge.sender.player

import android.app.Activity
import android.content.Context
import androidx.core.graphics.createBitmap
import android.provider.Settings
import android.view.Window
import android.graphics.Bitmap
import android.net.Uri
import android.view.PixelCopy
import android.os.Handler
import android.os.HandlerThread
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import androidx.media3.ui.PlayerView

internal fun initialBrightness(window: Window?, context: Context): Float {
    val current = window?.attributes?.screenBrightness ?: -1f
    if (current in 0f..1f) return current
    return try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) {
        0.5f
    }
}

internal fun captureFrame(playerView: PlayerView, onCaptured: (Bitmap?) -> Unit) {
    // TextureView path (current player_view.xml).
    val textureView = findTextureView(playerView)
    if (textureView != null) {
        val bmp = try {
            textureView.bitmap
        } catch (_: Exception) {
            null
        }
        onCaptured(bmp)
        return
    }

    // SurfaceView fallback (if layout is switched back).
    val surfaceView = findSurfaceView(playerView)
    if (surfaceView == null) {
        onCaptured(null)
        return
    }

    val surface = surfaceView.holder.surface
    if (surface == null || !surface.isValid) {
        onCaptured(null)
        return
    }

    val bitmap = createBitmap(surfaceView.width, surfaceView.height)
    val handlerThread = HandlerThread("PixelCopy")
    handlerThread.start()

    try {
        PixelCopy.request(
            surfaceView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                } else {
                    onCaptured(null)
                }
                handlerThread.quitSafely()
            },
            Handler(handlerThread.looper)
        )
    } catch (e: Exception) {
        onCaptured(null)
        handlerThread.quitSafely()
    }
}

internal fun findSurfaceView(view: View): SurfaceView? {
    if (view is SurfaceView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val res = findSurfaceView(child)
            if (res != null) return res
        }
    }
    return null
}

internal fun findTextureView(view: View): android.view.TextureView? {
    if (view is android.view.TextureView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val res = findTextureView(view.getChildAt(i))
            if (res != null) return res
        }
    }
    return null
}

/** Saves [bitmap] to Pictures/PlayBridge; returns its gallery URI, or null if saving failed. */
internal fun saveBitmapToGallery(context: Context, bitmap: Bitmap, title: String): Uri? {
    val filename = "${title.replace(Regex("[^a-zA-Z0-9]"), "_")}_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PlayBridge")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: error("No output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

internal fun enterPip(activity: android.app.Activity) {
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    activity.enterPictureInPictureMode(params)
}
