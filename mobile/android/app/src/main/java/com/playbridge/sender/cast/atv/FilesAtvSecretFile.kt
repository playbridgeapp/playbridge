package com.playbridge.sender.cast.atv

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Injectable filesystem boundary for write/fsync/rename failures in local tests. */
internal open class AtvFileWriter {
    open fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
    }

    open fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** `filesDir/atv/credentials.bin`. App-private and excluded from backup. */
internal class FilesAtvSecretFile(private val directory: File, private val writer: AtvFileWriter = AtvFileWriter()) : AtvSecretFile {
    constructor(context: Context) : this(File(context.filesDir, "atv"))

    private val file = File(directory, "credentials.bin")

    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    @Synchronized
    override fun write(bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException(ATV_SAVE_ERROR)
        val temporary = File(directory, "credentials.bin.tmp")
        try {
            writer.writeAndSync(temporary, bytes)
            writer.replace(temporary, file)
        } finally {
            // A failed rename never falls back to overwriting the live file.
            temporary.delete()
        }
    }
}
