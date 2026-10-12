package com.playbridge.sender.cast.atv

import android.content.Context
import java.io.File

/** `filesDir/atv/credentials.bin`. App-private and excluded from backup. */
internal class FilesAtvSecretFile(private val directory: File) : AtvSecretFile {
    constructor(context: Context) : this(File(context.filesDir, "atv"))

    private val file = File(directory, "credentials.bin")

    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(bytes: ByteArray) {
        directory.mkdirs()
        val temporary = File(directory, "credentials.bin.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.writeBytes(bytes)
            temporary.delete()
        }
    }
}
