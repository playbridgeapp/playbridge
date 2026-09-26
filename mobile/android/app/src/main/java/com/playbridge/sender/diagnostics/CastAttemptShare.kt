package com.playbridge.sender.diagnostics

import android.content.Context
import android.content.Intent

fun shareCastAttempt(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "Share cast diagnostics"))
}
