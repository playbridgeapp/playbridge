package com.playbridge.sender.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Installed means present, not a promise that the user's extension is enabled. */
enum class UblockSetupStatus {
    CHECKING, NOT_INSTALLED, INSTALLING, CANCELLING, INSTALLED, CHECK_FAILED, INSTALL_FAILED;

    val isBusy: Boolean get() = this == INSTALLING || this == CANCELLING
}

internal typealias CancelUblockInstall = ((Boolean) -> Unit) -> Unit

/** Main-thread-only setup state. Inspection never installs; only a native button starts setup. */
internal class UblockOnboardingSetup(
    private val checkInstalled: ((Boolean?) -> Unit) -> Unit,
    private val install: ((Boolean) -> Unit) -> CancelUblockInstall,
) {
    private val mutableStatus = MutableStateFlow(UblockSetupStatus.CHECKING)
    val status = mutableStatus.asStateFlow()
    private var generation = 0L
    private var cancelInstall: CancelUblockInstall? = null

    fun refresh() {
        if (status.value.isBusy) return
        val current = ++generation
        mutableStatus.value = UblockSetupStatus.CHECKING
        try {
            checkInstalled { installed ->
                if (current != generation || status.value != UblockSetupStatus.CHECKING) return@checkInstalled
                mutableStatus.value = when (installed) {
                    true -> UblockSetupStatus.INSTALLED
                    false -> UblockSetupStatus.NOT_INSTALLED
                    null -> UblockSetupStatus.CHECK_FAILED
                }
            }
        } catch (_: Exception) {
            if (current == generation && status.value == UblockSetupStatus.CHECKING) {
                mutableStatus.value = UblockSetupStatus.CHECK_FAILED
            }
        }
    }

    fun requestInstall() {
        if (status.value != UblockSetupStatus.NOT_INSTALLED && status.value != UblockSetupStatus.INSTALL_FAILED) return
        val current = ++generation
        mutableStatus.value = UblockSetupStatus.INSTALLING
        cancelInstall = null
        // Recheck before installing: preserve existing/legacy copies and their user settings.
        var checked = false
        try {
            checkInstalled { installed ->
                if (checked || current != generation || status.value != UblockSetupStatus.INSTALLING) return@checkInstalled
                checked = true
                when (installed) {
                    true -> mutableStatus.value = UblockSetupStatus.INSTALLED
                    null -> mutableStatus.value = UblockSetupStatus.CHECK_FAILED
                    false -> beginInstall(current)
                }
            }
        } catch (_: Exception) {
            if (current == generation && status.value == UblockSetupStatus.INSTALLING) {
                mutableStatus.value = UblockSetupStatus.CHECK_FAILED
            }
        }
    }

    private fun beginInstall(current: Long) {
        try {
            val cancel = install { success ->
                if (current != generation || !status.value.isBusy) return@install
                val wasCancelling = status.value == UblockSetupStatus.CANCELLING
                cancelInstall = null
                mutableStatus.value = when {
                    success -> UblockSetupStatus.INSTALLED
                    wasCancelling -> UblockSetupStatus.NOT_INSTALLED
                    else -> UblockSetupStatus.INSTALL_FAILED
                }
            }
            if (current == generation && status.value.isBusy) cancelInstall = cancel
        } catch (_: Exception) {
            if (current == generation && status.value.isBusy) mutableStatus.value = UblockSetupStatus.INSTALL_FAILED
        }
    }

    fun cancel() {
        if (status.value != UblockSetupStatus.INSTALLING) return
        val cancel = cancelInstall
        if (cancel == null) {
            // Still checking presence; invalidate that lookup before it can start a download.
            generation++
            mutableStatus.value = UblockSetupStatus.NOT_INSTALLED
            return
        }
        val current = generation
        mutableStatus.value = UblockSetupStatus.CANCELLING
        try {
            cancel { cancelled ->
                if (current != generation || status.value != UblockSetupStatus.CANCELLING) return@cancel
                if (cancelled) {
                    generation++
                    cancelInstall = null
                    mutableStatus.value = UblockSetupStatus.NOT_INSTALLED
                } else {
                    // Gecko may already be committing an approved install. Await its real result.
                    mutableStatus.value = UblockSetupStatus.INSTALLING
                }
            }
        } catch (_: Exception) {
            if (current == generation && status.value == UblockSetupStatus.CANCELLING) {
                mutableStatus.value = UblockSetupStatus.INSTALLING
            }
        }
    }

    fun canFinish(): Boolean = !status.value.isBusy
}
