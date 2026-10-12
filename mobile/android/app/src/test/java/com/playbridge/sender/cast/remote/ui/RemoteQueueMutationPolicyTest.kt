package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemoteCapabilities
import com.playbridge.sender.cast.remote.RemoteProfile
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteQueueState
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.testConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteQueueMutationPolicyTest {
    private val item = RemoteQueueItem(1, "Next", "next")
    @Test fun eachMutationRequiresItsOwnExplicitCapabilityForEveryProfile() {
        for (profile in RemoteProfile.entries) for (remove in Support.entries) for (move in Support.entries) {
            val base = RemoteCapabilities()
            val state = RemoteUiState(receiver = testConfiguration(profile).receiver,
                queue = RemoteQueueState(currentIndex = 0, currentItemId = "current"),
                capabilities = base.copy(queue = base.queue.copy(remove = remove, move = move)))
            val actual = resolveQueueMutations(state, item)
            assertEquals(remove == Support.Supported, actual.remove)
            assertEquals(move == Support.Supported, actual.playNext)
        }
    }
    @Test fun missingIdentityAndCurrentItemCannotBeMutated() {
        val caps = RemoteCapabilities()
        val state = RemoteUiState(queue = RemoteQueueState(currentItemId = "next", currentIndex = 99),
            capabilities = caps.copy(queue = caps.queue.copy(remove = Support.Supported, move = Support.Supported)))
        assertFalse(resolveQueueMutations(state, item).remove)
        assertFalse(resolveQueueMutations(state, item).playNext)
        assertEquals(RemoteQueueMutations(false, false), resolveQueueMutations(state, item.copy(itemId = null)))
        assertFalse(resolveQueueMutations(state.copy(queue = RemoteQueueState(currentIndex = 1)), item).remove)
    }
}
