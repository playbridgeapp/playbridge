package com.playbridge.sender.connection

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/** Shared accessor for the existing PlayBridge phone UUID; never log its result. */
internal object PlayBridgePhoneIdentity {
    fun uuid(prefs: SharedPreferences): String = uuid(
        read = { prefs.getString("pb_phone_uuid", null) },
        write = { value -> prefs.edit { putString("pb_phone_uuid", value) } },
    )

    @Synchronized
    fun uuid(read: () -> String?, write: (String) -> Unit, create: () -> String = { UUID.randomUUID().toString() }): String =
        read() ?: create().also(write)
}
