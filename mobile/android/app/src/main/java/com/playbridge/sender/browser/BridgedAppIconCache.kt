package com.playbridge.sender.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Installation artwork survives launches, including a failed lookup, without third-party services. */
internal object BridgedAppIconCache {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val memory = LruCache<String, Bitmap>(64)
    private val generations = mutableMapOf<String, Int>()

    private fun file(context: Context, origin: String, suffix: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "bridged-app-icons"), "$key.$suffix")
    }

    suspend fun load(context: Context, app: BridgedApp): Bitmap? = withContext(Dispatchers.IO) {
        locks.getOrPut(app.origin) { Mutex() }.withLock {
            val generation = synchronized(generations) { generations[app.origin] ?: 0 }
            val image = file(context, app.origin, "png")
            val marker = file(context, app.origin, "source")
            val source = "v1:${app.iconUrl.orEmpty()}"
            if (marker.exists() && marker.readText() == source) return@withLock cached(context, app)
            val bitmap = BridgedAppAppearance.selectIcon(app, ::fetch)
            synchronized(generations) {
                // A removed installation must not leave a late download behind.
                if ((generations[app.origin] ?: 0) == generation) {
                    image.parentFile?.mkdirs()
                    if (bitmap != null) {
                        val temporary = File(image.path + ".tmp")
                        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        temporary.renameTo(image)
                        memory.put("${app.origin}\n$source", bitmap)
                    } else image.delete()
                    marker.writeText(source)
                }
            }
            bitmap
        }
    }

    fun remove(context: Context, origin: String) = synchronized(generations) {
        generations[origin] = (generations[origin] ?: 0) + 1
        memory.snapshot().keys.filter { it.startsWith("$origin\n") }.forEach(memory::remove)
        file(context, origin, "png").delete()
        file(context, origin, "source").delete()
        Unit
    }

    fun cached(context: Context, app: BridgedApp): Bitmap? {
        val source = "v1:${app.iconUrl.orEmpty()}"
        val key = "${app.origin}\n$source"
        memory.get(key)?.let { return it }
        return runCatching {
            if (file(context, app.origin, "source").readText() != source) return null
            BitmapFactory.decodeFile(file(context, app.origin, "png").path)?.also { memory.put(key, it) }
        }.getOrNull()
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode != 200 || connection.contentLengthLong > 262144) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 262144) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val size = maxOf(decoded.width, decoded.height)
            if (size <= 128) decoded else Bitmap.createScaledBitmap(decoded,
                (decoded.width * 128 / size).coerceAtLeast(1),
                (decoded.height * 128 / size).coerceAtLeast(1), true).also { decoded.recycle() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
