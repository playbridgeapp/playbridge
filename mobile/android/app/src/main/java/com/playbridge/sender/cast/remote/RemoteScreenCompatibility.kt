package com.playbridge.sender.cast.remote

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class RemoteSheetFlags(private val read: () -> RemoteSheet?, private val write: (RemoteSheet?) -> Unit) {
    fun flag(sheet: RemoteSheet): ReadWriteProperty<Any?, Boolean> = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = read() == sheet
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            if (value) write(sheet) else if (read() == sheet) write(null)
        }
    }
}

internal class RemoteScreenPlayback(val position: () -> Long, val playing: () -> Boolean, val seek: (Long) -> Unit, val toggle: () -> Unit)
