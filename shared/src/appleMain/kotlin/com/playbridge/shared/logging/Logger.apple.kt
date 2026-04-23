package com.playbridge.shared.logging

import platform.Foundation.NSLog

actual val logger: Logger = object : Logger {
    override fun d(tag: String, msg: String) { NSLog("D/$tag: $msg") }
    override fun i(tag: String, msg: String) { NSLog("I/$tag: $msg") }
    override fun w(tag: String, msg: String, t: Throwable?) {
        NSLog("W/$tag: $msg ${t?.stackTraceToString() ?: ""}")
    }
    override fun e(tag: String, msg: String, t: Throwable?) {
        NSLog("E/$tag: $msg ${t?.stackTraceToString() ?: ""}")
    }
}
