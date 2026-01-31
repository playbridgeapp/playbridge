package com.playbridge.receiver.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * QR code data payload
 */
@Serializable
data class QRCodeData(
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val version: Int = 1
)

/**
 * Generates QR code bitmaps for pairing
 */
object QRGenerator {
    
    private val json = Json { encodeDefaults = true }
    
    /**
     * Generate QR code bitmap from connection info
     */
    fun generate(
        ip: String,
        port: Int,
        token: String,
        deviceName: String,
        size: Int = 512
    ): Bitmap {
        val data = QRCodeData(
            ip = ip,
            port = port,
            token = token,
            name = deviceName
        )
        
        val jsonString = json.encodeToString(data)
        return generateQRBitmap(jsonString, size)
    }
    
    private fun generateQRBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        
        return bitmap
    }
}
