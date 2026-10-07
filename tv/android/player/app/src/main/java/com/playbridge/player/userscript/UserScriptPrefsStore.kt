package com.playbridge.player.userscript

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred

/**
 * Approval records live in SharedPreferences (`user_scripts`). A same-app snapshot file
 * is rewritten on every change so the `:web` process can read the hash gate without
 * a stale cross-process preference cache. The snapshot is internal storage, not the
 * external scripts directory, so a file dropped next to a script cannot approve it.
 */
@SuppressLint("ApplySharedPref") // commit() so a crash cannot approve a script that never reached disk
class UserScriptPrefsStore(context: Context) : UserScriptApprovalStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(USER_SCRIPT_PREFS, Context.MODE_PRIVATE)
    private val snapshot = File(appContext.filesDir, USER_SCRIPT_SNAPSHOT)

    init {
        writeSnapshot(prefs.getString(KEY_APPROVALS, "") ?: "")
    }

    override fun isInstallEnabled(): Boolean = prefs.getBoolean(KEY_INSTALL_ENABLED, false)

    override fun setInstallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INSTALL_ENABLED, enabled).commit()
    }

    override fun isMigrationComplete(): Boolean = prefs.getBoolean(KEY_MIGRATION_COMPLETE, false)

    override fun markMigrationComplete() {
        prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).commit()
    }

    override fun get(name: String): StoredApproval? = all()[name]

    override fun put(name: String, record: StoredApproval) = synchronized(this) {
        val updated = all().toMutableMap()
        updated[name] = record
        persist(updated)
    }

    override fun remove(name: String) = synchronized(this) {
        val updated = all().toMutableMap()
        updated.remove(name)
        persist(updated)
    }

    override fun all(): Map<String, StoredApproval> =
        UserScriptApprovals.decode(prefs.getString(KEY_APPROVALS, "") ?: "")

    private fun persist(records: Map<String, StoredApproval>) {
        val encoded = UserScriptApprovals.encode(records)
        prefs.edit().putString(KEY_APPROVALS, encoded).commit()
        writeSnapshot(encoded)
    }

    private fun writeSnapshot(text: String) {
        try {
            snapshot.parentFile?.mkdirs()
            val tmp = File(snapshot.parentFile, snapshot.name + ".tmp")
            tmp.writeText(text)
            Files.move(
                tmp.toPath(),
                snapshot.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            try {
                snapshot.writeText(text)
            } catch (_: Exception) {
                // Fail closed: the engine treats a missing snapshot as no approvals.
            }
        }
    }

    companion object {
        private const val KEY_INSTALL_ENABLED = "install_enabled"
        private const val KEY_MIGRATION_COMPLETE = "migration_complete"
        private const val KEY_APPROVALS = "approvals_json"

        fun snapshotFile(context: Context): File =
            File(context.applicationContext.filesDir, USER_SCRIPT_SNAPSHOT)
    }
}

/**
 * One controller per process so a phone install and a settings approve cannot race,
 * and so the prompt callback can be attached when [com.playbridge.player.server.ServerService] starts.
 */
object UserScriptRuntime {
    private val lock = Any()
    private var controller: UserScriptController? = null
    private var publish: ((UserScriptReview, CompletableDeferred<Boolean>) -> Unit)? = null
    private var clear: (() -> Unit)? = null

    fun attach(
        context: Context,
        publishPrompt: (UserScriptReview, CompletableDeferred<Boolean>) -> Unit,
        clearPrompt: () -> Unit,
    ): UserScriptController = synchronized(lock) {
        publish = publishPrompt
        clear = clearPrompt
        controller ?: create(context).also { controller = it }
    }

    fun get(context: Context): UserScriptController = synchronized(lock) {
        controller ?: create(context).also { controller = it }
    }

    private fun create(context: Context): UserScriptController {
        val appContext = context.applicationContext
        return UserScriptController(
            scriptsDir = { appContext.getExternalFilesDir(null) },
            store = UserScriptPrefsStore(appContext),
            publishPrompt = { review, decision ->
                val publisher = publish
                if (publisher != null) publisher(review, decision) else decision.complete(false)
            },
            clearPrompt = { clear?.invoke() },
        )
    }
}
