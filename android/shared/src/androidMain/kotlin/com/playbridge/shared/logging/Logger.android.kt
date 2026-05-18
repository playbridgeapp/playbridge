package com.playbridge.shared.logging

import android.util.Log

actual val logger: Logger = object : Logger {
    override fun d(tag: String, msg: String) { Log.d(tag, msg) }
    override fun i(tag: String, msg: String) { Log.i(tag, msg) }
    override fun w(tag: String, msg: String, t: Throwable?) { Log.w(tag, msg, t) }
    override fun e(tag: String, msg: String, t: Throwable?) { Log.e(tag, msg, t) }
}
