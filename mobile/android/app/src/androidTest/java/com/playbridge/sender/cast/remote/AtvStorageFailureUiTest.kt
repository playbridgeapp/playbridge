package com.playbridge.sender.cast.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playbridge.sender.cast.AtvPairedTvsSettings
import com.playbridge.sender.cast.atv.ATV_REMOVE_ERROR
import com.playbridge.sender.cast.atv.ATV_SAVE_ERROR
import com.playbridge.sender.cast.atv.AtvCipher
import com.playbridge.sender.cast.atv.AtvCredentialStore
import com.playbridge.sender.cast.atv.AtvPairingFailure
import com.playbridge.sender.cast.atv.AtvPairingPhase
import com.playbridge.sender.cast.atv.AtvPairingUi
import com.playbridge.sender.cast.atv.AtvSecretFile
import com.playbridge.sender.cast.atv.AtvTvRecord
import com.playbridge.sender.cast.remote.ui.AtvPairingContent
import com.playbridge.sender.cast.remote.ui.RemoteHeader
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AtvStorageFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedForgetShowsFixedErrorKeepsTheTvAndSuccessfulRetryRemovesIt() {
        var saved: ByteArray? = null
        var fail = false
        val file = object : AtvSecretFile {
            override fun read() = saved
            override fun write(bytes: ByteArray) {
                if (fail) throw IOException("fixture failure")
                saved = bytes.copyOf()
            }
        }
        val cipher = object : AtvCipher {
            override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 90).toByte() }.toByteArray()
            override fun decrypt(blob: ByteArray) = encrypt(blob)
        }
        val store = AtvCredentialStore(file, cipher)
        store.upsert(AtvTvRecord("ab".repeat(32), null, "Fixture TV", null, emptySet()))
        compose.setContent { MaterialTheme { AtvPairedTvsSettings(store) } }
        compose.onNodeWithContentDescription("Forget Fixture TV").performClick()
        fail = true
        compose.onNode(hasText("Forget") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(ATV_REMOVE_ERROR).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, store.records.value.size) }
        fail = false
        compose.onNode(hasText("Forget") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("No paired TVs").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, AtvCredentialStore(file, cipher).records.value.size) }
    }

    @Test fun pairingFailureShowsOnlyTheFixedStorageMessage() {
        compose.setContent { MaterialTheme {
            AtvPairingContent(AtvPairingUi(AtvPairingPhase.Failed, failure = AtvPairingFailure.Storage), {}, {}, {})
        } }
        compose.onNodeWithText(ATV_SAVE_ERROR).assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry pairing").assertIsDisplayed()
    }

    @Test fun headerOffersPairAgainForUnreadableCredentialsAndShowsConnectionSaveFailure() {
        val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "fixture")
        val state = RemoteUiState(
            receiver = RemoteReceiver(endpoint, "Fixture TV", RemoteProfile.AndroidTv),
            fullControl = FullControlStatus(available = true, needsRepair = true, storageFailure = true),
        )
        compose.setContent { MaterialTheme { RemoteHeader(state, dispatch = {}) } }
        compose.onNodeWithText("Pair again").assertIsDisplayed()
        compose.onNodeWithText(ATV_SAVE_ERROR).assertIsDisplayed()
    }
}
