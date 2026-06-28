package com.playbridge.sender.data.backup

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import com.playbridge.sender.data.history.DatabaseProvider
import kotlinx.coroutines.*

class BackupTrigger(private val context: Context, private val scope: CoroutineScope) {
    private val backupManager = BackupManager(context)
    private val prefs = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
    
    private var backupJob: Job? = null
    
    companion object {
        private const val TAG = "BackupTrigger"
        private const val DEBOUNCE_MS = 5 * 60 * 1000L // 5 minutes
    }

    fun start() {
        Log.d(TAG, "Starting BackupTrigger")
        val db = DatabaseProvider.getDatabase(context)
        val tables = arrayOf("history", "bookmarks", "tabs", "installed_addons", "watchlist")
        
        try {
            db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(tables) {
                override fun onInvalidated(tables: Set<String>) {
                    Log.d(TAG, "Database invalidated: $tables, scheduling backup")
                    scheduleBackup()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add InvalidationTracker observer", e)
        }
    }
    
    private fun scheduleBackup() {
        backupJob?.cancel()
        backupJob = scope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            if (isActive) {
                try {
                    val enabled = prefs.getBoolean(BackupManager.KEY_ENABLED, false)
                    if (!enabled) {
                        Log.d(TAG, "Backup is disabled, skipping")
                        return@launch
                    }

                    if (!backupManager.isConfigured()) {
                        Log.w(TAG, "Backup is enabled but not configured correctly")
                        return@launch
                    }

                    var success = false
                    var attempt = 1
                    val maxAttempts = 3
                    val retryDelayMs = 30_000L // 30 seconds

                    while (attempt <= maxAttempts && !success && isActive) {
                        try {
                            Log.d(TAG, "Executing scheduled backup (attempt $attempt/$maxAttempts)")
                            val json = BackupUtils.createExportJson(context)
                            success = backupManager.uploadBackup(json)
                            if (success) {
                                backupManager.saveLastBackupTimestamp()
                                Log.d(TAG, "Automatic backup uploaded successfully")
                            } else {
                                Log.e(TAG, "Automatic backup upload failed on attempt $attempt")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during automatic backup attempt $attempt", e)
                        }

                        if (!success) {
                            if (attempt < maxAttempts && isActive) {
                                val backoffDelay = retryDelayMs * attempt
                                Log.i(TAG, "Retrying backup upload in ${backoffDelay / 1000} seconds...")
                                delay(backoffDelay)
                            }
                            attempt++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in scheduleBackup launcher", e)
                }
            }
        }
    }
}
