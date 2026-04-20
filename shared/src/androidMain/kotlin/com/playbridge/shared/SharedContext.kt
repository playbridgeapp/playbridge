package com.playbridge.shared

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("StaticFieldLeak")
object SharedContext {
    private var context: Context? = null

    fun init(appContext: Context) {
        if (context == null) {
            context = appContext.applicationContext
        }
    }

    val appContext: Context
        get() = context ?: throw IllegalStateException("SharedContext must be initialized with init(context)")
}
