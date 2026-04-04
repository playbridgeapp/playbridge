package com.playbridge.sender.data.backup

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupManager(private val context: Context) {
    private val client = OkHttpClient()
    private val prefs = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "BackupManager"
        const val KEY_ENDPOINT = "backup_s3_endpoint"
        const val KEY_BUCKET = "backup_s3_bucket"
        const val KEY_ACCESS_KEY = "backup_s3_access_key"
        const val KEY_SECRET_KEY = "backup_s3_secret_key"
        const val KEY_REGION = "backup_s3_region"
        const val KEY_ENABLED = "backup_enabled"
    }

    fun isConfigured(): Boolean {
        return !prefs.getString(KEY_ENDPOINT, "").isNullOrBlank() &&
               !prefs.getString(KEY_BUCKET, "").isNullOrBlank() &&
               !prefs.getString(KEY_ACCESS_KEY, "").isNullOrBlank() &&
               !prefs.getString(KEY_SECRET_KEY, "").isNullOrBlank()
    }

    fun getWeeklyFilename(): String {
        val cal = Calendar.getInstance()
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        return "playbridge_backup_week_${week}_${year}.json"
    }

    suspend fun uploadBackup(json: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false

        val endpoint = prefs.getString(KEY_ENDPOINT, "")!!
        val bucket = prefs.getString(KEY_BUCKET, "")!!
        val accessKey = prefs.getString(KEY_ACCESS_KEY, "")!!
        val secretKey = prefs.getString(KEY_SECRET_KEY, "")!!
        val region = prefs.getString(KEY_REGION, "us-east-1")!!
        val filename = getWeeklyFilename()

        val url = if (endpoint.startsWith("http")) {
            "$endpoint/$bucket/$filename"
        } else {
            "https://$bucket.$endpoint/$filename"
        }

        val host = if (endpoint.startsWith("http")) {
            endpoint.substringAfter("://").substringBefore("/")
        } else {
            "$bucket.$endpoint"
        }

        try {
            val contentBody = json.toByteArray()
            val amzDate = getAmzDate()
            val dateStamp = getDateStamp()
            val canonicalUri = if (endpoint.startsWith("http")) "/$bucket/$filename" else "/$filename"
            val service = "s3"
            
            val payloadHash = json.sha256()
            
            val canonicalRequest = "PUT\n" +
                    "$canonicalUri\n" +
                    "\n" +
                    "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n" +
                    "\n" +
                    "host;x-amz-content-sha256;x-amz-date\n" +
                    payloadHash

            val stringToSign = "AWS4-HMAC-SHA256\n" +
                    "$amzDate\n" +
                    "$dateStamp/$region/$service/aws4_request\n" +
                    canonicalRequest.sha256()

            val signingKey = getSignatureKey(secretKey, dateStamp, region, service)
            val signature = hmacSha256(signingKey, stringToSign).toHexString()

            val authorization = "AWS4-HMAC-SHA256 " +
                    "Credential=$accessKey/$dateStamp/$region/$service/aws4_request, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                    "Signature=$signature"

            val request = Request.Builder()
                .url(url)
                .put(contentBody.toRequestBody("application/json".toMediaType()))
                .addHeader("host", host)
                .addHeader("x-amz-date", amzDate)
                .addHeader("x-amz-content-sha256", payloadHash)
                .addHeader("Authorization", authorization)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Upload failed: ${response.code} ${response.message}")
                    Log.e(TAG, response.body?.string() ?: "")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            false
        }
    }

    suspend fun downloadBackup(): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null

        val endpoint = prefs.getString(KEY_ENDPOINT, "")!!
        val bucket = prefs.getString(KEY_BUCKET, "")!!
        val accessKey = prefs.getString(KEY_ACCESS_KEY, "")!!
        val secretKey = prefs.getString(KEY_SECRET_KEY, "")!!
        val region = prefs.getString(KEY_REGION, "us-east-1")!!
        val filename = getWeeklyFilename()

        val url = if (endpoint.startsWith("http")) {
            "$endpoint/$bucket/$filename"
        } else {
            "https://$bucket.$endpoint/$filename"
        }

        val host = if (endpoint.startsWith("http")) {
            endpoint.substringAfter("://").substringBefore("/")
        } else {
            "$bucket.$endpoint"
        }

        try {
            val amzDate = getAmzDate()
            val dateStamp = getDateStamp()
            val canonicalUri = if (endpoint.startsWith("http")) "/$bucket/$filename" else "/$filename"
            val service = "s3"
            
            val payloadHash = "UNSIGNED-PAYLOAD" // For GET we can use UNSIGNED-PAYLOAD
            
            val canonicalRequest = "GET\n" +
                    "$canonicalUri\n" +
                    "\n" +
                    "host:$host\n" +
                    "x-amz-content-sha256:$payloadHash\n" +
                    "x-amz-date:$amzDate\n" +
                    "\n" +
                    "host;x-amz-content-sha256;x-amz-date\n" +
                    payloadHash

            val stringToSign = "AWS4-HMAC-SHA256\n" +
                    "$amzDate\n" +
                    "$dateStamp/$region/$service/aws4_request\n" +
                    canonicalRequest.sha256()

            val signingKey = getSignatureKey(secretKey, dateStamp, region, service)
            val signature = hmacSha256(signingKey, stringToSign).toHexString()

            val authorization = "AWS4-HMAC-SHA256 " +
                    "Credential=$accessKey/$dateStamp/$region/$service/aws4_request, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                    "Signature=$signature"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("host", host)
                .addHeader("x-amz-date", amzDate)
                .addHeader("x-amz-content-sha256", payloadHash)
                .addHeader("Authorization", authorization)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.e(TAG, "Download failed: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            null
        }
    }

    private fun getAmzDate(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    private fun getDateStamp(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(this.toByteArray())
        return hash.toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4$key").toByteArray()
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }
}
