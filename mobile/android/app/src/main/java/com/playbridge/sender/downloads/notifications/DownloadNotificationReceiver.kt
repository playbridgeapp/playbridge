package com.playbridge.sender.downloads.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.playbridge.sender.data.downloads.DownloadDao
import com.playbridge.sender.downloads.engine.DownloadRepository
import com.playbridge.sender.downloads.engine.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Explicit, non-exported notification commands use the same repository as DownloadsScreen. */
class DownloadNotificationReceiver : BroadcastReceiver(), KoinComponent {
    private val repository: DownloadRepository by inject()
    private val dao: DownloadDao by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadNotifications.EXTRA_DOWNLOAD_ID) ?: return
        val action = DownloadNotificationAction.entries.firstOrNull { it.name == intent.action } ?: return
        if (action == DownloadNotificationAction.DISMISS) {
            DownloadNotifications(context).refreshSummary(excludingId = id)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = dao.getById(id)?.status ?: return@launch
                when (action) {
                    DownloadNotificationAction.PAUSE -> if (status == DownloadStatus.RUNNING.name ||
                        status == DownloadStatus.QUEUED.name || status == DownloadStatus.MERGING.name) repository.pause(id)
                    DownloadNotificationAction.CANCEL -> if (status != DownloadStatus.DONE.name) repository.cancel(id)
                    DownloadNotificationAction.RESUME, DownloadNotificationAction.RETRY ->
                        if (status == DownloadStatus.PAUSED.name || status == DownloadStatus.FAILED.name) repository.resume(id)
                    DownloadNotificationAction.DISMISS -> Unit
                }
            } catch (error: Exception) {
                Log.w("DownloadNotification", "Action failed (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }
}
