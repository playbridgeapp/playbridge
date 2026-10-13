package com.playbridge.sender.cast.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.Window
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.playbridge.sender.browser.BrowserActivity

internal val LocalRemoteMotionPolicy = staticCompositionLocalOf { RemoteMotionPolicy(1f, true, true) }
internal val LocalRemotePreferences = staticCompositionLocalOf { RemotePreferences() }

@Composable
internal fun rememberRemoteMotionPolicy(): RemoteMotionPolicy {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    fun scale(): Float = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var animatorScale by remember { mutableFloatStateOf(scale()) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var screenOn by remember { mutableStateOf(power.isInteractive) }
    DisposableEffect(context, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { animatorScale = scale() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            screenOn = power.isInteractive
            animatorScale = scale()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { screenOn = power.isInteractive }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        lifecycle.addObserver(lifecycleObserver)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return RemoteMotionPolicy(animatorScale, resumed, screenOn)
}

@Composable
internal fun rememberRemoteHaptics(levelOverride: RemoteHapticLevel? = null): RemoteHaptics {
    val preferences by rememberUpdatedState(LocalRemotePreferences.current)
    val emit = rememberRemoteHapticEmitter()
    return remember(emit, levelOverride) {
        RemoteHaptics(enabled = { preferences.hapticsEnabled }, emit = { emit(levelOverride ?: preferences.hapticsLevel) })
    }
}

/** Predefined touch effects retain the platform's touch intensity and suppression policy. */
@Suppress("DEPRECATION")
@Composable
internal fun rememberRemoteHapticEmitter(): (RemoteHapticLevel) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val vibrator = remember(context) {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    }
    return remember(view, vibrator) {
        { level ->
            if (vibrator?.hasVibrator() == true && view.isHapticFeedbackEnabled &&
                Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                if (Build.VERSION.SDK_INT >= 29) {
                    val effect = VibrationEffect.createPredefined(remoteHapticEffectId(level))
                    if (Build.VERSION.SDK_INT >= 33) {
                        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
                    } else {
                        vibrator.vibrate(effect, AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    }
                } else {
                    view.performHapticFeedback(remoteHapticFallbackConstant(level))
                }
            }
        }
    }
}

@Composable
internal fun RemoteVolumeKeys(enabled: Boolean, routeIdentity: String, onVolume: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onVolume)
    val activity = remember(context) {
        var owner = context
        while (owner is ContextWrapper && owner !is BrowserActivity) owner = owner.baseContext
        owner as? BrowserActivity
    }
    DisposableEffect(activity, lifecycle, enabled, routeIdentity) {
        var unregister: (() -> Unit)? = null
        fun update() {
            unregister?.invoke()
            unregister = if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                activity?.registerRemoteVolumeKeys { callback(it) }
            } else null
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); unregister?.invoke() }
    }
}

@Composable
internal fun Modifier.remotePressFeedback(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val motion = LocalRemoteMotionPolicy.current
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(if (motion.animate) 80 else 0), label = "remotePress")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Compose sheets/dialogs own a Window and bypass Activity.dispatchKeyEvent. */
@Composable
internal fun RemoteDialogVolumeKeys() {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(context) {
        var owner = context
        while (owner is ContextWrapper && owner !is BrowserActivity) owner = owner.baseContext
        owner as? BrowserActivity
    }
    val window = remember(view) {
        var parent = view.parent
        while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
        (parent as? DialogWindowProvider)?.window
    }
    DisposableEffect(activity, window) {
        val original = window?.callback
        val callback = if (activity != null && original != null) object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                activity.dispatchRemoteVolumeKey(event) || original.dispatchKeyEvent(event)
        } else null
        if (callback != null) window?.callback = callback
        onDispose { if (callback != null && window?.callback === callback) window.callback = original }
    }
}
